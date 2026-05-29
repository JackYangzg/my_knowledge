package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "note")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val markdownPath: String,
    val knowledgeBaseId: String,
    val status: String,
    val autoSaveStatus: String, // idle, saving, saved, failed, conflict, recovered
    val contentHash: String?,
    val isCurrentDraft: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?
)
