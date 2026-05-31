package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KnowledgeItemDetailViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {

    private val _item = MutableStateFlow<KnowledgeItemEntity?>(null)
    val item: StateFlow<KnowledgeItemEntity?> = _item.asStateFlow()

    fun loadItem(itemId: String) {
        viewModelScope.launch {
            _item.value = knowledgeRepository.getItemById(itemId)
        }
    }
}
