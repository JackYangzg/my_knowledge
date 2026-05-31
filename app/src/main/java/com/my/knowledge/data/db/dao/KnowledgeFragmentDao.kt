package com.my.knowledge.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.my.knowledge.data.db.entity.KnowledgeFragmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeFragmentDao {
    @Query("SELECT * FROM knowledge_fragment WHERE itemId = :itemId ORDER BY startOffset ASC")
    fun observeByItem(itemId: String): Flow<List<KnowledgeFragmentEntity>>

    @Query("SELECT * FROM knowledge_fragment WHERE knowledgeBaseId = :kbId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentByBase(kbId: String, limit: Int): List<KnowledgeFragmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(fragments: List<KnowledgeFragmentEntity>)

    @Query("DELETE FROM knowledge_fragment WHERE itemId = :itemId")
    suspend fun deleteByItemId(itemId: String)
}
