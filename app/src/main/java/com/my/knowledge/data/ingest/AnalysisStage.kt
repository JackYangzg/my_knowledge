package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.ProcessingTaskEntity

/**
 * P0-1: thin stage wrapper for the `analysis` step. The real work
 * lives in [IngestOrchestrator.analysisTask] — LLM call, JSON
 * parse, [AnalysisResultEntity] insert, with the long-source
 * chunked path branching through [IngestOrchestrator.requestAiAnalysisLongSource]
 * when the parsed markdown exceeds [IngestOrchestrator.LONG_SOURCE_BUDGET_CHARS].
 */
class AnalysisStage : Stage {
    override suspend fun run(task: ProcessingTaskEntity, orchestrator: IngestOrchestrator): Boolean {
        orchestrator.runAnalysisTask(task)
        return true
    }
}
