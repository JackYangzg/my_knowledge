package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val knowledgeRepository: KnowledgeRepository,
    private val fileStore: LocalFileStore
) : ViewModel() {
    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus

    fun exportMarkdownBackup() {
        viewModelScope.launch {
            try {
                val content = knowledgeRepository.exportMarkdownBundle()
                val file = fileStore.writeBackup("my_knowledge_${System.currentTimeMillis()}.md", content)
                _exportStatus.value = "已导出到 ${file.absolutePath}"
            } catch (e: Exception) {
                _exportStatus.value = "导出失败：${e.message ?: "未知错误"}"
            }
        }
    }
}
