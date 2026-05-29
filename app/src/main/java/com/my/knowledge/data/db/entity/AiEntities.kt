package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_conversation")
data class AiConversationEntity(
    @PrimaryKey val id: String,
    val scopeType: String, // knowledge_item, knowledge_base, global
    val scopeId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?
)

@Entity(tableName = "ai_message")
data class AiMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String, // user, assistant, system
    val content: String,
    val citationJson: String,
    val createdAt: Long
)
