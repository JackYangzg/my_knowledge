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
    val status: String,
    val contentHash: String,
    val summary: String?,
    val tagsJson: String,
    val rawNoteId: String?,
    val importance: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val processedAt: Long?,
    val deletedAt: Long?
) {
    companion object {
        /** Initial draft, not yet saved as knowledge item */
        const val STATUS_DRAFT = "draft"
        /** Saved but not yet categorized */
        const val STATUS_UNFILED = "unfiled"
        /** Processing pipeline is running */
        const val STATUS_PROCESSING = "processing"
        /** Processing complete, ready for user review */
        const val STATUS_PROCESSED = "processed"
        /** Archive recommendation ready, waiting for user confirmation */
        const val STATUS_RECOMMEND_READY = "recommend_ready"
        /** User confirmed and archived into knowledge base */
        const val STATUS_ARCHIVED = "archived"
        /** Needs user review (e.g., low confidence processing) */
        const val STATUS_NEED_REVIEW = "need_review"
        /** Processing failed */
        const val STATUS_FAILED = "failed"
        /** Soft deleted */
        const val STATUS_DELETED = "deleted"
    }
}
