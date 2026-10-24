package ke.co.pichalite

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalQueueUiTest {
    @Test fun fiveHundredLocalRecordsArePreparedOffMainAndRemainUsableWithoutDiskReads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = "queue-ui-${UUID.randomUUID()}"
        val photos = (1..500).map { Photo("$it", "$it.jpg", "${context.filesDir}/pichalite/inbox/$it.jpg", 100, null, 40, "ready", null) }
        val files = LocalPhotoFiles(context, preferences)
        withContext(Dispatchers.IO) {
            val edit = context.getSharedPreferences(preferences, 0).edit()
            for (photo in photos) {
                edit.putString("source:${java.io.File(photo.input).canonicalPath}", "{\"uri\":\"content://local/${photo.id}\",\"hash\":\"test\"}")
                edit.putString("replacement:${photo.id}", if (photo.id.toInt() % 2 == 0) "kept" else "awaiting")
            }
            assertTrue(edit.commit())
        }
        val ui = withContext(Dispatchers.IO) { LocalQueueUi.read(photos, files) }
        assertEquals(500, ui.photos.size)
        assertEquals(250, ui.waiting)
        assertEquals(250, ui.attention)
        assertEquals(250, ui.retained)
        assertEquals(250, ui.replacing)
        withContext(Dispatchers.Main) {
            val previous = android.os.StrictMode.getThreadPolicy()
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyDeath().build())
            try {
                repeat(500) { assertNotNull(ui.photos["${it + 1}"]?.status) }
                assertEquals(250, ui.waiting)
            } finally { android.os.StrictMode.setThreadPolicy(previous) }
            try { LocalQueueUi.read(photos, files); fail("Metadata must never be prepared during composition") }
            catch (_: IllegalStateException) { }
        }
        withContext(Dispatchers.IO) { context.deleteSharedPreferences(preferences); Unit }
    }
}
