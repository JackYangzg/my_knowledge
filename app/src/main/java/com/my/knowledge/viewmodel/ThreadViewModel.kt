package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import com.my.knowledge.domain.repository.KnowledgeRepository

class ThreadViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {
    // Logic for knowledge pulse/thread evolution
}
