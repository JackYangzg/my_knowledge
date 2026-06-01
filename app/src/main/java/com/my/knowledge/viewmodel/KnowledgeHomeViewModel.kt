package com.my.knowledge.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import com.my.knowledge.domain.usecase.ImportSourceUseCase
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
            importSourceUseCase.importText(
                title = name,
                text = content,
                targetKbId = targetBase?.id,
                importFrom = "file_picker",
                folderHint = targetLibrary
            )
        }
    }

    fun importUri(uri: Uri, displayName: String, mimeType: String?, sourceType: String, targetLibrary: String) {
        viewModelScope.launch {
            knowledgeRepository.ensureDefaultBases()
            val bases = knowledgeRepository.observeAllBases().firstOrNull().orEmpty()
            val targetBase = bases.find { it.name == targetLibrary }
                ?: bases.find { it.type == "inspiration" }
                ?: bases.find { it.type == "unfiled" }
            importSourceUseCase.importUri(
                uri = uri,
                displayName = displayName,
                mimeType = mimeType,
                sourceType = sourceType,
                targetKbId = targetBase?.id,
                importFrom = "file_picker",
                folderHint = targetLibrary
            )
        }
    }
}
