package com.my.knowledge.worker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for RELIAB-1 PR-N2 (late landing).
 *
 * The bug, as reported by the user:
 *   "弹出系统消息 与实际不匹配，没有更新，一直显示0条"
 *   — the foreground-service notification (text "剩余 N 条")
 *   showed 0 indefinitely and never updated.
 *
 * Root cause (see [com.my.knowledge.worker.IngestRuntime] PR-N1
 * commit comment): the `_pendingCount` StateFlow was added but
 * nobody ever called [IngestRuntime.reportPending] from the
 * production code path. The orchestrator's `runTask` finally
 * block is the one production caller (wired in this PR via the
 * `pendingCountReporter` constructor parameter).
 *
 * What this test locks down:
 *   1. [IngestRuntime.reportPending] updates the public
 *      [IngestRuntime.pendingCount] StateFlow. This is the only
 *      way the orchestrator can push a fresh count, so the
 *      StateFlow *must* reflect whatever the reporter last sent.
 *   2. The StateFlow update is monotonic & non-decreasing
 *      tolerance is not assumed — reporters are free to send
 *      counts that go up (cold-start recovery re-enqueues
 *      sources) or down (tasks finish).
 *
 * What this test does NOT cover (by design):
 *   - The wiring at the orchestrator call site. That's a
 *     structural property of [com.my.knowledge.data.ingest.IngestOrchestrator]
 *     verified by code review (the `runTask` finally block at
 *     `IngestOrchestrator.kt` calls `pendingCountReporter` on
 *     every return path). Driving the orchestrator through an
 *     end-to-end pipeline test would require standing up
 *     `AppDatabase` / `LocalFileStore` / `KnowledgeRepository` —
 *     an integration test the suite doesn't currently have a
 *     fixture for, and a much heavier hammer than this bug
 *     needs.
 *   - The FG notification text itself. Android's
 *     `NotificationManager.notify` is observable only on a
 *     device or emulator, not in a JVM unit test.
 */
class IngestRuntimePendingCountTest {

    @Test
    fun `reportPending updates the public pendingCount StateFlow`() = runBlocking {
        // Snapshot the current value (other tests in the same JVM
        // may have left the singleton's StateFlow in any state).
        val before = IngestRuntime.pendingCount.value

        IngestRuntime.reportPending(7)
        assertEquals(
            "reportPending(7) must surface as pendingCount.value == 7",
            7, IngestRuntime.pendingCount.value,
        )

        IngestRuntime.reportPending(0)
        assertEquals(
            "reportPending(0) must surface as pendingCount.value == 0 " +
                "(the StateFlow is a count, not a high-watermark — " +
                "a drain must take it back to 0)",
            0, IngestRuntime.pendingCount.value,
        )

        // Restore the original value so we don't poison subsequent
        // tests in the same JVM that happen to inspect the
        // StateFlow. (Tests that don't touch it are unaffected, but
        // it's polite.)
        IngestRuntime.reportPending(before)
    }

    @Test
    fun `reportPending accepts a count higher than the previous one (re-enqueue case)`() = runBlocking {
        val before = IngestRuntime.pendingCount.value

        IngestRuntime.reportPending(3)
        assertEquals(3, IngestRuntime.pendingCount.value)

        // Cold-start recovery re-enqueues sources that were
        // mid-pipeline when the process died, so the count can
        // legitimately go *up* between two reporter calls. The
        // StateFlow must not cap or clamp.
        IngestRuntime.reportPending(11)
        assertEquals(
            "re-enqueue must push the count up, not be ignored as a duplicate",
            11, IngestRuntime.pendingCount.value,
        )

        IngestRuntime.reportPending(before)
    }
}
