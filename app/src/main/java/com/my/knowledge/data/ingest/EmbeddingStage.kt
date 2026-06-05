package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.ProcessingTaskEntity

/**
 * P0-1: thin stage wrapper for the `embedding` step. This stage
 * is a queue-visibility no-op: real fragment embeddings are
 * maintained by the repository rebuild path, so the task just
 * marks itself successful. Kept as a stage so the pipeline
 * has a 4-step shape (parse → analysis → generation → embedding)
 * and operators see the task row land in `STATUS_SUCCESS`
 * before the next source's claim round.
 */
class EmbeddingStage : Stage {
    override suspend fun run(task: ProcessingTaskEntity, orchestrator: IngestOrchestrator): Boolean {
        orchestrator.runEmbeddingTask(task)
        return true
    }
}
