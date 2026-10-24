package ke.co.pichalite

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Release duplicate private files only after the existing save-and-replace flow succeeds. */
object LocalReplacementRunner {
    private val mutex = Mutex()
    suspend fun release(context: Context, photo: Photo, files: LocalPhotoFiles = LocalPhotoFiles(context)) = mutex.withLock {
        if (!files.wasDeleted(photo) || files.cacheReleased(photo)) return@withLock
        val destination = withContext(Dispatchers.IO) { files.verifiedSavedCopy(photo) }
        RuntimeGateway.call(context, JSONObject().put("action", "finish_local_export")
            .put("id", photo.id).put("destination", destination.toString()))
        withContext(Dispatchers.IO) { files.markCacheReleased(photo) }
    }
}
