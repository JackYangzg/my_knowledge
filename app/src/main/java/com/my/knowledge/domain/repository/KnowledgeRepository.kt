package com.my.knowledge.domain.repository

import com.my.knowledge.data.db.entity.ArchiveRecommendationEntity
import com.my.knowledge.data.db.entity.AskCitationEntity
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.data.db.entity.KnowledgeCommunityEntity
import com.my.knowledge.data.db.entity.KnowledgeEntityEntity
import com.my.knowledge.data.db.entity.KnowledgeFragmentEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.KnowledgeRelationEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.ProcessingTaskLogEntity
import com.my.knowledge.data.db.entity.ReviewItemEntity
import com.my.knowledge.data.db.entity.SourceManifestEntity
import com.my.knowledge.data.db.entity.AiConversationEntity
import com.my.knowledge.data.db.entity.AiMessageEntity
import kotlinx.coroutines.flow.Flow

interface KnowledgeRepository {
    // === KnowledgeBase operations ===
    fun observeAllBases(): Flow<List<KnowledgeBaseEntity>>
    fun observeItemsByKb(kbId: String, limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>>
    fun observeItemCount(kbId: String): Flow<Int>
    suspend fun createBase(name: String, description: String?, type: String = "normal", iconText: String? = null): KnowledgeBaseEntity
    suspend fun ensureDefaultBases()
    suspend fun getBaseById(id: String): KnowledgeBaseEntity?
    suspend fun getBaseByName(name: String): KnowledgeBaseEntity?
    suspend fun updateBase(base: KnowledgeBaseEntity)
    suspend fun deleteBase(id: String, moveToUnfiled: Boolean = false)
    
    // === KnowledgeItem operations ===
    suspend fun createItem(item: KnowledgeItemEntity): KnowledgeItemEntity
    suspend fun createUnfiledItemFromNote(noteId: String?, title: String, content: String, sourceType: String = "note"): KnowledgeItemEntity
    suspend fun getItemById(id: String): KnowledgeItemEntity?
    suspend fun getItemBySourceId(sourceId: String): KnowledgeItemEntity?
    suspend fun getByRawNoteId(noteId: String): KnowledgeItemEntity?
    fun observeProcessedItemsBySource(sourceId: String): Flow<List<KnowledgeItemEntity>>
    suspend fun updateItem(item: KnowledgeItemEntity)
    suspend fun deleteItem(id: String, softDelete: Boolean = true)
    suspend fun permanentDeleteItem(id: String)
    suspend fun restoreItem(id: String)
    suspend fun restoreItems(ids: List<String>)
    suspend fun permanentDeleteItems(ids: List<String>)
    fun observeDeletedItems(): Flow<List<KnowledgeItemEntity>>
    fun observeDeletedItemsPaged(limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>>
    fun observeDeletedItemCount(): Flow<Int>
    suspend fun moveItemToBase(itemId: String, targetKbId: String)
    
    // === Unfiled operations ===
    fun observeUnfiledItems(limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>>
    fun observeUnfiledItemCount(): Flow<Int>
    suspend fun getUnfiledBase(): KnowledgeBaseEntity?
    
    // === Content Hash ===
    fun calculateContentHash(content: String): String

    // === Source manifest / compiled assets ===
    suspend fun registerTextSource(ownerType: String, ownerId: String, sourceType: String, content: String, sourceUri: String? = null): SourceManifestEntity
    fun observeSources(ownerType: String, ownerId: String): Flow<List<SourceManifestEntity>>
    fun observeFragments(itemId: String): Flow<List<KnowledgeFragmentEntity>>
    suspend fun rebuildFragmentsForItem(item: KnowledgeItemEntity, sourceManifestId: String? = null): List<KnowledgeFragmentEntity>
    suspend fun rebuildGraphForBase(kbId: String)
    suspend fun refreshOverviewForBase(kbId: String)
    fun observeKnowledgeEntities(kbId: String): Flow<List<KnowledgeEntityEntity>>
    fun observeAllKnowledgeEntities(): Flow<List<KnowledgeEntityEntity>>
    fun observeKnowledgeRelations(kbId: String): Flow<List<KnowledgeRelationEntity>>
    fun observeAllKnowledgeRelations(): Flow<List<KnowledgeRelationEntity>>
    fun observeKnowledgeCommunities(kbId: String): Flow<List<KnowledgeCommunityEntity>>
    fun observeAllKnowledgeCommunities(): Flow<List<KnowledgeCommunityEntity>>
    
    // === ProcessingTask operations ===
    suspend fun createProcessingTask(task: ProcessingTaskEntity): ProcessingTaskEntity
    suspend fun updateProcessingTask(task: ProcessingTaskEntity)
    suspend fun getProcessingTask(taskId: String): ProcessingTaskEntity?
    suspend fun getPendingTask(targetType: String, targetId: String): ProcessingTaskEntity?
    suspend fun getActiveTasks(): Flow<List<ProcessingTaskEntity>>
    suspend fun retryTask(taskId: String)
    suspend fun retryProcessingForItem(itemId: String)
    suspend fun retryProcessingForSource(sourceId: String)
    suspend fun cancelTask(taskId: String)
    suspend fun appendProcessingLog(log: ProcessingTaskLogEntity)
    fun observeProcessingLogs(targetType: String, targetId: String): Flow<List<ProcessingTaskLogEntity>>

    // === Review Queue ===
    fun observePendingReviews(): Flow<List<ReviewItemEntity>>
    suspend fun resolveReview(reviewId: String, status: String)
    fun observeUnfiledWorkCount(): Flow<Int>
    fun observeProfileStats(): Flow<ProfileStats>
    
    // === ArchiveRecommendation operations ===
    suspend fun createArchiveRecommendation(recommendation: ArchiveRecommendationEntity): ArchiveRecommendationEntity
    suspend fun getRecommendationForItem(itemId: String): ArchiveRecommendationEntity?
    suspend fun acceptRecommendation(recommendationId: String)
    suspend fun rejectRecommendation(recommendationId: String)
    fun observePendingRecommendations(): Flow<List<ArchiveRecommendationEntity>>
    
    // === Batch operations ===
    suspend fun batchUpdateItemCounts(baseIds: List<String>)
    suspend fun exportMarkdownBundle(): String

    // === AI Conversation operations ===
    fun observeConversations(scopeType: String, scopeId: String): Flow<List<AiConversationEntity>>

    /**
     * Like [observeConversations] but each row also carries the current
     * message count for that conversation, computed via a single
     * GROUP-BY join in the DAO. Used by the AskSheet history drawer to
     * render the "N 条消息" badge without an N+1 query.
     */
    fun observeConversationsWithCount(
        scopeType: String,
        scopeId: String
    ): Flow<List<com.my.knowledge.data.repository.KnowledgeRepositoryImpl.ConversationWithCount>>
    suspend fun createConversation(conversation: AiConversationEntity): AiConversationEntity
    suspend fun getConversation(id: String): AiConversationEntity?
    suspend fun updateConversation(conversation: AiConversationEntity)
    suspend fun deleteConversation(id: String)
    suspend fun clearConversationsByScope(scopeType: String, scopeId: String)

    // === AI Message operations ===
    fun observeMessages(conversationId: String): Flow<List<AiMessageEntity>>
    suspend fun createMessage(message: AiMessageEntity): AiMessageEntity
    suspend fun getRecentMessages(conversationId: String, limit: Int): List<AiMessageEntity>
    suspend fun replaceCitationsForMessage(messageId: String, citations: List<AskCitationEntity>)
    fun observeCitations(messageId: String): Flow<List<AskCitationEntity>>

    // === Knowledge Thread operations ===
    suspend fun getThreadByKb(kbId: String): com.my.knowledge.data.db.entity.KnowledgeThreadEntity?
    suspend fun saveThread(thread: com.my.knowledge.data.db.entity.KnowledgeThreadEntity)
    fun observeThreadLogs(threadId: String): Flow<List<com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity>>
    suspend fun appendThreadLog(log: com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity)
    suspend fun deleteKnowledgeEntities(ids: List<String>)
    suspend fun deleteKnowledgeRelations(ids: List<String>)
    suspend fun deleteKnowledgeCommunities(ids: List<String>)
    suspend fun getEntityByName(name: String): KnowledgeEntityEntity?
}

data class ProfileStats(
    val knowledgeBaseCount: Int,
    val knowledgeItemCount: Int,
    val entityCount: Int,
    val conceptCount: Int
)
