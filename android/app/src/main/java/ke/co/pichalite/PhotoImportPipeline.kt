package ke.co.pichalite

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Bounded provider I/O, streaming each complete file straight into compression. */
internal suspend fun <T, R> importInParallel(
    items: List<T>,
    transfer: suspend (T) -> R,
    onImported: suspend (R) -> Unit,
    onFailure: (Exception) -> Unit,
) = coroutineScope {
    // Two small stream buffers; do not decode whole photos in the import stage.
    val pending = Channel<T>(capacity = 2)
    launch {
        try { for (item in items) pending.send(item) }
        finally { pending.close() }
    }
    repeat(2) {
        launch {
            for (item in pending) {
                try { onImported(transfer(item)) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    onFailure(error)
                }
            }
        }
    }
}
