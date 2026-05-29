package com.my.knowledge.data.search

import com.my.knowledge.data.db.dao.SearchDao
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import kotlinx.coroutines.flow.Flow

interface SearchEngine {
    fun search(query: String, knowledgeBaseId: String? = null): Flow<List<KnowledgeItemEntity>>
}

class FtsSearchEngine(
    private val searchDao: SearchDao
) : SearchEngine {
    override fun search(query: String, knowledgeBaseId: String?): Flow<List<KnowledgeItemEntity>> {
        val sanitizedQuery = "*$query*" // Basic wildcard search
        return if (knowledgeBaseId == null) {
            searchDao.searchAll(sanitizedQuery)
        } else {
            searchDao.searchByKb(sanitizedQuery, knowledgeBaseId)
        }
    }
}
