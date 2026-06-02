package com.my.knowledge.domain.usecase

import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import com.my.knowledge.data.file.LocalFileStore

class DeleteSourceUseCase(
    private val db: AppDatabase,
    private val fileStore: LocalFileStore
) {
    suspend fun deleteSource(sourceId: String): Set<String> {
        val source = db.sourceDocumentDao().getById(sourceId) ?: return emptySet()
        val now = System.currentTimeMillis()
        val affectedKbIds = mutableSetOf<String>()
        db.processingTaskDao().cancelBySource(sourceId, now)
        db.reviewItemDao().skipBySource(sourceId, now)
        db.knowledgeFragmentDao().deleteBySource(sourceId)
        db.parsedContentDao().deleteBySource(sourceId)
        db.analysisResultDao().deleteBySource(sourceId)

        val generatedItems = db.knowledgeItemDao().getAllBySourceId(sourceId)
        generatedItems.forEach { item ->
            db.archiveRecommendationDao().deleteByItemId(item.id)
            db.processingTaskDao().deleteByTarget("knowledge_item", item.id)
            db.processingTaskLogDao().deleteByTarget("knowledge_item", item.id)
            db.knowledgeFragmentDao().deleteByItemId(item.id)
            db.knowledgeGraphDao().deleteEmbeddingsByItem(item.id)
            db.aiConversationDao().getIdsByScope("knowledge_item", item.id).forEach { conversationId ->
                db.aiMessageDao().deleteByConversation(conversationId)
                db.askCitationDao().deleteByConversation(conversationId)
            }
            db.aiConversationDao().deleteByScope("knowledge_item", item.id)
            db.knowledgeItemDao().softDelete(item.id, now)
            db.knowledgeItemDao().updateItemCount(item.knowledgeBaseId)
            affectedKbIds += item.knowledgeBaseId
        }

        if (generatedItems.isEmpty()) {
            db.knowledgeItemDao().getBySourceHash(source.sha256).forEach { item ->
                db.archiveRecommendationDao().deleteByItemId(item.id)
                db.processingTaskDao().deleteByTarget("knowledge_item", item.id)
                db.processingTaskLogDao().deleteByTarget("knowledge_item", item.id)
                db.knowledgeFragmentDao().deleteByItemId(item.id)
                db.knowledgeGraphDao().deleteEmbeddingsByItem(item.id)
                db.knowledgeItemDao().softDelete(item.id, now)
                db.knowledgeItemDao().updateItemCount(item.knowledgeBaseId)
                affectedKbIds += item.knowledgeBaseId
            }
        }

        db.sourceDocumentDao().markDeleted(sourceId, now)
        fileStore.deleteSourceFiles(sourceId)
        return affectedKbIds
    }

    suspend fun restoreSourceMarker(sourceId: String) {
        db.sourceDocumentDao().updateStatus(
            id = sourceId,
            status = SourceDocumentEntity.STATUS_IMPORTED,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
    }
}
