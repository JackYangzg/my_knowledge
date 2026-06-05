package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.ProcessingTaskEntity

/**
 * P0-1: thin stage wrapper for the `parse` step. The real work
 * lives in [IngestOrchestrator.parseTask] — see that method for
 * the parse → parsed_content → fragment insert + write-lock
 * choreography. This class exists so the dispatch site can be a
 * map lookup, not a `when` over four magic strings.
 */
class ParseStage : Stage {
    override suspend fun run(task: ProcessingTaskEntity, orchestrator: IngestOrchestrator): Boolean {
        orchestrator.runParseTask(task)
        return true
    }
}
