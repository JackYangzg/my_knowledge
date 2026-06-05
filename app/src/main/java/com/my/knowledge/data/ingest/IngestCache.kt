package com.my.knowledge.data.ingest

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
 * Cache key today: [SourceDocumentEntity.sha256]. The review's
 * ARCH-6 follow-up is to also key on the analysis `promptVersion`
 * so a prompt upgrade invalidates the cache automatically. That
 * needs a v10→v11 migration to add `promptVersion` to
 * `source_document`; tracked as a follow-up.
 *
 * Cache hit semantics:
 *   - A DIFFERENT source row (same sha256, different `id`) must
 *     have reached `STATUS_GENERATED` end-to-end.
 *   - The new source is the one we're claiming for, so a "self-
 *     match" doesn't count.
 *   - The task input must not carry `"reprocess": true` — that's
 *     the user signal to ignore the cache and re-run the pipeline.
 */
class IngestCache(
    private val sourceDao: SourceDocumentDao,
) {
    /**
     * True iff this task's source row has a non-blank sha256 that
     * matches an *already-generated* sibling source row.
     */
    suspend fun isHit(task: ProcessingTaskEntity): Boolean {
        if (task.inputJson.contains("\"reprocess\":true")) return false
        val sourceId = task.sourceId ?: task.targetId
        val source = sourceDao.getById(sourceId) ?: return false
        if (source.sha256.isBlank()) return false
        val previous = sourceDao.findBySha256(source.sha256) ?: return false
        return previous.id != source.id &&
            previous.status == SourceDocumentEntity.STATUS_GENERATED
    }
}
