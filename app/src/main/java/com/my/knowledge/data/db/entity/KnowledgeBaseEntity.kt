package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "knowledge_base")
data class KnowledgeBaseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val iconText: String,
    val type: String, // unfiled, normal, system
    val isSystem: Boolean,
    val allowDelete: Boolean,
    val itemCount: Int,
    val sortOrder: Int,
    val threadStatus: String,
    val gapStatus: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?
)
