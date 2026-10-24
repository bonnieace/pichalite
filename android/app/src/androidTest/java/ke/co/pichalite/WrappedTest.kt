package ke.co.pichalite

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WrappedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun history(): QueueState {
        val photos = (0 until 620).map { index ->
            Photo("p$index", "private-name-$index.jpg", "/missing/input-$index", 1000, "/missing/output-$index", 250, "ready", null)
        } + Photo("retained", "small.jpg", "/missing/small", 100, "/missing/larger", 200, "ready", null) +
            Photo("failed", "failed.jpg", "/missing/failed", 1000, null, null, "failed", "Failed") +
            Photo("pending", "queued.jpg", "/missing/queued", 1000, null, null, "queued", null)
        return QueueState(loaded = true, photos = photos, total = photos.size, completed = 621, pending = 1,
            savedBytes = 465_000, reclaimedBytes = 450_000)
    }

    @Test fun recapUsesFullHistoryAndSeparatesReclaimedSpaceFromCompression() {
        val stats = WrappedStats.from(history(), replaced = 600, retained = 1)
        assertEquals(623, stats.imported)
        assertEquals(621, stats.finished)
        assertEquals(620, stats.smaller)
        assertEquals(600, stats.replaced)
        assertEquals(1, stats.retained)
        assertEquals(1, stats.pending)
        assertEquals(1, stats.failed)
        assertEquals(620_100L, stats.sourceBytes)
        assertEquals(155_200L, stats.outputBytes)
        assertEquals(465_000L, stats.reducedBytes)
        assertEquals(450_000L, stats.reclaimedBytes)
        assertEquals(75, stats.averageReductionPercent)
        assertEquals(75, stats.bestReductionPercent)
        assertEquals(750L, stats.largestReduction)
        // No cached files exist. Stats do not depend on file existence or displayed pages.
        assertEquals(stats, WrappedStats.from(history().copy(photos = history().photos.reversed()), 600, 1))
        assertEquals("2.00 GB", wrappedBytes(2L * 1024 * 1024 * 1024))
    }

    @Test fun emptyHistoryHasNoFabricatedWinsAndCannotBeShared() {
        val stats = WrappedStats.from(QueueState(loaded = true))
        assertEquals(0, stats.averageReductionPercent)
        assertEquals(0, stats.bestReductionPercent)
        assertEquals(0L, stats.reclaimedBytes)
        try { WrappedSharing.intent(context, stats, null); fail("Empty recaps must not be shared") }
        catch (_: IllegalStateException) { /* Needs a real completion. */ }
    }

    @Suppress("DEPRECATION")
    @Test fun recapExportsFourReadablePortraitGraphicsWithReadOnlyGrants() {
        val stats = WrappedStats.from(history(), 600, 1)
        val intent = WrappedSharing.intent(context, stats, null)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("image/png", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(4, uris.size)
        assertEquals(4, intent.clipData!!.itemCount)
        val previews = File(context.getExternalFilesDir(null), "wrapped-preview").apply { mkdirs() }
        uris.forEachIndexed { index, uri ->
            assertEquals("${context.packageName}.files", uri.authority)
            assertEquals(uri, intent.clipData!!.getItemAt(index).uri)
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            assertEquals(1080, options.outWidth)
            assertEquals(1920, options.outHeight)
            File(previews, "wrapped-${index + 1}.png").writeBytes(bytes)
            assertFalse(WrappedArtwork.description(stats, index).contains("private-name"))
        }
        val single = WrappedSharing.intent(context, stats, 2)
        assertEquals(Intent.ACTION_SEND, single.action)
        assertNotNull(single.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertFalse(intent.getStringExtra(Intent.EXTRA_TEXT)!!.contains("private-name"))
    }
}
