package com.my.knowledge.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.NoteEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import com.my.knowledge.domain.usecase.AutoSaveNoteUseCase
import com.my.knowledge.domain.usecase.CreateNoteUseCase
import com.my.knowledge.domain.repository.NoteRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

@OptIn(FlowPreview::class)
class NoteEditorViewModel(
    private val createNoteUseCase: CreateNoteUseCase,
    private val autoSaveNoteUseCase: AutoSaveNoteUseCase,
    private val noteRepository: NoteRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val processingTaskScheduler: ProcessingTaskScheduler
) : ViewModel() {

    var currentNote by mutableStateOf<NoteEntity?>(null)
        private set

    var title by mutableStateOf("")
    var content by mutableStateOf("")
    var mode by mutableStateOf("preview") // edit, preview
    private var savedKnowledgeItemId: String? = null
    
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
                content = noteRepository.readNoteContent(draft.id)
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
                        try {
                            _saveStatus.value = "saving"
                            autoSaveNoteUseCase(note.id, c)
                            _saveStatus.value = "saved"
                        } catch (_: Exception) {
                            _saveStatus.value = "save_failed"
                        }
                    }
                }
        }
    }

    fun toggleMode() {
        mode = if (mode == "edit") "preview" else "edit"
    }

    fun updateMode(nextMode: String) {
        if (nextMode == "edit" || nextMode == "preview") {
            mode = nextMode
        }
    }

    val knowledgeBaseNames: StateFlow<List<String>> = knowledgeRepository.observeAllBases()
        .map { bases -> bases.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createNewNote() {
        viewModelScope.launch {
            currentNote = createNoteUseCase()
            title = ""
            content = ""
            mode = "preview"
            savedKnowledgeItemId = null
        }
    }

    suspend fun saveToKnowledgeBase(kbName: String): String {
        currentNote?.let { autoSaveNoteUseCase(it.id, content) }
        knowledgeRepository.ensureDefaultBases()
        val bases = knowledgeRepository.observeAllBases().first()
        val targetBase = bases.find { it.name == kbName }
            ?: bases.find { it.type == "inspiration" }
            ?: bases.find { it.type == "unfiled" }
        val targetName = targetBase?.name ?: "灵感空间"

        val savedTitle = title.trim().ifBlank { "灵感 ${System.currentTimeMillis()}" }
        val savedContent = content.trim()
        if (savedContent.isEmpty() && title.trim().isEmpty()) return targetName

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(savedContent.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val existingId = savedKnowledgeItemId
        if (existingId != null) {
            val existing = knowledgeRepository.getItemById(existingId)
            if (existing != null) {
                knowledgeRepository.updateItem(existing.copy(
                    title = savedTitle,
                    contentMarkdown = savedContent,
                    excerpt = savedContent.take(100),
                    contentHash = hash,
                    updatedAt = System.currentTimeMillis()
                ))
                return targetName
            }
        }

        val item = knowledgeRepository.createUnfiledItemFromNote(
            noteId = currentNote?.id,
            title = savedTitle,
            content = savedContent,
            sourceType = "灵感记录"
        )
        if (targetBase != null && item.knowledgeBaseId != targetBase.id) {
            knowledgeRepository.moveItemToBase(item.id, targetBase.id)
        }
        processingTaskScheduler.scheduleFullPipeline(item.id)
        savedKnowledgeItemId = item.id
        return targetName
    }
}
