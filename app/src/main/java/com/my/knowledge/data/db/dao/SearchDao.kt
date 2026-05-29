package com.my.knowledge.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchDao {
    @Query("SELECT * FROM knowledge_item WHERE title LIKE '%' || :query || '%' OR contentMarkdown LIKE '%' || :query || '%'")
    fun searchAll(query: String): Flow<List<KnowledgeItemEntity>>

    @Query("SELECT * FROM knowledge_item WHERE (title LIKE '%' || :query || '%' OR contentMarkdown LIKE '%' || :query || '%') AND knowledgeBaseId = :kbId")
    fun searchByKb(query: String, kbId: String): Flow<List<KnowledgeItemEntity>>
}
