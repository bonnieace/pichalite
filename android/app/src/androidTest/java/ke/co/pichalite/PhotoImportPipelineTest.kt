package ke.co.pichalite

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoImportPipelineTest {
    @Test fun twoTransfersOverlapAndDeliverWithoutWaitingForSlowPhoto() = runBlocking {
        withTimeout(5_000) {
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val releaseSlow = CompletableDeferred<Unit>()
            val delivered = CompletableDeferred<Unit>()
            val results = mutableListOf<Int>()
            var active = 0
            var peak = 0
            val job = launch {
                importInParallel((1..100).toList(), transfer = { item ->
                    active++
                    peak = maxOf(peak, active)
                    try {
                        if (item == 1) { firstStarted.complete(Unit); releaseSlow.await() }
                        if (item == 2) { secondStarted.complete(Unit); firstStarted.await() }
                        item
                    } finally { active-- }
                }, onImported = { results += it; delivered.complete(Unit) }, onFailure = { throw it })
            }
            firstStarted.await(); secondStarted.await(); delivered.await()
            assertFalse("The slow transfer must still be running", releaseSlow.isCompleted)
            assertTrue(results.contains(2))
            releaseSlow.complete(Unit)
            job.join()
            assertEquals(2, peak)
            assertEquals((1..100).toList(), results.sorted())
        }
    }

    @Test fun oneFailureDoesNotStopTheBatchAndCancellationStopsBothTransfers() = runBlocking {
        val results = mutableListOf<Int>()
        val failures = mutableListOf<String?>()
        importInParallel((1..5).toList(), transfer = { if (it == 2) error("unreadable") else it },
            onImported = { results += it }, onFailure = { failures += it.message })
        assertEquals(listOf(1, 3, 4, 5), results.sorted())
        assertEquals(listOf("unreadable"), failures)
        var active = 0
        val bothStarted = CompletableDeferred<Unit>()
        val job = launch {
            importInParallel((1..100).toList(), transfer = {
                active++
                if (active == 2) bothStarted.complete(Unit)
                try { awaitCancellation() } finally { active-- }
            }, onImported = { fail("Cancelled transfers must not be queued") }, onFailure = { fail("Cancellation is not an import error") })
        }
        withTimeout(5_000) { bothStarted.await() }
        job.cancelAndJoin()
        assertEquals(0, active)
    }
}
