package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_embedding",
    indices = [Index("itemId"), Index("fragmentId"), Index("knowledgeBaseId")]
)
data class KnowledgeEmbeddingEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val fragmentId: String?,
    val knowledgeBaseId: String,
    val embeddingJson: String,
    val model: String,
    val dimensions: Int,
    val contentHash: String,
    val updatedAt: Long
)

@Entity(
    tableName = "knowledge_entity",
    indices = [Index("knowledgeBaseId"), Index("name"), Index("type")]
)
data class KnowledgeEntityEntity(
    @PrimaryKey val id: String,
    val knowledgeBaseId: String,
    val name: String,
    val type: String,
    val aliasesJson: String,
    val sourceItemIdsJson: String,
    val weight: Float,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "knowledge_relation",
    indices = [Index("knowledgeBaseId"), Index("fromEntityId"), Index("toEntityId"), Index("relationType")]
)
data class KnowledgeRelationEntity(
    @PrimaryKey val id: String,
    val knowledgeBaseId: String,
    val fromEntityId: String,
    val toEntityId: String,
    val relationType: String,
    val evidenceItemIdsJson: String,
    val confidence: Float,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "knowledge_community",
    indices = [Index("knowledgeBaseId")]
)
data class KnowledgeCommunityEntity(
    @PrimaryKey val id: String,
    val knowledgeBaseId: String,
    val name: String,
    val entityIdsJson: String,
    val summary: String,
    val createdAt: Long,
    val updatedAt: Long
)
