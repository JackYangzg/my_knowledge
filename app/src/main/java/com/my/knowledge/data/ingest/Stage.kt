package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.ProcessingTaskEntity

/**
 * P0-1 stage split: one [Stage] per pipeline step. The
 * [IngestOrchestrator.runTask] dispatch site maps `taskType` to
 * the right stage and runs it. Stages are deliberately thin
 * wrappers around the orchestrator's existing `private suspend fun
 * xxxTask` methods — the real per-stage business logic stays where
 * it is, with the per-KB write lock and the [IngestStateMachine]
 * transitions it needs to coordinate with. Splitting bodies out
 * is a follow-up: until then this gives a stable dispatch surface
 * so the next refactor (stage method bodies into dedicated
 * classes, with their own injected dependencies) is a local change
 * inside the stage file, not a sweeping edit of [IngestOrchestrator].
 *
 * Stages run sequentially inside a single claim-loop lane: a parse
 * task for source S chains into an analysis task for S, which
 * chains into a generation task for S, all on the same lane
 * (see [IngestScheduler.shouldClaimNextSameSourceTask]). The
 * `embedding` task is a no-op kept for explicit queue visibility
 * (real embeddings are maintained by the repository rebuild path).
 */
interface Stage {
    /**
     * Execute the stage. Implementations should call the
     * orchestrator's per-stage `internal` method (parseTask /
     * analysisTask / generationTask / embeddingTask) and return
     * `true` on success, `false` on non-retryable failure.
     */
    suspend fun run(task: ProcessingTaskEntity, orchestrator: IngestOrchestrator): Boolean
}
