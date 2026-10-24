package ke.co.pichalite

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.ParcelFileDescriptor
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
        if (Build.VERSION.SDK_INT < 29) check(context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            "Allow storage access to save the smaller image. The original is untouched."
        }
        val file = output(photo)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.name.substringBeforeLast('.').take(100) + "-pichalite.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PichaLite")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val destination = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "Could not create a Gallery copy" }
        try {
            copyAndVerify(file, destination)
            if (Build.VERSION.SDK_INT >= 29) check(resolver.update(destination, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) { "Could not publish the Gallery copy" }
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
        if (uri.authority in localAuthorities || uri.authority == MediaStore.AUTHORITY) {
            val descriptor = checkNotNull(resolver.openFileDescriptor(uri, "w")) { "Could not open the destination" }
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
                file.inputStream().use { it.copyTo(out) }
                out.flush()
                out.fd.sync()
            }
        } else {
            checkNotNull(resolver.openOutputStream(uri, "w")) { "Could not open the destination" }.use { out ->
                file.inputStream().use { it.copyTo(out) }
                out.flush()
            }
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

    /** Verifies the saved copy and unchanged original again immediately before removal. */
    private fun verifiedOriginal(photo: Photo): Uri {
        check(canRequestDeletion(photo)) { "A verified smaller local copy is required" }
        val file = output(photo)
        val source = checkNotNull(source(photo))
        val uri = Uri.parse(source.getString("uri"))
        check(uri.authority in localAuthorities && DocumentsContract.isDocumentUri(context, uri)) { "This source does not support local replacement" }
        val exported = JSONObject(checkNotNull(prefs.getString("export:${photo.id}", null)))
        val exportedUri = Uri.parse(exported.getString("uri"))
        check(exportedUri != uri && exportedUri.authority != "${context.packageName}.files") { "Save the smaller copy outside PichaLite first" }
        val expected = exported.getString("hash")
        check(digest(file) == expected && digest(exportedUri) == expected) { "The saved copy is missing or changed. The original is untouched."
        }
        check(digest(uri) == source.getString("hash")) { "The original changed since import. Import it again before cleanup."
        }
        return uri
    }

    private fun mediaUri(uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        // getMediaUri resolves the exact granted document; never guess a path or search by name.
        return try { MediaStore.getMediaUri(context, uri) } catch (_: Exception) { null }
    }

    fun deleteOriginal(photo: Photo) {
        val uri = verifiedOriginal(photo)
        val writable = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        val flags = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0
        if (writable && flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0) {
            try {
                check(DocumentsContract.deleteDocument(resolver, uri)) { "The original could not be deleted" }
            } catch (e: SecurityException) {
                val media = mediaUri(uri) ?: throw e
                check(resolver.delete(media, null, null) == 1) { "The original could not be deleted" }
            }
        } else {
            val media = checkNotNull(mediaUri(uri)) { "This provider does not allow replacement. The smaller copy is saved; the original is kept." }
            check(resolver.delete(media, null, null) == 1) { "The original could not be deleted" }
        }
        markDeleted(photo, uri)
    }

    private fun markDeleted(photo: Photo, uri: Uri) {
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

    fun replacementState(photo: Photo): String = prefs.getString("replacement:${photo.id}", "") ?: ""
    fun replacementStatus(photo: Photo): String? {
        if (!hasLocalSource(photo)) return null
        if (wasDeleted(photo)) return "Replaced · smaller image saved"
        return when (replacementState(photo)) {
            "awaiting" -> "Smaller image saved · allow replacement in Android’s prompt"
            "kept" -> "Smaller image saved · original kept (permission declined)"
            "not_smaller" -> "Already small · original kept"
            "error" -> prefs.getString("replacement-error:${photo.id}", "Could not replace this image")
            else -> if (photo.status == "ready") "Saving smaller image and replacing original…" else "Will save and replace after compression"
        }
    }
    fun canRetryReplacement(photo: Photo) = hasLocalSource(photo) && !wasDeleted(photo) &&
        photo.status == "ready" && replacementState(photo) in setOf("error", "kept")

    private fun setReplacementState(photo: Photo, state: String, error: String? = null) {
        check(prefs.edit().putString("replacement:${photo.id}", state)
            .putString("replacement-error:${photo.id}", error).commit()) { "Could not save replacement progress" }
    }
    fun batchReplacementPermission(photos: List<Photo>): PendingIntent = synchronized(replacementLock) {
        check(Build.VERSION.SDK_INT >= 30)
        val uris = photos.map { checkNotNull(mediaUri(verifiedOriginal(it))) { "Could not resolve local replacement access" } }.distinct()
        MediaStore.createWriteRequest(resolver, uris)
    }

    fun declineReplacement(photo: Photo) = synchronized(replacementLock) { setReplacementState(photo, "kept") }

    /** One resumable save/verify/delete flow shared by the service and foreground screen. */
    fun replaceWhenReady(photo: Photo, foreground: Boolean = false, retry: Boolean = false): PendingIntent? = synchronized(replacementLock) {
        if (!hasLocalSource(photo) || wasDeleted(photo) || photo.status != "ready") return@synchronized null
        val stage = replacementState(photo)
        if (!retry && stage in setOf("kept", "error", "not_smaller")) return@synchronized null
        if (stage == "awaiting" && !foreground) return@synchronized null
        if ((photo.outputBytes ?: Long.MAX_VALUE) >= photo.inputBytes) {
            setReplacementState(photo, "not_smaller")
            return@synchronized null
        }
        try {
            // A persisted export prevents duplicate Gallery copies after a restart or refusal.
            if (!hasExport(photo)) exportToGallery(photo)
            try {
                deleteOriginal(photo)
            } catch (e: SecurityException) {
                val uri = verifiedOriginal(photo)
                val request = if (Build.VERSION.SDK_INT >= 30) {
                    MediaStore.createWriteRequest(resolver, listOf(checkNotNull(mediaUri(uri)) {
                        "Android could not grant replacement access. The original is kept."
                    }))
                } else if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                    e.userAction.actionIntent
                } else throw e
                setReplacementState(photo, "awaiting")
                return@synchronized if (foreground) request else null
            }
        } catch (e: Exception) {
            setReplacementState(photo, "error", "Original kept · " +
                (e.message ?: "Could not complete replacement"))
        }
        null
    }

    private fun digest(uri: Uri) = checkNotNull(resolver.openInputStream(uri)) { "Could not read a file for verification" }.use(::digest)

    companion object {
        private val replacementLock = Any()
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
