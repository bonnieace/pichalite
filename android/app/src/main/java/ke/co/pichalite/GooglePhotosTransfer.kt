package ke.co.pichalite

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

/** Handoff and user review, never an assertion from Google that an upload finished. */
enum class BackupStage { NOT_SENT, SENT, USER_VERIFIED }

class PhotoBackupStore(private val context: Context, preferenceName: String = "photo_backup_review") {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    private fun generation(photo: Photo): String? {
        if (photo.status != "ready") return null
        if (photo.savedUri != null) {
            val files = LocalPhotoFiles(context)
            if (files.savedCopy(photo)?.toString() != photo.savedUri) return null
            try { context.contentResolver.openInputStream(android.net.Uri.parse(photo.savedUri))?.use { if (it.read() < 0) return null } ?: return null }
            catch (_: Exception) { return null }
            return "${photo.savedUri}|${photo.outputBytes}|${files.savedHash(photo)}"
        }
        val output = photo.output?.let(::File) ?: return null
        if (!output.isFile || output.length() <= 0 || output.length() != photo.outputBytes) return null
        // A reprocessed or replaced output must be reviewed again. This is bookkeeping,
        // not an automatic permission to delete either the source or the output.
        return "${output.canonicalPath}|${output.length()}|${output.lastModified()}"
    }

    fun stage(photo: Photo): BackupStage {
        val generation = generation(photo) ?: return BackupStage.NOT_SENT
        if (preferences.getString("${photo.id}.output", null) != generation) return BackupStage.NOT_SENT
        return when (preferences.getString("${photo.id}.stage", null)) {
            "sent" -> BackupStage.SENT
            "user_verified" -> BackupStage.USER_VERIFIED
            else -> BackupStage.NOT_SENT
        }
    }

    fun recordHandoff(photo: Photo) {
        val generation = checkNotNull(generation(photo)) { "This finished photo is no longer available" }
        // Sending another copy does not invalidate a review of the same output.
        val value = if (stage(photo) == BackupStage.USER_VERIFIED) "user_verified" else "sent"
        check(preferences.edit().putString("${photo.id}.output", generation)
            .putString("${photo.id}.stage", value).commit()) { "Could not save the backup review" }
    }

    fun confirmBackup(photo: Photo) {
        check(stage(photo) != BackupStage.NOT_SENT) { "Send the lighter copy to Google Photos first" }
        check(preferences.edit().putString("${photo.id}.stage", "user_verified").commit()) {
            "Could not save the backup review"
        }
    }
}

object GooglePhotosSharing {
    const val PACKAGE = "com.google.android.apps.photos"

    fun uri(context: Context, photo: Photo): android.net.Uri {
        check(photo.status == "ready") { "Wait until this photo finishes processing" }
        if (photo.savedUri != null) {
            val uri = android.net.Uri.parse(photo.savedUri)
            check(uri.scheme == "content" && LocalPhotoFiles(context).savedCopy(photo) == uri) { "The saved result is no longer available" }
            context.contentResolver.openInputStream(uri)?.use { check(it.read() >= 0) { "The saved result is empty" } }
                ?: error("The saved result is no longer available")
            return uri
        }
        val output = File(checkNotNull(photo.output) { "No finished photo is available" })
        val allowed = File(RuntimeGateway.root(context), "output").canonicalFile
        check(output.canonicalFile.parentFile == allowed && output.isFile && output.length() > 0) {
            "The finished photo is no longer available"
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", output)
    }

    fun intent(context: Context, photo: Photo): Intent {
        val uri = uri(context, photo)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            setPackage(PACKAGE)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "PichaLite lighter copy", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/** Explicit URI grants for every finished item, including multi-photo handoffs. */
object FinishedPhotosSharing {
    /** Promote Photos inside the system chooser, retaining every other share target. */
    fun chooser(context: Context, photos: List<Photo>): Intent {
        val share = intent(context, photos)
        val preferred = Intent(share).setPackage(GooglePhotosSharing.PACKAGE)
        @Suppress("DEPRECATION")
        val activity = context.packageManager.queryIntentActivities(preferred, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
        val component = activity?.activityInfo?.let { ComponentName(it.packageName, it.name) }
        return chooser(share, component)
    }

    internal fun chooser(share: Intent, preferred: ComponentName?): Intent = Intent.createChooser(share, "Share lighter photos").apply {
        if (preferred != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(Intent(share).setComponent(preferred)))
    }

    fun intent(context: Context, photos: List<Photo>, targetPackage: String? = null): Intent {
        check(photos.isNotEmpty()) { "Select at least one finished photo" }
        val uris = ArrayList(photos.distinctBy { it.id }.map {
            // Reuse the finished-output boundary checks; never expose the private inbox.
            @Suppress("DEPRECATION")
            GooglePhotosSharing.intent(context, it).getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        })
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            if (targetPackage != null) setPackage(targetPackage)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(context.contentResolver, "PichaLite lighter copies", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
