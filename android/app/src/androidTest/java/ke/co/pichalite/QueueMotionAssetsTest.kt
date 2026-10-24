package ke.co.pichalite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueMotionAssetsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun pixels(drawable: LottieDrawable, progress: Float): IntArray {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        drawable.progress = progress
        drawable.draw(Canvas(bitmap))
        val result = IntArray(240 * 240)
        bitmap.getPixels(result, 0, 240, 0, 0, 240, 240)
        bitmap.recycle()
        return result
    }

    @Test fun cogsKeepMovingAfterTheOriginalOneSecondStop() {
        val composition = checkNotNull(LottieCompositionFactory.fromRawResSync(context, R.raw.photo_processing).value)
        val drawable = LottieDrawable().apply { callback = ImageView(context); setComposition(composition); setBounds(0, 0, 240, 240) }
        // Both samples lie well beyond the original asset's last rotation keyframe.
        assertFalse(pixels(drawable, 0.55f).contentEquals(pixels(drawable, 0.59f)))
        assertFalse(pixels(drawable, 0.82f).contentEquals(pixels(drawable, 0.86f)))
        val json = context.resources.openRawResource(R.raw.photo_processing).bufferedReader().use { JSONObject(it.readText()) }
        val layers = json.getJSONArray("layers")
        for (index in 0 until layers.length()) {
            val rotation = layers.getJSONObject(index).getJSONObject("ks").getJSONObject("r")
            assertFalse("Android playback must not depend on After Effects expressions", rotation.has("x"))
            val keys = rotation.getJSONArray("k")
            assertEquals(json.getDouble("op"), keys.getJSONObject(keys.length() - 1).getDouble("t"), 0.001)
        }
    }

    @Test fun approvedFactoryFramesAreBundledAndStayWithinTheDecodedMemoryBudget() {
        val composition = checkNotNull(LottieCompositionFactory.fromRawResSync(context, R.raw.photo_compression).value)
        assertEquals(56, composition.images.size)
        val bytes = composition.images.values.sumOf { it.width.toLong() * it.height * 4 }
        assertTrue("Do not restore the original 189 MiB image sequence", bytes <= 24L * 1024 * 1024)
        val drawable = LottieDrawable().apply { callback = ImageView(context); setComposition(composition); setBounds(0, 0, 240, 240) }
        for (frame in 0 until 56) {
            val rendered = pixels(drawable, (frame + 0.25f) / 56)
            assertTrue("Every embedded frame must render offline", rendered.count { (it ushr 24) > 0 } > 1000)
        }
    }
}
