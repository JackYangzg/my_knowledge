package com.my.knowledge.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.NoteEntity
import com.my.knowledge.domain.usecase.AutoSaveNoteUseCase
import com.my.knowledge.domain.usecase.CreateNoteUseCase
import com.my.knowledge.domain.repository.NoteRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class NoteEditorViewModel(
    private val createNoteUseCase: CreateNoteUseCase,
    private val autoSaveNoteUseCase: AutoSaveNoteUseCase,
    private val noteRepository: NoteRepository
) : ViewModel() {

    var currentNote by mutableStateOf<NoteEntity?>(null)
        private set

    var title by mutableStateOf("")
    var content by mutableStateOf("")
    var mode by mutableStateOf("edit") // edit, preview
    
    private val _saveStatus = MutableStateFlow("idle")
    val saveStatus: StateFlow<String> = _saveStatus

    init {
        loadOrCreateNote()
        setupAutoSave()
    }

    private fun loadOrCreateNote() {
        viewModelScope.launch {
            noteRepository.observeCurrentDraft().firstOrNull()?.let { draft ->
                currentNote = draft
                title = draft.title ?: ""
                // For a real app, content would be read from file store here
            } ?: run {
                currentNote = createNoteUseCase()
            }
        }
    }

    private fun setupAutoSave() {
        viewModelScope.launch {
            // Observe content changes and auto-save
            combine(
                snapshotFlow { title },
                snapshotFlow { content }
            ) { t, c -> t to c }
                .debounce(1000)
                .collectLatest { (_, c) ->
                    currentNote?.let { note ->
                        _saveStatus.value = "saving"
                        autoSaveNoteUseCase(note.id, c)
                        _saveStatus.value = "saved"
                    }
                }
        }
    }

    fun toggleMode() {
        mode = if (mode == "edit") "preview" else "edit"
    }

    fun createNewNote() {
        viewModelScope.launch {
            currentNote = createNoteUseCase()
            title = ""
            content = ""
            mode = "edit"
        }
    }
}
