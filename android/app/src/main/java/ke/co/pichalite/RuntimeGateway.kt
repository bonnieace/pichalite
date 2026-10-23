package ke.co.pichalite

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object RuntimeGateway {
    private val lifecycle = Mutex()
    fun root(context: Context) = File(context.filesDir, "pichalite")

    suspend fun call(context: Context, request: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            if (PichaRuntime.nativeStatus() != 2) {
                val started = PichaRuntime.nativeStart(arrayOf("--root", root(context).absolutePath))
                check(started == 0 || started == 1) { "Could not open the photo queue: ${PichaRuntime.nativeLastError()}" }
            }
            val reply = JSONObject(NativeJobs.nativeCall(request.toString()))
            check(reply.optBoolean("ok")) { reply.optString("error", "Photo queue unavailable") }
            reply
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        lifecycle.withLock { PichaRuntime.nativeStop(10_000) }
    }
}
