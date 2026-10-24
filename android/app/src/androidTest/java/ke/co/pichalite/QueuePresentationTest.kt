package ke.co.pichalite

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QueuePresentationTest {
    @get:Rule val compose = createComposeRule()
    private fun photo(i: Int, status: String = "ready") = Photo("$i", "photo-$i.jpg", "/unused/$i.jpg", 100, "/unused/result-$i.jpg", 40, status, null)
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "ui").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }

    @Test fun largeStreamingBatchKeepsStablePanelAndReadyRemainsAccessible() {
        var state by mutableStateOf(QueueState(loaded = true, pending = 1, photos = listOf(photo(1, "processing"))))
        var importing by mutableStateOf(true)
        var readyTab by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize().background(Paper).padding(24.dp)) {
                    photoQueue(state, importing, state.photos.size, 620, readyTab, 0, {}) {
                        Text(it.name, Modifier.height(48.dp).testTag("photo_row"))
                    }
                }
            }
        }
        val before = compose.onNodeWithTag("queue_activity").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state = state.copy(photos = (1..620).map { photo(it) }, pending = 1) }
        compose.onAllNodesWithTag("photo_row").assertCountEquals(0)
        assertEquals(before, compose.onNodeWithTag("queue_activity").fetchSemanticsNode().boundsInRoot)
        screenshot("queue-streaming")
        // Import can momentarily have no pending native jobs between arrivals.
        compose.runOnIdle { state = state.copy(pending = 0) }
        compose.onNodeWithTag("queue_activity").assertIsDisplayed()
        compose.runOnIdle { readyTab = true }
        compose.onNodeWithTag("queue_activity").assertDoesNotExist()
        compose.onNodeWithText("photo-1.jpg").assertIsDisplayed()
        // End the batch and reveal the paginated Studio list.
        compose.runOnIdle { readyTab = false; importing = false }
        compose.onNodeWithText("Page 1 of 13").assertIsDisplayed()
    }

    @Test fun pagesCoverEntireBatchWithoutTruncationAndClampWhenFilterShrinks() {
        var state by mutableStateOf(QueueState(loaded = true, photos = (1..101).map { photo(it) }))
        var page by mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize().padding(24.dp).testTag("list")) {
                    photoQueue(state, false, 0, 0, true, page, { page = it }) {
                        Text(it.name, Modifier.height(32.dp))
                    }
                }
            }
        }
        compose.onNodeWithText("Photos 1–50 of 101").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous page").assertIsNotEnabled()
        compose.onNodeWithTag("list").performScrollToNode(hasText("photo-50.jpg"))
        compose.onNodeWithText("photo-51.jpg").assertDoesNotExist()
        compose.onNodeWithTag("list").performScrollToNode(hasContentDescription("Next page"))
        compose.onNodeWithContentDescription("Next page").performClick()
        compose.onNodeWithText("Photos 51–100 of 101").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next page").performClick()
        compose.onNodeWithText("photo-101.jpg").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next page").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(photos = listOf(photo(1))) }
        compose.onNodeWithText("Page 1 of 1").assertIsDisplayed()
    }

    @Test fun pausedQueueRevealsPhotosInsteadOfIndefiniteAnimation() {
        compose.setContent { MaterialTheme { LazyColumn {
            photoQueue(QueueState(loaded = true, paused = true, pending = 1, photos = listOf(photo(1, "waiting"))), false, 0, 0, false, 0, {}) { Text(it.name) }
        } } }
        compose.onNodeWithTag("queue_activity").assertDoesNotExist()
        compose.onNodeWithText("photo-1.jpg").assertIsDisplayed()
    }

    @Test fun completionDoesNotMoveThumbnailAndTappingOpensNavigablePreview() {
        var current by mutableStateOf(photo(1, "waiting"))
        var preview by mutableStateOf(false)
        var index by mutableIntStateOf(0)
        compose.setContent { MaterialTheme {
            PhotoRow(current, false, {}, {}, false, {}, true, null, null, open = { preview = true })
            if (preview) PhotoPreviewDialog(photo(index + 1), index, 2, { preview = false }, { index-- }, { index++ }, {})
        } }
        val before = compose.onNodeWithContentDescription(current.name).fetchSemanticsNode().boundsInRoot.left
        compose.runOnIdle { current = photo(1) }
        assertEquals(before, compose.onNodeWithContentDescription(current.name).fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithContentDescription(current.name).performClick()
        compose.onNodeWithTag("photo_preview").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous photo").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next photo").performClick()
        compose.onNodeWithText("2 of 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next photo").assertIsNotEnabled()
        screenshot("photo-preview")
        compose.onNodeWithContentDescription("Close preview").performClick()
        compose.onNodeWithTag("photo_preview").assertDoesNotExist()
    }

    @Test fun successfulImportIsInformativeAndDismissible() {
        var visible by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (visible) PhotoNotice("620 photos imported.", false) { visible = false }
        } }
        compose.onNodeWithText("620 photos imported.").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Information")).assertExists()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onNodeWithText("620 photos imported.").assertDoesNotExist()
    }

    @Test fun studioAndWrappedBothUseGigabytesForLargeTotals() {
        val gb = 1024L * 1024 * 1024
        assertEquals("0 B", bytes(0))
        assertEquals("1.0 MB", bytes(1024L * 1024))
        assertEquals("1.00 GB", bytes(gb))
        assertEquals("2.50 GB", bytes(gb * 5 / 2))
        assertEquals(wrappedBytes(gb * 10), bytes(gb * 10))
    }

    @Test fun simultaneousStagesUseOneProcessingAnimationAndSwitchAsWorkFinishes() {
        var importing by mutableStateOf(true)
        var pending by mutableIntStateOf(3)
        compose.setContent { MaterialTheme { QueueActivity(importing, 8, 100, pending) } }
        compose.onAllNodesWithTag("queue_animation").assertCountEquals(1)
        compose.onNodeWithText("Importing & compressing").assertIsDisplayed()
        compose.onNodeWithText("8 of 100 imported · 3 remaining").assertIsDisplayed()
        assertEquals(QueueAnimation.PROCESSING, queueAnimation(true, 3, false))
        screenshot("combined-processing")
        compose.runOnIdle { pending = 0 }
        compose.mainClock.advanceTimeBy(1700)
        compose.onAllNodesWithTag("queue_animation").assertCountEquals(1)
        compose.onNodeWithText("Bringing your photos in").assertIsDisplayed()
        assertEquals(QueueAnimation.IMPORT, queueAnimation(true, 0, false))
        assertEquals(QueueAnimation.IMPORT, queueAnimation(true, 3, true))
        compose.runOnIdle { importing = false; pending = 3 }
        compose.onAllNodesWithTag("queue_animation").assertCountEquals(1)
        compose.onNodeWithText("Making room for more").assertIsDisplayed()
        assertEquals(QueueAnimation.COMPRESSION, queueAnimation(false, 3, false))
        screenshot("compressing")
    }

    @Test fun fiveHundredProgressUpdatesKeepThePanelStillAndIgnoreShortQueueGaps() {
        compose.mainClock.autoAdvance = false
        var imported by mutableIntStateOf(0)
        var pending by mutableIntStateOf(2)
        compose.setContent { MaterialTheme { QueueActivity(true, imported, 500, pending) } }
        compose.mainClock.advanceTimeBy(32)
        val before = compose.onNodeWithTag("queue_activity").fetchSemanticsNode().boundsInRoot
        for (index in 1..500) {
            compose.runOnUiThread { imported = index; pending = if (index % 2 == 0) 0 else 2 }
            compose.mainClock.advanceTimeBy(16)
        }
        compose.onNodeWithText("Importing & compressing").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("queue_activity").fetchSemanticsNode().boundsInRoot)
        compose.onAllNodesWithTag("queue_animation").assertCountEquals(1)
        compose.mainClock.advanceTimeBy(1700)
        compose.onNodeWithText("Bringing your photos in").assertIsDisplayed()
        screenshot("500-photo-stable-panel")
    }

    @Test fun bundledLottiesParseAndDrawOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (resource in listOf(R.raw.photo_upload, R.raw.photo_compression, R.raw.photo_processing)) {
            val result = LottieCompositionFactory.fromRawResSync(context, resource)
            assertNull(result.exception)
            val composition = checkNotNull(result.value)
            assertTrue(composition.duration > 0)
            val drawable = LottieDrawable().apply { setComposition(composition); progress = 0.5f; setBounds(0, 0, 240, 240) }
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(240 * 240)
            bitmap.getPixels(pixels, 0, 240, 0, 0, 240, 240)
            assertTrue("The animation must contain visible artwork", pixels.count { (it ushr 24) > 0 } > 1000)
            bitmap.recycle()
        }
    }
}
