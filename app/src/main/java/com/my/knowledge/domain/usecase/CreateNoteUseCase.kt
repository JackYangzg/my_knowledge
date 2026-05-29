package com.my.knowledge.domain.usecase

import com.my.knowledge.data.db.entity.NoteEntity
import com.my.knowledge.domain.repository.NoteRepository

class CreateNoteUseCase(private val noteRepository: NoteRepository) {
    suspend operator fun invoke(knowledgeBaseId: String = "unfiled"): NoteEntity {
        return noteRepository.createNote(knowledgeBaseId)
    }
}
