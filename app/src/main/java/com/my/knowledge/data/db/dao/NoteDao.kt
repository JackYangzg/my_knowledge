package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM note WHERE isCurrentDraft = 1 AND deletedAt IS NULL LIMIT 1")
    fun observeCurrentDraft(): Flow<NoteEntity?>

    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity)

    @Update
    suspend fun update(note: NoteEntity)

    @Query("UPDATE note SET isCurrentDraft = 0")
    suspend fun clearCurrentDraftFlag()
}
