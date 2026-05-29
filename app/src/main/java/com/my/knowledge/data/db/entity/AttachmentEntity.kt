package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachment",
    indices = [Index("ownerType"), Index("ownerId")]
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val ownerType: String, // note, knowledge_item, ai_answer
    val ownerId: String,
    val fileName: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val markdownRef: String,
    val contentHash: String?,
    val ocrText: String?,
    val parsedText: String?,
    val transcribedText: String?,
    val createdAt: Long,
    val deletedAt: Long?
)
