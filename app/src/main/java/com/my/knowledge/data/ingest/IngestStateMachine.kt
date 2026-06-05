package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.dao.KnowledgeItemDao
import com.my.knowledge.data.db.dao.SourceDocumentDao
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity

/**
 * P0-1 third half: single chokepoint for `source_document` /
 * `knowledge_item` status writes during the ingest pipeline. Every
 * stage method used to call `sourceDocumentDao().updateStatus(...)`
 * directly; centralizing here does two things:
 *
 *   1. **Single point of instrumentation** — a future P0-1.4 stage
 *      can hook a transition log or metric here without touching
 *      the four stage methods.
 *   2. **Coordinated cross-table writes** — `transitionToParsing`
 *      flips the source to `STATUS_PARSING` AND the linked
 *      knowledge item to `STATUS_PROCESSING` in two writes that
 *      always happen together. Before this, the two writes were
 *      scattered across stage methods and easy to drift apart
 *      (e.g. a 2023 bug where a retry left the item `archived`
 *      while the source was back to `parsing`).
 *
 * Legal transitions (the only ones the stage methods should call):
 *   imported   → parsing     (parseTask start)
 *   parsing    → parsed      (parseTask end on success)
 *   parsed     → analyzing   (analysisTask start)
 *   analyzing  → generated   (generationTask end on success)
 *   any        → failed      (caught exception in runTask)
 *   analyzing  → imported    (analysis bail-out: model API key
 *                             not configured, requires user action)
 *
 * The methods take a `now: Long` so a test can pin the timestamp
 * deterministically.
 */
class IngestStateMachine(
    private val sourceDao: SourceDocumentDao,
    private val itemDao: KnowledgeItemDao,
) {
    suspend fun transitionToParsing(sourceId: String, now: Long = System.currentTimeMillis()) {
        sourceDao.updateStatus(sourceId, SourceDocumentEntity.STATUS_PARSING, null, now)
        itemDao.updateStatusBySourceId(sourceId, KnowledgeItemEntity.STATUS_PROCESSING, now)
    }

    suspend fun transitionToParsed(sourceId: String, now: Long = System.currentTimeMillis()) {
        sourceDao.updateStatus(sourceId, SourceDocumentEntity.STATUS_PARSED, null, now)
    }

    suspend fun transitionToAnalyzing(sourceId: String, now: Long = System.currentTimeMillis()) {
        sourceDao.updateStatus(sourceId, SourceDocumentEntity.STATUS_ANALYZING, null, now)
        itemDao.updateStatusBySourceId(sourceId, KnowledgeItemEntity.STATUS_PROCESSING, now)
    }

    suspend fun transitionToGenerated(sourceId: String, now: Long = System.currentTimeMillis()) {
        sourceDao.updateStatus(sourceId, SourceDocumentEntity.STATUS_GENERATED, null, now)
    }

    /**
     * Used by the analysisTask bail-out when the model API key is
     * not configured: the source drops back to `imported` so the
     * worker can pick it up again after the user fills in the key,
     * and the linked item is marked failed with the same message.
     */
    suspend fun transitionToImportedWaitingForConfig(
        sourceId: String,
        errorMessage: String,
        now: Long = System.currentTimeMillis(),
    ) {
        sourceDao.updateStatus(sourceId, SourceDocumentEntity.STATUS_IMPORTED, errorMessage, now)
        itemDao.updateFailureBySourceId(sourceId, errorMessage, now)
    }

    /**
     * Catch-all failure sink. `errorMessage` is what the UI shows
     * in the source's "失败原因" row.
     */
    suspend fun transitionToFailed(
        sourceId: String,
        errorMessage: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        sourceDao.updateStatus(sourceId, SourceDocumentEntity.STATUS_FAILED, errorMessage, now)
        itemDao.updateFailureBySourceId(sourceId, errorMessage, now)
    }
}
