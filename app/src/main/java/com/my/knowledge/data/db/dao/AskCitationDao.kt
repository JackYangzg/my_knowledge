package com.my.knowledge.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.my.knowledge.data.db.entity.AskCitationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AskCitationDao {
    @Query("SELECT * FROM ask_citation WHERE messageId = :messageId ORDER BY createdAt ASC")
    fun observeByMessage(messageId: String): Flow<List<AskCitationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(citations: List<AskCitationEntity>)

    @Query("DELETE FROM ask_citation WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: String)
}
