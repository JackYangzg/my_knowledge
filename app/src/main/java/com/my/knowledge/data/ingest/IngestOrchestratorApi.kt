package com.my.knowledge.data.ingest

/**
 * CQ-10 / ARCH-3 prerequisite: the surface that callers (currently
 * [com.my.knowledge.worker.IngestRuntime] and the WorkManager worker
 * chain) depend on. Extracted from the concrete
 * [IngestOrchestrator] so:
 *
 *   - A fake can stand in for tests that want to assert scheduling
 *     behavior without spinning up the full pipeline.
 *   - The future WorkerFactory (ARCH-1) can hand a worker an
 *     `IngestOrchestratorApi` resolved through Hilt EntryPoint, with
 *     the concrete `IngestOrchestrator` swapped at the binding site
 *     (not at every call site).
 *
 * Intentionally narrow. The concrete class still owns ~2600 lines of
 * per-stage business logic; this interface just makes the *entry
 * point* overridable. New methods should not be added here without a
 * real second consumer — the public surface is the load-bearing part
 * of the contract.
 */
interface IngestOrchestratorApi {
    /**
     * Run the claim loop until either `maxTasks` tasks have been
     * processed, `parallelism` lanes have all idled out, or the
     * enclosing coroutine is cancelled. Idempotent: re-calling after
     * the loop has drained is a no-op.
     */
    suspend fun runUntilIdle(maxTasks: Int = 80, parallelism: Int = 4)

    /**
     * Cooperative cancel: signals the in-flight LLM stream to abort
     * and stops the claim loop after the current task finishes. Safe
     * to call from any thread; no-op if no run is in progress.
     */
    fun cancel()
}
