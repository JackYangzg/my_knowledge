package com.my.knowledge.domain.repository

import com.my.knowledge.data.db.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

interface NoteRepository {
    fun observeCurrentDraft(): Flow<NoteEntity?>
    suspend fun createNote(knowledgeBaseId: String): NoteEntity
    suspend fun saveNote(id: String, content: String, title: String? = null)
    suspend fun readNoteContent(id: String): String
    suspend fun getNoteById(id: String): NoteEntity?
}
