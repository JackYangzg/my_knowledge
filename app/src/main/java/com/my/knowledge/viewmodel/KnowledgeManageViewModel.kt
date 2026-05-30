package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for Knowledge Base management
 * Includes delete with confirmation and move to unfiled option
 */
class KnowledgeManageViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {

    // P1: Delete confirmation state
    private val _deleteConfirmKb = MutableStateFlow<KnowledgeBaseEntity?>(null)
    val deleteConfirmKb: StateFlow<KnowledgeBaseEntity?> = _deleteConfirmKb.asStateFlow()

    // All knowledge bases
    val knowledgeBases: StateFlow<List<KnowledgeBaseEntity>> = 
        knowledgeRepository.observeAllBases()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Unfiled base for moving items
    private val _unfiledBase = MutableStateFlow<KnowledgeBaseEntity?>(null)
    val unfiledBase: StateFlow<KnowledgeBaseEntity?> = _unfiledBase.asStateFlow()

    init {
        viewModelScope.launch {
            _unfiledBase.value = knowledgeRepository.getUnfiledBase()
        }
    }

    fun createKnowledgeBase(name: String, description: String?) {
        viewModelScope.launch {
            knowledgeRepository.createBase(name, description)
        }
    }

    fun deleteKnowledgeBase(id: String, moveToUnfiled: Boolean) {
        viewModelScope.launch {
            knowledgeRepository.deleteBase(id, moveToUnfiled)
            _deleteConfirmKb.value = null
        }
    }

    fun showDeleteConfirmation(base: KnowledgeBaseEntity) {
        _deleteConfirmKb.value = base
    }

    fun dismissDeleteConfirmation() {
        _deleteConfirmKb.value = null
    }

    fun updateBase(base: KnowledgeBaseEntity) {
        viewModelScope.launch {
            knowledgeRepository.updateBase(base)
        }
    }
}