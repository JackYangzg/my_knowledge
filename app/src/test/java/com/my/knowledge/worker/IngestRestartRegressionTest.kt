package com.my.knowledge.worker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression test for "ingest restarts after embedding completes".
 *
 * The bug: after the embedding task is the last task in the queue,
 * something re-triggers a new pass that enqueues a new parse task
 * for the same source. Expected: after embedding, the loop should
 * exit cleanly and no new parse task should be enqueued.
 *
 * This test pins the contract at the runtime loop level: a single
 * start() with an empty runOnce should result in exactly one pass.
 */
class IngestRestartRegressionTest {

    @Test
    fun `loop with single start and empty work runs exactly one pass`() = runBlocking {
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loop = IngestRuntimeLoop(scope) {
            calls.incrementAndGet()
        }
        loop.start()
        withTimeout(2_000) {
            // Wait for the pass to complete and the loop to settle.
            while (loop.isActive()) delay(10)
        }
        // Give the coroutine a moment to clean up.
        delay(100)
        scope.cancel()
        // After one start() with no rerun, exactly one pass runs.
        assertEquals("single start() must run exactly one pass, no restart",
            1, calls.get())
    }

    @Test
    fun `loop with start during in-flight pass runs at most two passes`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loop = IngestRuntimeLoop(scope) {
            val n = calls.incrementAndGet()
            if (n == 1) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        loop.start()  // pass 1 starts
        withTimeout(1_000) { firstEntered.await() }
        // While pass 1 is in flight, request a rerun.
        loop.start()  // queues a rerun
        // Release pass 1; the loop should now run pass 2.
        releaseFirst.complete(Unit)
        withTimeout(2_000) {
            while (calls.get() < 2) delay(10)
        }
        // No more reruns queued, loop should exit.
        withTimeout(2_000) { while (loop.isActive()) delay(10) }
        scope.cancel()
        // Two passes total: pass 1 (initial) + pass 2 (rerun). Not three.
        assertEquals(
            "exactly 2 passes expected (initial + one rerun), got ${calls.get()}",
            2, calls.get()
        )
    }
}
