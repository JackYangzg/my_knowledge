package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.domain.repository.KnowledgeRepository
import com.my.knowledge.domain.repository.ProfileStats
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val knowledgeRepository: KnowledgeRepository,
    private val fileStore: LocalFileStore
) : ViewModel() {
    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus

    val unfiledWorkCount: StateFlow<Int> = knowledgeRepository.observeUnfiledWorkCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * 处理中任务数（pending + running）。驱动 ProfileScreen 上"日志中心"入口
     * 的描述文案。旧的描述只显示"X 条未归档"过于单薄,日志中心页面本身
     * 已经把计数拆成 处理中 / 失败 / 待确认 三张卡片,这里跟它对齐。
     */
    val processingTaskCount: StateFlow<Int> = knowledgeRepository.observeActiveTaskCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val failedTaskCount: StateFlow<Int> = knowledgeRepository.observeFailedTaskCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val pendingRecommendationCount: StateFlow<Int> = knowledgeRepository.observePendingRecommendations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        .let { recommendations ->
            kotlinx.coroutines.flow.combine(recommendations, knowledgeRepository.observePendingReviews()) { recs, reviews ->
                recs.size + reviews.size
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
        }

    val profileStats: StateFlow<ProfileStats> = knowledgeRepository.observeProfileStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfileStats(0, 0, 0, 0))

    val processingSummaries: StateFlow<List<KnowledgeBaseProcessingSummary>> =
        combine(
            knowledgeRepository.observeAllBases(),
            knowledgeRepository.observeAllKnowledgeEntities(),
            knowledgeRepository.observeAllKnowledgeRelations(),
            knowledgeRepository.observeAllKnowledgeCommunities()
        ) { bases, entities, relations, communities ->
            val entitiesByKb = entities.groupBy { it.knowledgeBaseId }
            val relationsByKb = relations.groupBy { it.knowledgeBaseId }
            val communitiesByKb = communities.groupBy { it.knowledgeBaseId }
            bases.map { base ->
                val kbEntities = entitiesByKb[base.id].orEmpty()
                KnowledgeBaseProcessingSummary(
                    knowledgeBaseId = base.id,
                    knowledgeBaseName = base.name,
                    itemCount = base.itemCount,
                    entityCount = kbEntities.count { it.type != "concept" },
                    conceptCount = kbEntities.count { it.type == "concept" },
                    relationCount = relationsByKb[base.id].orEmpty().size,
                    communityCount = communitiesByKb[base.id].orEmpty().size,
                    topTerms = kbEntities.sortedByDescending { it.weight }.take(5).map { it.name }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

data class KnowledgeBaseProcessingSummary(
    val knowledgeBaseId: String,
    val knowledgeBaseName: String,
    val itemCount: Int,
    val entityCount: Int,
    val conceptCount: Int,
    val relationCount: Int,
    val communityCount: Int,
    val topTerms: List<String>
)
