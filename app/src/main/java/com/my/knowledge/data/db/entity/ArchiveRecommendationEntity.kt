package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "archive_recommendation")
data class ArchiveRecommendationEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val recommendedKnowledgeBaseId: String?,
    val recommendedKnowledgeBaseName: String?,
    val confidence: Float,
    val reason: String,
    val alternativeJson: String,
    val suggestCreateNewBase: Boolean,
    val status: String, // pending, accepted, rejected, etc.
    val createdAt: Long,
    val updatedAt: Long
)
