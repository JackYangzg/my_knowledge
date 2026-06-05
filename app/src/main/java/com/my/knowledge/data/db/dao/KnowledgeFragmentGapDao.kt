package com.my.knowledge.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.my.knowledge.data.db.entity.KnowledgeFragmentGapEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeFragmentGapDao {
    @Query("SELECT * FROM knowledge_fragment_gap WHERE chainId = :chainId ORDER BY resolved ASC, priority ASC, createdAt ASC")
    fun observeByChain(chainId: String): Flow<List<KnowledgeFragmentGapEntity>>

    @Query("SELECT * FROM knowledge_fragment_gap WHERE chainId = :chainId AND resolved = :resolved")
    suspend fun getByChain(chainId: String, resolved: Boolean): List<KnowledgeFragmentGapEntity>

    @Query("SELECT * FROM knowledge_fragment_gap WHERE chainId = :chainId")
    suspend fun getAllByChain(chainId: String): List<KnowledgeFragmentGapEntity>

    @Query("SELECT * FROM knowledge_fragment_gap WHERE id = :id")
    suspend fun getById(id: String): KnowledgeFragmentGapEntity?

    @Query("UPDATE knowledge_fragment_gap SET resolved = 1, resolvedByItemId = :itemId, resolvedByUserText = NULL, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun markResolvedByItem(id: String, itemId: String, resolvedAt: Long)

    @Query("UPDATE knowledge_fragment_gap SET resolved = 1, resolvedByItemId = NULL, resolvedByUserText = :userText, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun markResolvedByUserText(id: String, userText: String, resolvedAt: Long)

    @Query("DELETE FROM knowledge_fragment_gap WHERE chainId = :chainId")
    suspend fun deleteByChain(chainId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(gaps: List<KnowledgeFragmentGapEntity>)

    @Query("UPDATE knowledge_fragment_gap SET resolved = 0, resolvedByItemId = NULL, resolvedByUserText = NULL, resolvedAt = NULL WHERE id = :id")
    suspend fun markUnresolved(id: String)

    /**
     * Atomically replace all gaps for a chain. Used by both
     * `NaturalLanguageGapReanalysisWorker` (P12) and the periodic
     * `FragmentGapDetector` re-scan that runs after `ThreadEvolutionRunner`.
     */
    @Transaction
    suspend fun replaceForChain(chainId: String, gaps: List<KnowledgeFragmentGapEntity>) {
        deleteByChain(chainId)
        if (gaps.isNotEmpty()) insertAll(gaps)
    }
}
