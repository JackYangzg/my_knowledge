package com.my.knowledge.data.repository

import com.my.knowledge.data.db.dao.KnowledgeBaseDao
import com.my.knowledge.data.db.dao.KnowledgeItemDao
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.Flow
import java.util.*

class KnowledgeRepositoryImpl(
    private val kbDao: KnowledgeBaseDao,
    private val itemDao: KnowledgeItemDao
) : KnowledgeRepository {
    override fun observeAllBases(): Flow<List<KnowledgeBaseEntity>> = kbDao.observeAll()

    override fun observeItemsByKb(kbId: String, limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>> =
        itemDao.observePagedByKb(kbId, limit, offset)

    override fun observeItemCount(kbId: String): Flow<Int> = itemDao.observeCountByKb(kbId)

    override suspend fun createBase(name: String, description: String?): KnowledgeBaseEntity {
        val kb = KnowledgeBaseEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            iconText = name.take(1),
            type = "normal",
            isSystem = false,
            allowDelete = true,
            itemCount = 0,
            sortOrder = 0,
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
}
