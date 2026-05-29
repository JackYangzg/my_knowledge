package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "knowledge_thread")
data class KnowledgeThreadEntity(
    @PrimaryKey val id: String,
    val knowledgeBaseId: String,
    val description: String,
    val coreQuestion: String,
    val mainlineJson: String,
    val relationsJson: String,
    val gapsJson: String,
    val nextSuggestionsJson: String,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "knowledge_thread_log")
data class KnowledgeThreadLogEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val triggerType: String,
    val triggerId: String?,
    val beforeHash: String?,
    val afterHash: String?,
    val summary: String,
    val createdAt: Long
)
