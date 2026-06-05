package com.my.knowledge.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.my.knowledge.data.db.entity.KnowledgeFragmentChainEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeFragmentChainDao {
    @Query("SELECT * FROM knowledge_fragment_chain WHERE knowledgeBaseId = :kbId ORDER BY updatedAt DESC")
    fun observeByKb(kbId: String): Flow<List<KnowledgeFragmentChainEntity>>

    @Query("SELECT * FROM knowledge_fragment_chain WHERE knowledgeBaseId = :kbId AND status = :status ORDER BY updatedAt DESC")
    fun observeByKbAndStatus(kbId: String, status: String): Flow<List<KnowledgeFragmentChainEntity>>

    @Query("SELECT * FROM knowledge_fragment_chain WHERE id = :id")
    suspend fun getById(id: String): KnowledgeFragmentChainEntity?

    @Query("SELECT * FROM knowledge_fragment_chain WHERE id = :id")
    fun observeById(id: String): Flow<KnowledgeFragmentChainEntity?>

    @Query("SELECT * FROM knowledge_fragment_chain WHERE threadId = :threadId")
    suspend fun getByThreadId(threadId: String): KnowledgeFragmentChainEntity?

    @Query("SELECT * FROM knowledge_fragment_chain WHERE knowledgeBaseId = :kbId AND status = 'NEED_REVIEW'")
    suspend fun getOpenByKb(kbId: String): List<KnowledgeFragmentChainEntity>

    @Query("SELECT * FROM knowledge_fragment_chain WHERE knowledgeBaseId = :kbId AND status IN ('RECOMMEND_READY', 'ARCHIVED') ORDER BY updatedAt DESC")
    fun observeArchived(kbId: String): Flow<List<KnowledgeFragmentChainEntity>>

    @Query("SELECT * FROM knowledge_fragment_chain ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<KnowledgeFragmentChainEntity>>

    @Query("UPDATE knowledge_fragment_chain SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)

    @Query("UPDATE knowledge_fragment_chain SET distilledItemId = :itemId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setDistilledItemId(id: String, itemId: String, updatedAt: Long)

    @Query("UPDATE knowledge_fragment_chain SET gapCount = :gapCount, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateGapCount(id: String, gapCount: Int, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chain: KnowledgeFragmentChainEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chains: List<KnowledgeFragmentChainEntity>)

    @Update
    suspend fun update(chain: KnowledgeFragmentChainEntity)
}
