package com.my.knowledge.worker

import com.my.knowledge.data.ingest.WikiPageDraft
import com.my.knowledge.data.ingest.WikiPageWriteLockRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 *   2. `start` while a pass is in flight → no second pass is queued.
 *   3. `cancel` → in-flight pass is cancelled, no follow-up run.
 *   4. `start` after `cancel` → a fresh pass begins.
 *   5. `runOnce` is never invoked concurrently — the inner mutex
 *      serialises passes even when the active pass re-runs itself.
 */
class IngestRuntimeLoopTest {

    @Test
    fun `start runs runOnce exactly once`() = runBlocking {
        val calls = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loop = IngestRuntimeLoop(scope) {
            calls.incrementAndGet()
            entered.complete(Unit)
        }
        loop.start()
        withTimeout(1_000) { entered.await() }
        withTimeout(1_000) { while (loop.isActive()) delay(10) }
        scope.cancel()
        assertEquals(1, calls.get())
    }

    @Test
    fun `start while a pass is in flight is idempotent`() = runBlocking {
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
        // Re-entry must not queue another full ingest pass.
        loop.start()
        releaseFirst.complete(Unit)
        withTimeout(1_000) { while (loop.isActive()) delay(10) }
        scope.cancel()
        assertEquals("active start must not queue a post-embedding rerun", 1, calls.get())
    }

    @Test
    fun `cancel stops the in-flight pass after duplicate start`() = runBlocking {
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
        // A duplicate start is ignored, then cancel stops the active pass.
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

    @Test
    fun `wiki page locks serialize the same path even when titles drift`() = runBlocking {
        val registry = WikiPageWriteLockRegistry()
        val first = draft(path = "wiki/entities/raft.md", title = "Raft")
        val renamed = draft(path = "wiki/entities/raft.md", title = "Raft Consensus")
        var concurrent = 0
        var maxConcurrent = 0

        val jobs = listOf(first, renamed).map { page ->
            async(Dispatchers.Default) {
                registry.withLock("kb-1", page) {
                    concurrent++
                    maxConcurrent = maxOf(maxConcurrent, concurrent)
                    delay(40)
                    concurrent--
                }
            }
        }
        jobs.forEach { it.await() }

        assertEquals(1, maxConcurrent)
        assertEquals(registry.lockKey("kb-1", first), registry.lockKey("kb-1", renamed))
    }

    @Test
    fun `wiki page locks allow different files to write concurrently`() = runBlocking {
        val registry = WikiPageWriteLockRegistry()
        val entered = AtomicInteger(0)
        val bothEntered = CompletableDeferred<Unit>()

        val jobs = listOf(
            draft(path = "wiki/entities/raft.md", title = "Raft"),
            draft(path = "wiki/entities/paxos.md", title = "Paxos"),
        ).map { page ->
            async(Dispatchers.Default) {
                registry.withLock("kb-1", page) {
                    if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                    withTimeout(1_000) { bothEntered.await() }
                }
            }
        }
        jobs.forEach { it.await() }

        assertEquals(2, entered.get())
    }

    private fun draft(path: String, title: String) = WikiPageDraft(
        type = "entity",
        title = title,
        sourceType = "wiki_entity",
        markdown = "# $title",
        summary = title,
        tagsJson = "[]",
        sourceTraceJson = "{}",
        wikiPath = path,
    )
}
