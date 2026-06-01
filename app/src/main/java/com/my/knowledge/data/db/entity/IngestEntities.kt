package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "source_document",
    indices = [Index("sha256"), Index("status"), Index("sourceType")]
)
data class SourceDocumentEntity(
    @PrimaryKey val id: String,
    val sourceType: String,
    val title: String,
    val originalUri: String?,
    val localPath: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val sha256: String,
    val importFrom: String?,
    val folderHint: String?,
    val status: String,
    val errorMessage: String?,
    val targetKnowledgeBaseId: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val STATUS_IMPORTED = "imported"
        const val STATUS_PARSING = "parsing"
        const val STATUS_PARSED = "parsed"
        const val STATUS_ANALYZING = "analyzing"
        const val STATUS_GENERATED = "generated"
        const val STATUS_FAILED = "failed"
        const val STATUS_DELETED = "deleted"
    }
}

@Entity(
    tableName = "parsed_content",
    indices = [Index("sourceId"), Index("parseHash")]
)
data class ParsedContentEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val parserType: String,
    val markdown: String,
    val plainText: String,
    val parseHash: String,
    val metadataJson: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "analysis_result",
    indices = [Index("sourceId"), Index("parsedContentId"), Index("analysisHash")]
)
data class AnalysisResultEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val parsedContentId: String,
    val summary: String,
    val tagsJson: String,
    val entitiesJson: String,
    val conceptsJson: String,
    val relationsJson: String,
    val claimsJson: String,
    val gapsJson: String,
    val archiveRecommendationJson: String,
    val confidence: Float,
    val modelName: String?,
    val promptVersion: String,
    val analysisHash: String,
    val createdAt: Long
)

@Entity(
    tableName = "review_item",
    indices = [Index("sourceId"), Index("itemId"), Index("status"), Index("type")]
)
data class ReviewItemEntity(
    @PrimaryKey val id: String,
    val sourceId: String?,
    val itemId: String?,
    val type: String,
    val title: String,
    val description: String,
    val payloadJson: String,
    val suggestedActionsJson: String,
    val status: String,
    val createdAt: Long,
    val resolvedAt: Long?
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_SKIPPED = "skipped"
    }
}
