package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.ArchiveRecommendationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArchiveRecommendationDao {
    @Query("SELECT * FROM archive_recommendation WHERE itemId = :itemId")
    suspend fun getByItemId(itemId: String): ArchiveRecommendationEntity?

    @Query("SELECT * FROM archive_recommendation WHERE status = 'pending' ORDER BY confidence DESC, createdAt ASC")
    fun observePending(): Flow<List<ArchiveRecommendationEntity>>

    @Query("SELECT COUNT(*) FROM archive_recommendation WHERE status = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recommendation: ArchiveRecommendationEntity)

    @Update
    suspend fun update(recommendation: ArchiveRecommendationEntity)

    @Query("UPDATE archive_recommendation SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)

    @Query("DELETE FROM archive_recommendation WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM archive_recommendation WHERE itemId = :itemId")
    suspend fun deleteByItemId(itemId: String)
}