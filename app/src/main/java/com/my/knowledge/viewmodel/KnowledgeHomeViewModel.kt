package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import com.my.knowledge.ui.KnowledgeManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class KnowledgeHomeViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {

    val knowledgeBases: StateFlow<List<KnowledgeBaseEntity>> = knowledgeRepository.observeAllBases()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        ensureUnfiledExists()
    }

    private fun ensureUnfiledExists() {
        viewModelScope.launch {
            val bases = knowledgeRepository.observeAllBases().first()
            if (bases.none { it.type == "unfiled" }) {
                knowledgeRepository.createBase("未归类", "默认知识存放处")
            }
        }
    }

    fun importFile(name: String, type: String, content: String, targetLibrary: String) {
        // Bridging to KnowledgeManager for MVP, in real app this would be a UseCase
        KnowledgeManager.importAndAnalyze(name, type, content, targetLibrary)
    }
}
