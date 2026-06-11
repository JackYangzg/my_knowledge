package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.ProcessingTaskEntity

class WikiMergeStage : Stage {
    override suspend fun run(task: ProcessingTaskEntity, orchestrator: IngestOrchestrator): Boolean {
        orchestrator.runWikiMergeTask(task)
        return true
    }
}
