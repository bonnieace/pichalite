package ke.co.pichalite

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class LocalImports(val uris: List<Uri>, val flags: Int)

/** Separate Files entry point: local documents, multiple selection and writable grants. */
class LocalImagesContract : ActivityResultContract<Unit, LocalImports>() {
    override fun createIntent(context: Context, input: Unit) = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp"))
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): LocalImports {
        if (resultCode != android.app.Activity.RESULT_OK || intent == null) return LocalImports(emptyList(), 0)
        val uris = intent.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
            ?: listOfNotNull(intent.data)
        return LocalImports(uris.distinct(), intent.flags)
    }
}

/** Source identity and verified external exports. Cloud-picker imports never enter this store. */
class LocalPhotoFiles(
    private val context: Context,
    preferenceName: String = "local_photo_files",
    private val localAuthorities: Set<String> = LOCAL_AUTHORITIES,
) {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    fun retainGrants(imports: LocalImports) {
        val flags = imports.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        for (uri in imports.uris) {
            try { resolver.takePersistableUriPermission(uri, flags) }
            catch (_: SecurityException) {
                try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { /* A provider may offer session-only access. */ }
            }
        }
    }

    fun rememberSource(input: File, uri: Uri) {
        check(input.canonicalFile.parentFile == File(RuntimeGateway.root(context), "inbox").canonicalFile)
        val source = JSONObject().put("uri", uri.toString()).put("hash", digest(input))
        check(prefs.edit().putString("source:${input.absolutePath}", source.toString()).commit()) {
            "Could not retain the original file reference"
        }
    }

    private fun source(photo: Photo) = prefs.getString("source:${photo.input}", null)?.let(::JSONObject)
    fun wasDeleted(photo: Photo) = prefs.getBoolean("deleted:${photo.input}", false)
    fun hasLocalSource(photo: Photo) = source(photo) != null
    fun hasExport(photo: Photo) = prefs.contains("export:${photo.id}")

    private fun output(photo: Photo): File {
        check(photo.status == "ready") { "Wait for compression to finish" }
        val file = File(checkNotNull(photo.output) { "No finished copy is available" }).canonicalFile
        check(file.parentFile == File(RuntimeGateway.root(context), "output").canonicalFile && file.isFile && file.length() == photo.outputBytes && file.length() > 0) {
            "The finished copy is missing or changed"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "The finished copy is not a readable image" }
        return file
    }

    fun exportToFolder(photo: Photo, tree: Uri): Uri {
        try { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (_: SecurityException) { /* Session-only saves are still checked before any cleanup. */ }
        val file = output(photo)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = photo.name.substringBeforeLast('.').take(100) + "-pichalite.jpg"
        val destination = checkNotNull(DocumentsContract.createDocument(resolver, parent, "image/jpeg", name)) {
            "This folder does not allow saving files"
        }
        try { writeAndVerify(photo, file, destination); return destination }
        catch (e: Exception) {
            try { DocumentsContract.deleteDocument(resolver, destination) } catch (_: Exception) { }
            throw e
        }
    }

    fun exportToGallery(photo: Photo): Uri {
        check(Build.VERSION.SDK_INT >= 29) { "Use Save to folder on this Android version" }
        val file = output(photo)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.name.substringBeforeLast('.').take(100) + "-pichalite.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PichaLite")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val destination = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "Could not create a Gallery copy" }
        try {
            copyAndVerify(file, destination)
            check(resolver.update(destination, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) { "Could not publish the Gallery copy" }
            recordExport(photo, file, destination)
            return destination
        } catch (e: Exception) {
            try { resolver.delete(destination, null, null) } catch (_: Exception) { }
            throw e
        }
    }

    private fun writeAndVerify(photo: Photo, file: File, uri: Uri) {
        copyAndVerify(file, uri)
        recordExport(photo, file, uri)
    }

    private fun copyAndVerify(file: File, uri: Uri) {
        checkNotNull(resolver.openOutputStream(uri, "w")) { "Could not open the destination" }.use { out ->
            file.inputStream().use { it.copyTo(out) }
            out.flush()
        }
        check(digest(uri) == digest(file)) { "The saved copy could not be verified; the original is untouched" }
    }

    private fun recordExport(photo: Photo, file: File, uri: Uri) {
        val record = JSONObject().put("uri", uri.toString()).put("hash", digest(file))
        check(prefs.edit().putString("export:${photo.id}", record.toString()).commit()) { "Could not record the saved copy" }
    }

    /** Cheap UI eligibility only. All bytes and permissions are checked again before deletion. */
    fun canRequestDeletion(photo: Photo): Boolean {
        if (wasDeleted(photo) || photo.status != "ready" || !hasExport(photo)) return false
        if ((photo.outputBytes ?: Long.MAX_VALUE) >= photo.inputBytes) return false
        val uri = source(photo)?.optString("uri")?.let(Uri::parse) ?: return false
        val exported = prefs.getString("export:${photo.id}", null)?.let(::JSONObject) ?: return false
        val destination = Uri.parse(exported.getString("uri"))
        return uri.authority in localAuthorities && (destination.authority in localAuthorities || destination.authority == MediaStore.AUTHORITY)
    }

    fun deleteOriginal(photo: Photo) {
        check(canRequestDeletion(photo)) { "Save a smaller copy first; only local-file imports support cleanup" }
        val file = output(photo)
        val source = checkNotNull(source(photo))
        val uri = Uri.parse(source.getString("uri"))
        check(uri.authority in localAuthorities && DocumentsContract.isDocumentUri(context, uri)) { "This source does not support local cleanup" }
        check(context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            "The file provider did not grant deletion access. The original is untouched."
        }
        val flags = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0
        check(flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0) { "This file provider does not allow deletion" }
        val exported = JSONObject(checkNotNull(prefs.getString("export:${photo.id}", null)))
        val exportedUri = Uri.parse(exported.getString("uri"))
        check(exportedUri != uri && exportedUri.authority != "${context.packageName}.files") { "Save the smaller copy outside PichaLite first" }
        val expected = exported.getString("hash")
        check(digest(file) == expected && digest(exportedUri) == expected) { "The saved copy is missing or changed. The original is untouched."
        }
        check(digest(uri) == source.getString("hash")) { "The original changed since import. Import it again before cleanup."
        }
        check(DocumentsContract.deleteDocument(resolver, uri)) { "The original could not be deleted" }
        // Mark every import of this exact URI to avoid asking to delete it twice.
        val edit = prefs.edit()
        for ((key, value) in prefs.all) {
            if (key.startsWith("source:") && value is String && JSONObject(value).optString("uri") == uri.toString()) {
                val input = key.removePrefix("source:")
                edit.putBoolean("deleted:$input", true)
            }
        }
        check(edit.commit()) { "Original removed, but its cleanup record could not be saved" }
        // This import's private full-size copy is no longer needed after external save + deletion.
        // Other imports might still be pending, so only clear this completed photo's input.
        val input = File(photo.input).canonicalFile
        if (input.parentFile == File(RuntimeGateway.root(context), "inbox").canonicalFile) input.delete()
    }

    private fun digest(uri: Uri) = checkNotNull(resolver.openInputStream(uri)) { "Could not read a file for verification" }.use(::digest)

    companion object {
        private val LOCAL_AUTHORITIES = setOf("com.android.externalstorage.documents", "com.android.providers.media.documents", "com.android.providers.downloads.documents")
        fun digest(file: File) = file.inputStream().use(::digest)
        private fun digest(input: InputStream): String {
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
