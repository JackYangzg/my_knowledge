package com.my.knowledge.domain.repository

import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import kotlinx.coroutines.flow.Flow

interface KnowledgeRepository {
    fun observeAllBases(): Flow<List<KnowledgeBaseEntity>>
    fun observeItemsByKb(kbId: String, limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>>
    fun observeItemCount(kbId: String): Flow<Int>
    suspend fun createBase(name: String, description: String?): KnowledgeBaseEntity
    suspend fun getBaseById(id: String): KnowledgeBaseEntity?
}
