package com.my.knowledge.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import com.my.knowledge.domain.usecase.ImportResult
import com.my.knowledge.domain.usecase.ImportSourceUseCase
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class KnowledgeHomeViewModel(
    private val knowledgeRepository: KnowledgeRepository,
    private val importSourceUseCase: ImportSourceUseCase
) : ViewModel() {

    val knowledgeBases: StateFlow<List<KnowledgeBaseEntity>> = knowledgeRepository.observeAllBases()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * One-shot import outcome stream. The UI collects this in
     * `KnowledgeScreen` and shows a Toast when the import was a
     * duplicate (the "已导入" prompt the user asked for). Replay = 0
     * + DROP_OLDEST keeps the buffer tiny and never re-shows a stale
     * toast on configuration change.
     */
    private val _importResults = MutableSharedFlow<ImportResult>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val importResults: SharedFlow<ImportResult> = _importResults.asSharedFlow()

    init {
        ensureDefaultBasesExist()
    }

    private fun ensureDefaultBasesExist() {
        viewModelScope.launch {
            knowledgeRepository.ensureDefaultBases()
        }
    }

    fun importFile(name: String, type: String, content: String, targetLibrary: String) {
        viewModelScope.launch {
            knowledgeRepository.ensureDefaultBases()
            val bases = knowledgeRepository.observeAllBases().firstOrNull().orEmpty()
            val targetBase = bases.find { it.name == targetLibrary }
                ?: bases.find { it.type == "inspiration" }
                ?: bases.find { it.type == "unfiled" }
            val result = importSourceUseCase.importText(
                title = name,
                text = content,
                targetKbId = targetBase?.id,
                importFrom = "file_picker",
                folderHint = targetLibrary
            )
            _importResults.emit(result)
        }
    }

    fun importUri(uri: Uri, displayName: String, mimeType: String?, sourceType: String, targetLibrary: String) {
        viewModelScope.launch {
            knowledgeRepository.ensureDefaultBases()
            val bases = knowledgeRepository.observeAllBases().firstOrNull().orEmpty()
            val targetBase = bases.find { it.name == targetLibrary }
                ?: bases.find { it.type == "inspiration" }
                ?: bases.find { it.type == "unfiled" }
            val result = importSourceUseCase.importUri(
                uri = uri,
                displayName = displayName,
                mimeType = mimeType,
                sourceType = sourceType,
                targetKbId = targetBase?.id,
                importFrom = "file_picker",
                folderHint = targetLibrary
            )
            _importResults.emit(result)
        }
    }
}
