package ke.co.pichalite

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeQueueTest {
    @Test fun compressionFinishesWhileAnotherImportIsStillTransferring() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RuntimeGateway.call(context, JSONObject().put("action", "pause").put("paused", false))
        val input = File(RuntimeGateway.root(context), "inbox/${UUID.randomUUID()}__parallel.png")
        Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).also { bitmap ->
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val transferring = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val queued = CompletableDeferred<String>()
        val pipeline = launch {
            importInParallel(listOf(1, 2), transfer = { item ->
                if (item == 1) input else { transferring.complete(Unit); release.await(); null }
            }, onImported = { file ->
                if (file != null) queued.complete(RuntimeGateway.call(context,
                    JSONObject().put("action", "enqueue").put("input", file.absolutePath)).getString("id"))
            }, onFailure = { throw it })
        }
        try {
            withTimeout(30_000) {
                transferring.await()
                val id = queued.await()
                while (true) {
                    val jobs = RuntimeGateway.call(context, JSONObject().put("action", "snapshot")).getJSONArray("jobs")
                    if ((0 until jobs.length()).any { jobs.getJSONObject(it).let { p -> p.getString("id") == id && p.getString("status") == "ready" } }) break
                    delay(150)
                }
                assertFalse("Compression must not wait for the other import", release.isCompleted)
                assertTrue(pipeline.isActive)
            }
        } finally { release.complete(Unit); pipeline.join(); RuntimeGateway.stop() }
    }

    @Test fun jniQueueRetainsPausedJobsAcrossRuntimeRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RuntimeGateway.call(context, JSONObject().put("action", "pause").put("paused", true))
        val input = File(RuntimeGateway.root(context), "inbox/${UUID.randomUUID()}__device-test.png")
        val bitmap = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(220, 150, 90))
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val added = RuntimeGateway.call(context, JSONObject().put("action", "enqueue").put("input", input.absolutePath))
        val id = added.getString("id")
        val stopped = RuntimeGateway.stop()
        assertEquals(0, stopped)
        var snapshot = RuntimeGateway.call(context, JSONObject().put("action", "snapshot"))
        assertTrue(snapshot.getBoolean("paused"))
        assertTrue(snapshot.getInt("pending") > 0)
        RuntimeGateway.call(context, JSONObject().put("action", "pause").put("paused", false))
        var result: JSONObject? = null
        repeat(90) {
            snapshot = RuntimeGateway.call(context, JSONObject().put("action", "snapshot"))
            val jobs = snapshot.getJSONArray("jobs")
            for (i in 0 until jobs.length()) {
                val job = jobs.getJSONObject(i)
                if (job.getString("id") == id && job.getString("status") == "ready") result = job
            }
            if (result == null) delay(500)
        }
        assertNotNull("Photo must finish through JNI + Rivium + Apalis", result)
        val photo = result!!
        assertEquals(1600, photo.getInt("width"))
        assertEquals(800, photo.getInt("height"))
        assertTrue(File(photo.getString("output")).isFile)
        assertEquals(0, RuntimeGateway.stop())
        snapshot = RuntimeGateway.call(context, JSONObject().put("action", "snapshot"))
        val jobs = snapshot.getJSONArray("jobs")
        assertEquals(1, (0 until jobs.length()).count { jobs.getJSONObject(it).getString("id") == id })
        assertEquals(0, RuntimeGateway.stop())
    }
}
