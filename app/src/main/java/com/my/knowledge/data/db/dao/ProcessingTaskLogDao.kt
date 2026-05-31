package com.my.knowledge.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.my.knowledge.data.db.entity.ProcessingTaskLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessingTaskLogDao {
    @Query("SELECT * FROM processing_task_log WHERE targetType = :targetType AND targetId = :targetId ORDER BY createdAt DESC")
    fun observeByTarget(targetType: String, targetId: String): Flow<List<ProcessingTaskLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ProcessingTaskLogEntity)

    @Query("DELETE FROM processing_task_log WHERE targetType = :targetType AND targetId = :targetId")
    suspend fun deleteByTarget(targetType: String, targetId: String)
}
