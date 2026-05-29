package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachment WHERE ownerType = :ownerType AND ownerId = :ownerId AND deletedAt IS NULL")
    fun observeByOwner(ownerType: String, ownerId: String): Flow<List<AttachmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentEntity)

    @Query("UPDATE attachment SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
