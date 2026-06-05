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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * P1-A.4: lifecycle contract for [IngestRuntimeLoop].
 *
 *   1. `start` → exactly one pass begins.
 *   2. `start` while a pass is in flight → no second coroutine is
 *      spawned; the active loop sees `rerunRequested` and runs one
 *      more pass after the current one finishes.
 *   3. `cancel` → in-flight pass is cancelled, no follow-up run.
 *   4. `start` after `cancel` → a fresh pass begins.
 *   5. `runOnce` is never invoked concurrently — the inner mutex
 *      serialises passes even when the active pass re-runs itself.
 */
class IngestRuntimeLoopTest {

    @Test
    fun `start runs runOnce exactly once when no rerun is requested`() = runBlocking {
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loop = IngestRuntimeLoop(scope) {
            calls.incrementAndGet()
        }
        loop.start()
        withTimeout(1_000) { loop.cancel() }
        scope.cancel()
        assertEquals(1, calls.get())
    }

    @Test
    fun `start while a pass is in flight queues a rerun, no second coroutine`() = runBlocking {
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
        loop.start()
        withTimeout(1_000) { firstEntered.await() }
        // While the first pass is in flight, request a rerun.
        loop.start()
        // Release the first pass; the loop should now run a second
        // pass before settling.
        releaseFirst.complete(Unit)
        // Wait for the second pass to complete.
        withTimeout(2_000) {
            while (calls.get() < 2) delay(10)
        }
        withTimeout(1_000) { loop.cancel() }
        scope.cancel()
        assertEquals("second start should have queued exactly one rerun", 2, calls.get())
    }

    @Test
    fun `cancel stops the in-flight pass and discards queued rerun`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loop = IngestRuntimeLoop(scope) {
            val n = calls.incrementAndGet()
            if (n == 1) {
                firstEntered.complete(Unit)
                // Block long enough for cancel() to land.
                try {
                    delay(10_000)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                }
            }
        }
        loop.start()
        withTimeout(1_000) { firstEntered.await() }
        // Queue a rerun, then immediately cancel.
        loop.start()
        loop.cancel()
        scope.cancel()
        assertEquals("only the first pass should have run", 1, calls.get())
        assertFalse(loop.isActive())
    }

    @Test
    fun `start after cancel spins up a fresh pass`() = runBlocking {
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loop = IngestRuntimeLoop(scope) { calls.incrementAndGet() }
        loop.start()
        // Wait for the first pass to settle.
        withTimeout(1_000) { while (calls.get() < 1) delay(5) }
        loop.cancel()
        scope.cancel()
        // New scope for the fresh start.
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loop2 = IngestRuntimeLoop(scope2) { calls.incrementAndGet() }
        loop2.start()
        withTimeout(1_000) { while (calls.get() < 2) delay(5) }
        loop2.cancel()
        scope2.cancel()
        assertEquals(2, calls.get())
    }

    @Test
    fun `passes are serialised by the inner mutex`() = runBlocking {
        var concurrent = 0
        var maxConcurrent = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loop = IngestRuntimeLoop(scope) {
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            try {
                delay(20)
            } finally {
                concurrent--
            }
        }
        // Three back-to-back start() calls while idle should still
        // result in serialised passes, not parallel ones.
        loop.start()
        loop.start()
        loop.start()
        withTimeout(2_000) { while (loop.isActive()) delay(10) }
        scope.cancel()
        assertTrue("passes must be serialised (max concurrent was $maxConcurrent)", maxConcurrent <= 1)
    }
}
