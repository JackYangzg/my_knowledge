package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.ProcessingTaskEntity

/**
 * P0-1: thin stage wrapper for the `generation` step. The real
 * work lives in [IngestOrchestrator.generationTask] — root item
 * insert, AI / template wiki page merge under the per-(kbId,
 * sourceType, title) write lock, low-confidence review item
 * surfacing, and the post-generation rebuild handoff to
 * [com.my.knowledge.data.processing.RebuildDebouncer].
 */
class GenerationStage : Stage {
    override suspend fun run(task: ProcessingTaskEntity, orchestrator: IngestOrchestrator): Boolean {
        orchestrator.runGenerationTask(task)
        return true
    }
}
