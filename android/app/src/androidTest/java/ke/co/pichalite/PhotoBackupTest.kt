package ke.co.pichalite

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhotoBackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withPhoto(block: (Photo) -> Unit) {
        val id = UUID.randomUUID().toString()
        val root = RuntimeGateway.root(context)
        val input = File(root, "inbox/${id}__original.png").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(120) { 42 }) }
        val output = File(root, "output/$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(40) { 17 }) }
        val photo = Photo(id, "original.png", input.absolutePath, input.length(), output.absolutePath, output.length(), "ready", null)
        try { block(photo) } finally { input.delete(); output.delete() }
    }

    @Test fun handoffDoesNotClaimBackupAndReviewSurvivesRecreation() = withPhoto { photo ->
        val name = "backup-test-${photo.id}"
        val store = PhotoBackupStore(context, name)
        try {
            assertEquals(BackupStage.NOT_SENT, store.stage(photo))
            store.recordHandoff(photo)
            // Cancellation/return from a launched share stays unverified.
            assertEquals(BackupStage.SENT, PhotoBackupStore(context, name).stage(photo))
            store.confirmBackup(photo)
            assertEquals(BackupStage.USER_VERIFIED, PhotoBackupStore(context, name).stage(photo))
            store.recordHandoff(photo)
            assertEquals(BackupStage.USER_VERIFIED, store.stage(photo))
            assertArrayEquals(ByteArray(120) { 42 }, File(photo.input).readBytes())
        } finally { context.getSharedPreferences(name, 0).edit().clear().commit() }
    }

    @Test fun userCannotConfirmBeforeHandoff() = withPhoto { photo ->
        val store = PhotoBackupStore(context, "backup-test-${photo.id}")
        try { store.confirmBackup(photo); fail("No handoff has occurred") }
        catch (_: IllegalStateException) { assertEquals(BackupStage.NOT_SENT, store.stage(photo)) }
    }

    @Test fun changedOrMissingOutputInvalidatesOldReview() = withPhoto { photo ->
        val name = "backup-test-${photo.id}"
        val store = PhotoBackupStore(context, name)
        try {
            store.recordHandoff(photo); store.confirmBackup(photo)
            val output = File(photo.output!!)
            output.appendBytes(byteArrayOf(1))
            assertEquals(BackupStage.NOT_SENT, store.stage(photo))
            val newPhoto = photo.copy(outputBytes = output.length())
            assertEquals(BackupStage.NOT_SENT, store.stage(newPhoto))
            store.recordHandoff(newPhoto); store.confirmBackup(newPhoto)
            assertTrue(output.setLastModified(output.lastModified() + 10_000))
            assertEquals(BackupStage.NOT_SENT, store.stage(newPhoto))
            output.delete()
            assertEquals(BackupStage.NOT_SENT, store.stage(newPhoto))
            assertTrue(File(photo.input).isFile)
        } finally { context.getSharedPreferences(name, 0).edit().clear().commit() }
    }

    @Suppress("DEPRECATION")
    @Test fun googlePhotosHandoffGrantsReadAccessOnlyToFinishedCopy() = withPhoto { photo ->
        val intent = GooglePhotosSharing.intent(context, photo)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertEquals(GooglePhotosSharing.PACKAGE, intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${context.packageName}.files", uri.authority)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        context.contentResolver.openInputStream(uri)!!.use {
            assertArrayEquals(File(photo.output!!).readBytes(), it.readBytes())
        }
        assertTrue(File(photo.input).isFile)
    }

    @Test fun unfinishedAndPrivateInputFilesCannotBeSentAsFinishedCopies() = withPhoto { photo ->
        for (invalid in listOf(photo.copy(status = "pending"), photo.copy(output = photo.input))) {
            try { GooglePhotosSharing.intent(context, invalid); fail("Must reject an unfinished or non-output photo") }
            catch (_: IllegalStateException) { /* No external app was launched. */ }
        }
    }
}
