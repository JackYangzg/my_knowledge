package com.my.knowledge.data.repository

import com.my.knowledge.data.db.dao.NoteDao
import com.my.knowledge.data.db.entity.NoteEntity
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.util.*

class NoteRepositoryImpl(
    private val noteDao: NoteDao,
    private val fileStore: LocalFileStore
) : NoteRepository {
    override fun observeCurrentDraft(): Flow<NoteEntity?> = noteDao.observeCurrentDraft()

    override suspend fun createNote(knowledgeBaseId: String): NoteEntity {
        noteDao.clearCurrentDraftFlag()
        val id = UUID.randomUUID().toString()
        val note = NoteEntity(
            id = id,
            title = null,
            markdownPath = fileStore.getMarkdownFile(id).absolutePath,
            knowledgeBaseId = knowledgeBaseId,
            status = "draft",
            autoSaveStatus = "idle",
            contentHash = null,
            isCurrentDraft = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            deletedAt = null
        )
        noteDao.insert(note)
        fileStore.writeMarkdown(id, "")
        return note
    }

    override suspend fun saveNote(id: String, content: String, title: String?) {
        val note = noteDao.getById(id)
        val normalizedTitle = title?.trim()?.ifBlank { null }
        if (note != null) {
            noteDao.update(note.copy(
                title = normalizedTitle ?: note.title,
                autoSaveStatus = "saving",
                updatedAt = System.currentTimeMillis()
            ))
        }
        try {
            fileStore.writeMarkdown(id, content)
            if (note != null) {
                noteDao.update(note.copy(
                    title = normalizedTitle ?: note.title,
                    updatedAt = System.currentTimeMillis(),
                    autoSaveStatus = "saved",
                    contentHash = sha256(content)
                ))
            }
        } catch (e: Exception) {
            if (note != null) {
                noteDao.update(note.copy(
                    updatedAt = System.currentTimeMillis(),
                    autoSaveStatus = "failed"
                ))
            }
            throw e
        }
    }

    override suspend fun readNoteContent(id: String): String = fileStore.readMarkdown(id)

    override suspend fun getNoteById(id: String): NoteEntity? = noteDao.getById(id)

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
