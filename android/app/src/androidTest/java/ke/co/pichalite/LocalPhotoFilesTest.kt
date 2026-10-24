package ke.co.pichalite

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
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
        // Persistable grants are recorded even when the caller also owns this fixture provider.
        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
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

    @Test fun galleryCopyIsPublishedAndRetainedAfterLocalCleanup() = fixture { photo, store, _, source ->
        val exported = store.exportToGallery(photo)
        try {
            assertEquals(MediaStore.AUTHORITY, exported.authority)
            context.contentResolver.query(exported, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            assertTrue(store.canRequestDeletion(photo))
            store.deleteOriginal(photo)
            assertFalse(source.exists())
            context.contentResolver.openInputStream(exported)!!.use { assertArrayEquals(File(photo.output!!).readBytes(), it.readBytes()) }
        } finally { context.contentResolver.delete(exported, null, null) }
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

    private fun automaticExport(photo: Photo): Uri? = context.getSharedPreferences("local-files-${photo.id}", 0)
        .getString("export:${photo.id}", null)?.let { Uri.parse(JSONObject(it).getString("uri")) }

    @Test fun compressionFlowAutomaticallySavesAndReplacesOnceAcrossRestart() = fixture { photo, store, _, source ->
        try {
            assertNull(store.replaceWhenReady(photo))
            assertFalse(source.exists())
            assertFalse(File(photo.input).exists())
            assertTrue(store.wasDeleted(photo))
            val exported = automaticExport(photo)!!
            context.contentResolver.openInputStream(exported)!!.use { assertArrayEquals(File(photo.output!!).readBytes(), it.readBytes()) }
            val reopened = LocalPhotoFiles(context, "local-files-${photo.id}", setOf(authority))
            assertNull(reopened.replaceWhenReady(photo, foreground = true))
            assertEquals(exported, automaticExport(photo))
            assertTrue(reopened.replacementStatus(photo)!!.startsWith("Replaced"))
        } finally { automaticExport(photo)?.let { context.contentResolver.delete(it, null, null) } }
    }

    @Test fun autoReplacementSkipsPendingNonSmallerAndUnknownSources() = fixture { photo, store, _, source ->
        assertNull(store.replaceWhenReady(photo.copy(status = "processing")))
        assertNull(store.replaceWhenReady(photo.copy(outputBytes = photo.inputBytes)))
        assertTrue(source.exists())
        assertFalse(store.hasExport(photo))
        assertEquals("not_smaller", store.replacementState(photo))
        assertNull(LocalPhotoFiles(context).replaceWhenReady(photo, foreground = true))
        assertFalse(LocalPhotoFiles(context).hasExport(photo))
    }

    @Test fun declinedReplacementStaysPausedAcrossRestartAndReusesSavedCopyOnRetry() = fixture { photo, store, _, source ->
        val exported = store.exportToGallery(photo)
        try {
            store.declineReplacement(photo)
            val reopened = LocalPhotoFiles(context, "local-files-${photo.id}", setOf(authority))
            assertNull(reopened.replaceWhenReady(photo, foreground = true))
            assertTrue(source.exists())
            assertTrue(reopened.canRetryReplacement(photo))
            assertEquals("kept", reopened.replacementState(photo))
            reopened.replaceWhenReady(photo, foreground = true, retry = true)
            assertFalse(source.exists())
            assertTrue(reopened.wasDeleted(photo))
            assertEquals(exported, automaticExport(photo))
        } finally { context.contentResolver.delete(exported, null, null) }
    }

    @Test fun missingSavedCopyStopsAutomaticReplacementWithoutCreatingAnotherCopy() = fixture { photo, store, _, source ->
        val exported = store.exportToGallery(photo)
        context.contentResolver.delete(exported, null, null)
        assertNull(store.replaceWhenReady(photo, foreground = true))
        assertTrue(source.exists())
        assertEquals("error", store.replacementState(photo))
        assertTrue(store.canRetryReplacement(photo))
        assertEquals(exported, automaticExport(photo))
        // A later refresh must not repeatedly retry an error or silently create duplicates.
        store.replaceWhenReady(photo, foreground = true)
        assertTrue(source.exists())
        assertEquals(exported, automaticExport(photo))
    }

    @Test fun autoReplacementDoesNotDeleteChangedSourceAndResumesSavedExportAfterRestart() = fixture { photo, store, _, source ->
        val exported = store.exportToGallery(photo)
        val bytes = source.readBytes()
        try {
            source.appendBytes(byteArrayOf(42))
            store.replaceWhenReady(photo)
            assertTrue(source.exists())
            assertEquals("error", store.replacementState(photo))
            source.writeBytes(bytes)
            val reopened = LocalPhotoFiles(context, "local-files-${photo.id}", setOf(authority))
            reopened.replaceWhenReady(photo, retry = true)
            assertTrue(reopened.wasDeleted(photo))
            assertFalse(source.exists())
            assertEquals(exported, automaticExport(photo))
        } finally { context.contentResolver.delete(exported, null, null) }
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
    @Test fun localCompressionSavesAndReplacesInOneFlowWithoutExtraWorkingCopies() = runBlocking {
        RuntimeGateway.call(context, JSONObject().put("action", "pause").put("paused", false))
        val name = "auto-${UUID.randomUUID()}.png"
        val source = File(context.cacheDir, "test-documents/$name").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(256 * 256) { i -> 0xff000000.toInt() or ((i * 1103515245 + 12345) and 0xffffff) }
        bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 256)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = DocumentsContract.buildDocumentUri(authority, source.name)
        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        val input = File(RuntimeGateway.root(context), "inbox/${UUID.randomUUID()}__$name")
        source.copyTo(input)
        val store = LocalPhotoFiles(context, localAuthorities = setOf(authority))
        store.rememberSource(input, uri)
        val id = RuntimeGateway.call(context, JSONObject().put("action", "enqueue").put("input", input.absolutePath)).getString("id")
        var photo: Photo? = null
        for (attempt in 0 until 100) {
            val jobs = RuntimeGateway.call(context, JSONObject().put("action", "snapshot")).getJSONArray("jobs")
            photo = (0 until jobs.length()).map { Photo.fromJson(jobs.getJSONObject(it)) }.find { it.id == id && it.status == "ready" }
            if (photo != null) break
            kotlinx.coroutines.delay(100)
        }
        val ready = checkNotNull(photo) { "The native worker did not finish" }
        val expected = File(ready.output!!).readBytes()
        try {
            assertTrue(ready.outputBytes!! < ready.inputBytes)
            store.replaceWhenReady(ready)
            LocalReplacementRunner.release(context, ready, store)
            assertFalse(source.exists())
            assertFalse(input.exists())
            assertFalse(File(ready.output!!).exists())
            assertTrue(store.cacheReleased(ready))
            val snapshot = RuntimeGateway.call(context, JSONObject().put("action", "snapshot"))
            val jobs = snapshot.getJSONArray("jobs")
            val saved = (0 until jobs.length()).map { Photo.fromJson(jobs.getJSONObject(it)) }.first { it.id == id }
            assertEquals("ready", saved.status)
            assertNotNull(saved.savedUri)
            context.contentResolver.openInputStream(Uri.parse(saved.savedUri))!!.use { assertArrayEquals(expected, it.readBytes()) }
            val extra = store.exportToFolder(saved, DocumentsContract.buildTreeDocumentUri(authority, "root"))
            try {
                context.contentResolver.openInputStream(extra)!!.use { assertArrayEquals(expected, it.readBytes()) }
                assertEquals(Uri.parse(saved.savedUri), store.savedCopy(saved))
            } finally { DocumentsContract.deleteDocument(context.contentResolver, extra) }
            val share = FinishedPhotosSharing.intent(context, listOf(saved))
            assertEquals(Intent.ACTION_SEND, share.action)
            val total = snapshot.getLong("reclaimed_bytes")
            LocalReplacementRunner.release(context, saved, store)
            assertEquals(total, RuntimeGateway.call(context, JSONObject().put("action", "snapshot")).getLong("reclaimed_bytes"))
        } finally {
            store.savedCopy(ready)?.let { context.contentResolver.delete(it, null, null) }
            source.delete(); input.delete(); File(ready.output!!).delete()
            RuntimeGateway.stop()
        }
    }


}
