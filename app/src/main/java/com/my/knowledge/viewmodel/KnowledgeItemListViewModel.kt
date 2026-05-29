package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeItemListViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {

    private val _kbId = MutableStateFlow<String?>(null)
    
    val items: StateFlow<List<KnowledgeItemEntity>> = _kbId
        .filterNotNull()
        .flatMapLatest { id ->
            knowledgeRepository.observeItemsByKb(id, 100, 0)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setKnowledgeBaseId(id: String) {
        _kbId.value = id
    }
}
