package ke.co.pichalite

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Handoff and user review, never an assertion from Google that an upload finished. */
enum class BackupStage { NOT_SENT, SENT, USER_VERIFIED }

class PhotoBackupStore(context: Context, preferenceName: String = "photo_backup_review") {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    private fun generation(photo: Photo): String? {
        if (photo.status != "ready") return null
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

    fun intent(context: Context, photo: Photo): Intent {
        check(photo.status == "ready") { "Wait until this photo finishes processing" }
        val output = File(checkNotNull(photo.output) { "No finished photo is available" })
        val allowed = File(RuntimeGateway.root(context), "output").canonicalFile
        check(output.canonicalFile.parentFile == allowed && output.isFile && output.length() > 0) {
            "The finished photo is no longer available"
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", output)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            setPackage(PACKAGE)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "PichaLite lighter copy", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
