package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI Conversation entity
 * scopeType: knowledge_item, knowledge_base, thread, global
 */
@Entity(
    tableName = "ai_conversation",
    indices = [Index("scopeType"), Index("scopeId")]
)
data class AiConversationEntity(
    @PrimaryKey val id: String,
    val scopeType: String, // knowledge_item, knowledge_base, thread, global
    val scopeId: String,   // Corresponding ID based on scopeType
    val title: String,
    val isLocalOnly: Boolean = true, // P0: Local-first boundary
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?
)

/**
 * AI Message entity with credibility markers
 * P0: Must distinguish [来自原文] and [AI推理]
 */
@Entity(
    tableName = "ai_message",
    indices = [Index("conversationId")]
)
data class AiMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String, // user, assistant, system
    val content: String,
    val contentType: String = "general", // general, original_quote, ai_inference, insufficient_info, saved_knowledge
    val citationJson: String = "[]",     // JSON array of source references
    val sourceItemIdsJson: String = "[]", // Knowledge items used as context
    val savedAsKnowledgeItemId: String? = null, // If user saved this as new knowledge
    val createdAt: Long
)

/**
 * Citation source reference
 */
data class CitationSource(
    val itemId: String,
    val itemTitle: String,
    val fragment: String,
    val confidence: Float = 1.0f
)