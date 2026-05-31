package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_fragment",
    indices = [
        Index("itemId"),
        Index("knowledgeBaseId"),
        Index("sourceManifestId")
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
    val createdAt: Long
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
    indices = [Index("messageId"), Index("itemId"), Index("fragmentId")]
)
data class AskCitationEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val itemId: String?,
    val fragmentId: String?,
    val quote: String,
    val label: String,
    val createdAt: Long
) {
    companion object {
        const val LABEL_SOURCE = "来自原文"
        const val LABEL_INFERENCE = "AI推理"
        const val LABEL_INSUFFICIENT = "信息不足"
    }
}
