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
        val useFts = query.length >= 2 && !query.contains("*")
        return if (useFts) {
            if (knowledgeBaseId == null) searchDao.ftsSearchAll("\"$query\"")
            else searchDao.ftsSearchByKb("\"$query\"", knowledgeBaseId)
        } else {
            if (knowledgeBaseId == null) searchDao.searchAll(query)
            else searchDao.searchByKb(query, knowledgeBaseId)
        }
    }
}
