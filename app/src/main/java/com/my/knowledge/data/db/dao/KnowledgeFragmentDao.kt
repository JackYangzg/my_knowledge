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

    @Query("SELECT * FROM knowledge_fragment WHERE sourceId = :sourceId ORDER BY orderIndex ASC")
    suspend fun getBySource(sourceId: String): List<KnowledgeFragmentEntity>

    @Query("""
        UPDATE knowledge_fragment
        SET itemId = :itemId,
            knowledgeItemId = :itemId,
            knowledgeBaseId = :knowledgeBaseId
        WHERE sourceId = :sourceId
    """)
    suspend fun attachSourceFragmentsToItem(sourceId: String, itemId: String, knowledgeBaseId: String)

    @Query("DELETE FROM knowledge_fragment WHERE itemId = :itemId")
    suspend fun deleteByItemId(itemId: String)

    @Query("DELETE FROM knowledge_fragment WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)

    /** Used when moving an item to a different KB — updates kbId on all its fragments. */
    @Query("UPDATE knowledge_fragment SET knowledgeBaseId = :targetKbId WHERE itemId = :itemId")
    suspend fun updateKbIdByItem(itemId: String, targetKbId: String)
}
