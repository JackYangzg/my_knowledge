package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_item",
    indices = [
        Index("knowledgeBaseId"),
        Index("status"),
        Index("sourceType"),
        Index("contentHash")
    ]
)
data class KnowledgeItemEntity(
    @PrimaryKey val id: String,
    val knowledgeBaseId: String,
    val title: String,
    val contentMarkdown: String,
    val excerpt: String,
    val sourceType: String,
    val status: String, // draft, unfiled, processing, processed, archived, need_review, failed, deleted
    val contentHash: String,
    val summary: String?,
    val tagsJson: String,
    val rawNoteId: String?,
    val importance: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val processedAt: Long?,
    val deletedAt: Long?
)
