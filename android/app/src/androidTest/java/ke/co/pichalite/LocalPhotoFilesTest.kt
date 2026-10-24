package ke.co.pichalite

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalPhotoFilesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority get() = "${context.packageName}.testdocuments"

    private fun fixture(block: (Photo, LocalPhotoFiles, Uri, File) -> Unit) {
        val id = UUID.randomUUID().toString()
        val root = RuntimeGateway.root(context)
        val source = File(context.cacheDir, "test-documents/$id.png").apply { parentFile!!.mkdirs() }
        val input = File(root, "inbox/${id}__original.png").apply { parentFile!!.mkdirs() }
        val output = File(root, "output/$id.jpg").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(128 * 128) { i -> 0xff000000.toInt() or ((i * 1103515245 + 12345) and 0xffffff) }
        bitmap.setPixels(pixels, 0, 128, 0, 0, 128, 128)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 65, it) }
        bitmap.recycle()
        source.copyTo(input)
        assertTrue(input.length() > output.length())
        val photo = Photo(id, "original.png", input.absolutePath, input.length(), output.absolutePath, output.length(), "ready", null)
        val uri = DocumentsContract.buildDocumentUri(authority, source.name)
        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val name = "local-files-$id"
        val store = LocalPhotoFiles(context, name, setOf(authority))
        store.rememberSource(input, uri)
        try { block(photo, store, uri, source) }
        finally {
            input.delete(); output.delete(); source.delete()
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    private fun save(photo: Photo, store: LocalPhotoFiles): Uri = store.exportToFolder(photo, DocumentsContract.buildTreeDocumentUri(authority, "root"))
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Must retain the original") } catch (_: IllegalStateException) { }
    }

    @Test fun localPickerSupportsMoreThanThirtyAndCancellationIsEmpty() {
        val contract = LocalImagesContract()
        val intent = contract.createIntent(context, Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        val uris = (1..75).map { DocumentsContract.buildDocumentUri(authority, "$it.png") }
        val clip = ClipData.newRawUri("photos", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        assertEquals(uris, contract.parseResult(Activity.RESULT_OK, Intent().apply { clipData = clip }).uris)
        assertTrue(contract.parseResult(Activity.RESULT_CANCELED, intent).uris.isEmpty())
    }

    @Test fun actualImporterQueuesEveryPhotoAboveThirty() = runBlocking {
        RuntimeGateway.call(context, JSONObject().put("action", "pause").put("paused", true))
        val batch = UUID.randomUUID().toString()
        val sourceFiles = (1..35).map { index ->
            File(context.cacheDir, "test-documents/bulk-$batch-$index.png").apply {
                parentFile!!.mkdirs()
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val model = withContext(Dispatchers.Main) { PhotoModel(context.applicationContext as Application) }
        try {
            val job = withContext(Dispatchers.Main) {
                model.importPhotos(sourceFiles.map { DocumentsContract.buildDocumentUri(authority, it.name) }, local = true)
            }
            job.join()
            val imported = withContext(Dispatchers.Main) { model.state.photos.filter { it.name.contains("bulk-$batch-") } }
            assertEquals("No selected photos may be silently truncated", 35, imported.size)
            assertTrue(imported.all { File(it.input).isFile })
        } finally {
            sourceFiles.forEach { it.delete() }
            RuntimeGateway.stop()
        }
    }

    @Test fun sourceCannotBeDeletedUntilAnExternalCopyIsVerified() = fixture { photo, store, _, source ->
        assertFalse(store.canRequestDeletion(photo))
        rejects { store.deleteOriginal(photo) }
        assertTrue(source.exists())
        val exported = save(photo, store)
        try {
            assertTrue(store.canRequestDeletion(photo))
            assertTrue(LocalPhotoFiles(context, "local-files-${photo.id}", setOf(authority)).canRequestDeletion(photo))
            store.deleteOriginal(photo)
            assertFalse(source.exists())
            assertFalse(File(photo.input).exists())
            assertTrue(File(photo.output!!).exists())
            assertTrue(store.wasDeleted(photo))
            assertFalse(store.canRequestDeletion(photo))
            context.contentResolver.openInputStream(exported)!!.use { assertArrayEquals(File(photo.output!!).readBytes(), it.readBytes()) }
        } finally { DocumentsContract.deleteDocument(context.contentResolver, exported) }
    }

    @Test fun changedOrMissingExportNeverDeletesOriginal() = fixture { photo, store, _, source ->
        val exported = save(photo, store)
        try {
            context.contentResolver.openOutputStream(exported, "wt")!!.use { it.write(byteArrayOf(1, 2)) }
            rejects { store.deleteOriginal(photo) }
            assertTrue(source.exists())
            DocumentsContract.deleteDocument(context.contentResolver, exported)
            try { store.deleteOriginal(photo); fail("Missing copy must block deletion") } catch (_: Exception) { }
            assertTrue(source.exists())
        } finally { try { DocumentsContract.deleteDocument(context.contentResolver, exported) } catch (_: Exception) { } }
    }

    @Test fun changedOriginalAndChangedOutputNeverDeleteSource() = fixture { photo, store, _, source ->
        val exported = save(photo, store)
        try {
            val original = source.readBytes()
            source.writeBytes(original.copyOf().apply { this[100] = (this[100].toInt() xor 1).toByte() })
            rejects { store.deleteOriginal(photo) }
            assertTrue(source.exists())
            source.writeBytes(original)
            val output = File(photo.output!!)
            val bytes = output.readBytes().apply { this[lastIndex - 4] = (this[lastIndex - 4].toInt() xor 1).toByte() }
            output.writeBytes(bytes)
            rejects { store.deleteOriginal(photo) }
            assertTrue(source.exists())
        } finally { DocumentsContract.deleteDocument(context.contentResolver, exported) }
    }

    @Test fun cloudImportsAndNonSmallerOutputsCannotBeDeleted() = fixture { photo, store, _, source ->
        val exported = save(photo, store)
        try {
            assertFalse(store.canRequestDeletion(photo.copy(input = photo.input + "-unknown")))
            assertFalse(LocalPhotoFiles(context).canRequestDeletion(photo))
            assertFalse(store.canRequestDeletion(photo.copy(outputBytes = photo.inputBytes)))
            assertTrue(source.exists())
        } finally { DocumentsContract.deleteDocument(context.contentResolver, exported) }
    }

    @Suppress("DEPRECATION")
    @Test fun batchShareGrantsEveryFinishedCopyAndNoOriginals() = fixture { first, _, _, _ ->
        fixture { second, _, _, _ ->
            val intent = FinishedPhotosSharing.intent(context, listOf(first, second))
            assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(2, uris.size)
            assertEquals(2, intent.clipData!!.itemCount)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            uris.forEachIndexed { i, uri ->
                assertEquals(uri, intent.clipData!!.getItemAt(i).uri)
                assertEquals("${context.packageName}.files", uri.authority)
                context.contentResolver.openInputStream(uri)!!.use { assertTrue(it.readBytes().isNotEmpty()) }
            }
            assertTrue(File(first.input).exists())
            assertTrue(File(second.input).exists())
        }
    }
}
