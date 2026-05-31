package com.my.knowledge.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.my.knowledge.data.db.entity.SourceManifestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceManifestDao {
    @Query("SELECT * FROM source_manifest WHERE ownerType = :ownerType AND ownerId = :ownerId ORDER BY createdAt DESC")
    fun observeByOwner(ownerType: String, ownerId: String): Flow<List<SourceManifestEntity>>

    @Query("SELECT * FROM source_manifest WHERE contentHash = :contentHash AND status != 'deleted' ORDER BY createdAt ASC LIMIT 1")
    suspend fun getFirstByHash(contentHash: String): SourceManifestEntity?

    @Query("SELECT * FROM source_manifest WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SourceManifestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SourceManifestEntity)

    @Update
    suspend fun update(source: SourceManifestEntity)

    @Query("UPDATE source_manifest SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)
}
