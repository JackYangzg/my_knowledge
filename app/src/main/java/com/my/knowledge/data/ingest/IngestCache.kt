package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.dao.AnalysisResultDao
import com.my.knowledge.data.db.dao.SourceDocumentDao
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity

/**
 * P0-1 second half: ingest cache fast-path. When a user re-imports
 * a file that has the same `sha256` as one that already completed
 * the pipeline, we skip parse / analysis / generation and jump
 * straight to embedding — saving one Stage 1 LLM call and one
 * Stage 2 LLM call per duplicate.
 *
 * Cache key: [SourceDocumentEntity.sha256] + the previous source's
 * analysis `promptVersion`. CQ-12/ARCH-6 fix: bumping
 * [PromptVersions.INGEST_ANALYSIS_V1] automatically invalidates the
 * cache (a previous source whose analysis ran under the old prompt
 * is no longer a valid template for the new pipeline's output).
 *
 * Cache hit semantics:
 *   - A DIFFERENT source row (same sha256, different `id`) must
 *     have reached `STATUS_GENERATED` end-to-end.
 *   - That source's analysis row must carry
 *     [PromptVersions.INGEST_ANALYSIS_V1] (the same prompt the
 *     orchestrator would run today). A stale analysis under an
 *     older prompt invalidates the hit.
 *   - The task input must not carry `"reprocess": true` — that's
 *     the user signal to ignore the cache and re-run the pipeline.
 */
class IngestCache(
    private val sourceDao: SourceDocumentDao,
    private val analysisDao: AnalysisResultDao,
) {
    /**
     * True iff this task's source row has a non-blank sha256 that
     * matches an *already-generated* sibling source row whose
     * analysis was produced by the currently-active prompt.
     */
    suspend fun isHit(task: ProcessingTaskEntity): Boolean {
        if (task.inputJson.contains("\"reprocess\":true")) return false
        val sourceId = task.sourceId ?: task.targetId
        val source = sourceDao.getById(sourceId) ?: return false
        if (source.sha256.isBlank()) return false
        val previous = sourceDao.findBySha256(source.sha256) ?: return false
        if (previous.id == source.id) return false
        if (previous.status != SourceDocumentEntity.STATUS_GENERATED) return false
        val previousAnalysis = analysisDao.getLatestBySource(previous.id) ?: return false
        return previousAnalysis.promptVersion == PromptVersions.INGEST_ANALYSIS_V1
    }
}
