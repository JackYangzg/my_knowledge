package com.my.knowledge.domain.repository

import com.my.knowledge.data.db.entity.ArchiveRecommendationEntity
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.AiConversationEntity
import com.my.knowledge.data.db.entity.AiMessageEntity
import kotlinx.coroutines.flow.Flow

interface KnowledgeRepository {
    // === KnowledgeBase operations ===
    fun observeAllBases(): Flow<List<KnowledgeBaseEntity>>
    fun observeItemsByKb(kbId: String, limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>>
    fun observeItemCount(kbId: String): Flow<Int>
    suspend fun createBase(name: String, description: String?, type: String = "normal", iconText: String? = null): KnowledgeBaseEntity
    suspend fun getBaseById(id: String): KnowledgeBaseEntity?
    suspend fun updateBase(base: KnowledgeBaseEntity)
    suspend fun deleteBase(id: String, moveToUnfiled: Boolean = false)
    
    // === KnowledgeItem operations ===
    suspend fun createItem(item: KnowledgeItemEntity): KnowledgeItemEntity
    suspend fun getItemById(id: String): KnowledgeItemEntity?
    suspend fun updateItem(item: KnowledgeItemEntity)
    suspend fun deleteItem(id: String, softDelete: Boolean = true)
    suspend fun moveItemToBase(itemId: String, targetKbId: String)
    
    // === Unfiled operations ===
    fun observeUnfiledItems(limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>>
    fun observeUnfiledItemCount(): Flow<Int>
    suspend fun getUnfiledBase(): KnowledgeBaseEntity?
    
    // === Content Hash ===
    fun calculateContentHash(content: String): String
    
    // === ProcessingTask operations ===
    suspend fun createProcessingTask(task: ProcessingTaskEntity): ProcessingTaskEntity
    suspend fun updateProcessingTask(task: ProcessingTaskEntity)
    suspend fun getPendingTask(targetType: String, targetId: String): ProcessingTaskEntity?
    suspend fun getActiveTasks(): Flow<List<ProcessingTaskEntity>>
    suspend fun retryTask(taskId: String)
    
    // === ArchiveRecommendation operations ===
    suspend fun createArchiveRecommendation(recommendation: ArchiveRecommendationEntity): ArchiveRecommendationEntity
    suspend fun getRecommendationForItem(itemId: String): ArchiveRecommendationEntity?
    suspend fun acceptRecommendation(recommendationId: String)
    suspend fun rejectRecommendation(recommendationId: String)
    fun observePendingRecommendations(): Flow<List<ArchiveRecommendationEntity>>
    
    // === Batch operations ===
    suspend fun batchUpdateItemCounts(baseIds: List<String>)

    // === AI Conversation operations ===
    fun observeConversations(scopeType: String, scopeId: String): Flow<List<AiConversationEntity>>
    suspend fun createConversation(conversation: AiConversationEntity): AiConversationEntity
    suspend fun getConversation(id: String): AiConversationEntity?
    suspend fun updateConversation(conversation: AiConversationEntity)

    // === AI Message operations ===
    fun observeMessages(conversationId: String): Flow<List<AiMessageEntity>>
    suspend fun createMessage(message: AiMessageEntity): AiMessageEntity
    suspend fun getRecentMessages(conversationId: String, limit: Int): List<AiMessageEntity>

    // === Knowledge Thread operations ===
    suspend fun getThreadByKb(kbId: String): com.my.knowledge.data.db.entity.KnowledgeThreadEntity?
    suspend fun saveThread(thread: com.my.knowledge.data.db.entity.KnowledgeThreadEntity)
    fun observeThreadLogs(threadId: String): Flow<List<com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity>>
    suspend fun appendThreadLog(log: com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity)
}