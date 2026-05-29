package com.my.knowledge.domain.usecase

import com.my.knowledge.domain.repository.NoteRepository

class AutoSaveNoteUseCase(private val noteRepository: NoteRepository) {
    suspend operator fun invoke(id: String, content: String) {
        noteRepository.saveNote(id, content)
    }
}
