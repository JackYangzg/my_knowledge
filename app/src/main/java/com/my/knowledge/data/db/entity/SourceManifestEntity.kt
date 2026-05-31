package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "source_manifest",
    indices = [
        Index("contentHash"),
        Index("ownerType", "ownerId"),
        Index("status")
    ]
)
data class SourceManifestEntity(
    @PrimaryKey val id: String,
    val sourceUri: String?,
    val sourceType: String,
    val localPath: String?,
    val contentHash: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val status: String,
    val ownerType: String,
    val ownerId: String,
    val duplicateOfSourceId: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val STATUS_NEW = "new"
        const val STATUS_CHANGED = "changed"
        const val STATUS_DUPLICATED = "duplicated"
        const val STATUS_ARCHIVED = "archived"
        const val STATUS_DELETED = "deleted"
    }
}
