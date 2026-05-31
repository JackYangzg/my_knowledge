package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class KnowledgeHomeViewModel(
    private val knowledgeRepository: KnowledgeRepository,
    private val processingTaskScheduler: ProcessingTaskScheduler
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
            val item = knowledgeRepository.createUnfiledItemFromNote(
                noteId = null,
                title = name,
                content = content,
                sourceType = type
            )
            processingTaskScheduler.scheduleFullPipeline(item.id)
        }
    }
}
