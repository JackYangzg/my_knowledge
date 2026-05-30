package com.my.knowledge.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchDao {
    @Query("""
        SELECT knowledge_item.* FROM knowledge_item
        JOIN knowledge_item_fts ON knowledge_item.rowid = knowledge_item_fts.rowid
        WHERE knowledge_item_fts MATCH :query
        AND knowledge_item.deletedAt IS NULL
        ORDER BY knowledge_item.updatedAt DESC
    """)
    fun ftsSearchAll(query: String): Flow<List<KnowledgeItemEntity>>

    @Query("""
        SELECT knowledge_item.* FROM knowledge_item
        JOIN knowledge_item_fts ON knowledge_item.rowid = knowledge_item_fts.rowid
        WHERE knowledge_item_fts MATCH :query
        AND knowledge_item.knowledgeBaseId = :kbId
        AND knowledge_item.deletedAt IS NULL
        ORDER BY knowledge_item.updatedAt DESC
    """)
    fun ftsSearchByKb(query: String, kbId: String): Flow<List<KnowledgeItemEntity>>

    @Query("""
        SELECT * FROM knowledge_item
        WHERE (title LIKE '%' || :query || '%' OR contentMarkdown LIKE '%' || :query || '%')
        AND deletedAt IS NULL
    """)
    fun searchAll(query: String): Flow<List<KnowledgeItemEntity>>

    @Query("""
        SELECT * FROM knowledge_item
        WHERE (title LIKE '%' || :query || '%' OR contentMarkdown LIKE '%' || :query || '%')
        AND knowledgeBaseId = :kbId
        AND deletedAt IS NULL
    """)
    fun searchByKb(query: String, kbId: String): Flow<List<KnowledgeItemEntity>>
}
