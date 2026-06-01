package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "processing_task",
    indices = [Index("targetType"), Index("targetId"), Index("status")]
)
data class ProcessingTaskEntity(
    @PrimaryKey val id: String,
    val targetType: String,
    val targetId: String,
    val taskType: String, // parse_markdown, ocr_image, etc.
    val status: String, // pending, running, success, failed, etc.
    val priority: Int,
    val dependsOnTaskIdsJson: String?,
    val retryCount: Int,
    val maxRetry: Int,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long?,
    val sourceId: String? = null,
    val itemId: String? = null,
    val progress: Int = 0,
    val currentStep: String? = null,
    val inputJson: String = "{}",
    val outputJson: String? = null,
    val startedAt: Long? = null
)
