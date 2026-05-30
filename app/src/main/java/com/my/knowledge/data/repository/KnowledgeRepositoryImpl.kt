package com.my.knowledge.data.repository

import com.my.knowledge.data.db.dao.ArchiveRecommendationDao
import com.my.knowledge.data.db.dao.KnowledgeBaseDao
import com.my.knowledge.data.db.dao.KnowledgeItemDao
import com.my.knowledge.data.db.dao.ProcessingTaskDao
import com.my.knowledge.data.db.dao.AiConversationDao
import com.my.knowledge.data.db.dao.AiMessageDao
import com.my.knowledge.data.db.dao.KnowledgeThreadDao
import com.my.knowledge.data.db.dao.KnowledgeThreadLogDao
import com.my.knowledge.data.db.entity.ArchiveRecommendationEntity
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.AiConversationEntity
import com.my.knowledge.data.db.entity.AiMessageEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.util.*

class KnowledgeRepositoryImpl(
    private val kbDao: KnowledgeBaseDao,
    private val itemDao: KnowledgeItemDao,
    private val taskDao: ProcessingTaskDao,
    private val recommendationDao: ArchiveRecommendationDao,
    private val conversationDao: AiConversationDao,
    private val messageDao: AiMessageDao,
    private val threadDao: KnowledgeThreadDao,
    private val threadLogDao: KnowledgeThreadLogDao
) : KnowledgeRepository {

    // === KnowledgeBase operations ===
    override fun observeAllBases(): Flow<List<KnowledgeBaseEntity>> = kbDao.observeAll()

    override fun observeItemsByKb(kbId: String, limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>> =
        itemDao.observePagedByKb(kbId, limit, offset)

    override fun observeItemCount(kbId: String): Flow<Int> = itemDao.observeCountByKb(kbId)

    override suspend fun createBase(name: String, description: String?, type: String, iconText: String?): KnowledgeBaseEntity {
        val kb = KnowledgeBaseEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            iconText = iconText ?: name.take(1),
            type = type,
            isSystem = type == "unfiled" || type == "system",
            allowDelete = type != "unfiled",
            itemCount = 0,
            sortOrder = if (type == "unfiled") -1 else 0,
            threadStatus = "idle",
            gapStatus = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            deletedAt = null
        )
        kbDao.insert(kb)
        return kb
    }

    override suspend fun getBaseById(id: String): KnowledgeBaseEntity? = kbDao.getById(id)

    override suspend fun updateBase(base: KnowledgeBaseEntity) {
        kbDao.update(base.copy(updatedAt = System.currentTimeMillis()))
    }

    override suspend fun deleteBase(id: String, moveToUnfiled: Boolean) {
        val base = kbDao.getById(id) ?: return
        
        if (!base.allowDelete) {
            throw IllegalArgumentException("Cannot delete system knowledge base")
        }

        if (moveToUnfiled) {
            val unfiled = getUnfiledBase() ?: createBase("未归类", "默认知识存放处", "unfiled", "未")
            // Move all items to unfiled
            itemDao.observePagedByKb(id, Int.MAX_VALUE, 0).collect { items ->
                items.forEach { item ->
                    itemDao.moveToBase(item.id, unfiled.id, System.currentTimeMillis())
                }
            }
        } else {
            // Hard delete all items first
            itemDao.observePagedByKb(id, Int.MAX_VALUE, 0).collect { items ->
                items.forEach { item ->
                    itemDao.hardDelete(item.id)
                }
            }
        }
        
        kbDao.hardDelete(id)
    }

    // === KnowledgeItem operations ===
    override suspend fun createItem(item: KnowledgeItemEntity): KnowledgeItemEntity {
        itemDao.insert(item)
        itemDao.updateItemCount(item.knowledgeBaseId)
        return item
    }

    override suspend fun getItemById(id: String): KnowledgeItemEntity? = itemDao.getById(id)

    override suspend fun updateItem(item: KnowledgeItemEntity) {
        itemDao.update(item.copy(updatedAt = System.currentTimeMillis()))
    }

    override suspend fun deleteItem(id: String, softDelete: Boolean) {
        if (softDelete) {
            itemDao.softDelete(id, System.currentTimeMillis())
        } else {
            itemDao.hardDelete(id)
        }
        // Update counts
        itemDao.observePagedByKb(id, 1, 0).collect { }
    }

    override suspend fun moveItemToBase(itemId: String, targetKbId: String) {
        itemDao.moveToBase(itemId, targetKbId, System.currentTimeMillis())
        itemDao.updateItemCount(targetKbId)
    }

    // === Unfiled operations ===
    override fun observeUnfiledItems(limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>> =
        itemDao.observeUnfiledItems(limit, offset)

    override fun observeUnfiledItemCount(): Flow<Int> = itemDao.observeUnfiledItemCount()

    override suspend fun getUnfiledBase(): KnowledgeBaseEntity? = kbDao.getByType("unfiled")

    // === Content Hash ===
    override fun calculateContentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // === ProcessingTask operations ===
    override suspend fun createProcessingTask(task: ProcessingTaskEntity): ProcessingTaskEntity {
        taskDao.insert(task)
        return task
    }

    override suspend fun updateProcessingTask(task: ProcessingTaskEntity) {
        taskDao.update(task.copy(updatedAt = System.currentTimeMillis()))
    }

    override suspend fun getPendingTask(targetType: String, targetId: String): ProcessingTaskEntity? =
        taskDao.getPendingTask(targetType, targetId)

    override suspend fun getActiveTasks(): Flow<List<ProcessingTaskEntity>> = taskDao.observeActiveTasks()

    override suspend fun retryTask(taskId: String) {
        taskDao.retryTask(taskId, System.currentTimeMillis())
    }

    // === ArchiveRecommendation operations ===
    override suspend fun createArchiveRecommendation(recommendation: ArchiveRecommendationEntity): ArchiveRecommendationEntity {
        recommendationDao.insert(recommendation)
        return recommendation
    }

    override suspend fun getRecommendationForItem(itemId: String): ArchiveRecommendationEntity? =
        recommendationDao.getByItemId(itemId)

    override suspend fun acceptRecommendation(recommendationId: String) {
        val recommendation = recommendationDao.getByItemId(recommendationId) ?: return
        recommendationDao.updateStatus(recommendationId, "accepted", System.currentTimeMillis())
        
        // Move item to recommended base
        recommendation.recommendedKnowledgeBaseId?.let { kbId ->
            itemDao.moveToBase(recommendation.itemId, kbId, System.currentTimeMillis())
            itemDao.updateItemCount(kbId)
        }
    }

    override suspend fun rejectRecommendation(recommendationId: String) {
        recommendationDao.updateStatus(recommendationId, "rejected", System.currentTimeMillis())
    }

    override fun observePendingRecommendations(): Flow<List<ArchiveRecommendationEntity>> =
        recommendationDao.observePending()

    // === Batch operations ===
    override suspend fun batchUpdateItemCounts(baseIds: List<String>) {
        baseIds.forEach { kbId ->
            itemDao.updateItemCount(kbId)
        }
    }

    // === AI Conversation operations ===
    override fun observeConversations(scopeType: String, scopeId: String): Flow<List<AiConversationEntity>> =
        conversationDao.observeByScope(scopeType, scopeId)

    override suspend fun createConversation(conversation: AiConversationEntity): AiConversationEntity {
        conversationDao.insert(conversation)
        return conversation
    }

    override suspend fun getConversation(id: String): AiConversationEntity? =
        conversationDao.getById(id)

    override suspend fun updateConversation(conversation: AiConversationEntity) {
        conversationDao.update(conversation.copy(updatedAt = System.currentTimeMillis()))
    }

    // === AI Message operations ===
    override fun observeMessages(conversationId: String): Flow<List<AiMessageEntity>> =
        messageDao.observeByConversation(conversationId)

    override suspend fun createMessage(message: AiMessageEntity): AiMessageEntity {
        messageDao.insert(message)
        return message
    }

    override suspend fun getRecentMessages(conversationId: String, limit: Int): List<AiMessageEntity> =
        messageDao.getRecentMessages(conversationId, limit)

    // === Knowledge Thread operations ===
    override suspend fun getThreadByKb(kbId: String): KnowledgeThreadEntity? =
        threadDao.getByKb(kbId)

    override suspend fun saveThread(thread: KnowledgeThreadEntity) {
        val existing = threadDao.getByKb(thread.knowledgeBaseId)
        if (existing != null) {
            threadDao.update(thread.copy(id = existing.id))
        } else {
            threadDao.insert(thread)
        }
    }

    override fun observeThreadLogs(threadId: String): Flow<List<KnowledgeThreadLogEntity>> =
        threadLogDao.observeByThread(threadId)

    override suspend fun appendThreadLog(log: KnowledgeThreadLogEntity) {
        threadLogDao.insert(log)
    }
}