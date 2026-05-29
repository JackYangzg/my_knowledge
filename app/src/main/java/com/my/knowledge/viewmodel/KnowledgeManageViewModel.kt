package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.launch

class KnowledgeManageViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {
    fun createKnowledgeBase(name: String, description: String?) {
        viewModelScope.launch {
            knowledgeRepository.createBase(name, description)
        }
    }
}
