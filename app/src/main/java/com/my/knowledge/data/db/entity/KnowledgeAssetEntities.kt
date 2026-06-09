package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_fragment",
    indices = [
        Index("itemId"),
        Index("knowledgeBaseId"),
        Index("sourceManifestId"),
        Index("sourceId"),
        Index("parsedContentId"),
        Index("knowledgeItemId"),
        Index("chainId")
    ]
)
data class KnowledgeFragmentEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val knowledgeBaseId: String,
    val content: String,
    val summary: String?,
    val tagsJson: String,
    val sourceRef: String?,
    val sourceManifestId: String?,
    val startOffset: Int,
    val endOffset: Int,
    val createdAt: Long,
    val sourceId: String? = null,
    val parsedContentId: String? = null,
    val knowledgeItemId: String? = null,
    val orderIndex: Int = 0,
    val heading: String? = null,
    val tokenCount: Int = 0,
    val embeddingId: String? = null,
    /** FRAG-1: 关联到 fragment chain。v1 简化: chainId == threadId。 */
    val chainId: String? = null
)

@Entity(
    tableName = "processing_task_log",
    indices = [Index("taskId"), Index("targetType", "targetId")]
)
data class ProcessingTaskLogEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val targetType: String,
    val targetId: String,
    val stage: String,
    val status: String,
    val message: String,
    val createdAt: Long
)

@Entity(
    tableName = "ask_citation",
    indices = [
        Index("messageId"),
        Index("itemId"),
        Index("fragmentId"),
        Index("sourceKnowledgeBaseId")
    ]
)
data class AskCitationEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val itemId: String?,
    val fragmentId: String?,
    val quote: String,
    val label: String,
    val createdAt: Long,
    // v13: 来源 KB 标识 — CitationRow 显示「知识库名 · 条目标题」用
    // null = 老数据/已删除 KB, UI 友好降级显示「(已删除)」
    val sourceKnowledgeBaseId: String? = null,
    val sourceKnowledgeBaseName: String? = null
) {
    companion object {
        const val LABEL_SOURCE = "来自原文"
        const val LABEL_INFERENCE = "AI推理"
        const val LABEL_INSUFFICIENT = "信息不足"
        // v13: 共现 tag 关系图扩展的引用,UI 灰蓝色区分于 LABEL_SOURCE
        const val LABEL_RELATED = "相关"
    }
}
