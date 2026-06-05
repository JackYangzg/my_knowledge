package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * FRAG-1: Fragment chain is the aggregation unit behind a knowledge thread.
 *
 * Per P11, v1 enforces a 1:1 mapping between a chain and a thread — the
 * chain's primary key reuses the thread's id. UI filters / sorts / 4-tab
 * status semantics all key off this table, not off `knowledge_item.status`.
 */
@Entity(
    tableName = "knowledge_fragment_chain",
    indices = [
        Index(value = ["knowledgeBaseId", "status"]),
        Index(value = ["threadId"], unique = true),
    ],
)
data class KnowledgeFragmentChainEntity(
    @PrimaryKey val id: String,
    val knowledgeBaseId: String,
    val threadId: String,
    val title: String,
    /** Cached snapshot of `knowledge_thread.description`; refreshed on every gap write. */
    val goalSummary: String,
    val confidence: Float,
    val entityCount: Int,
    val sourceCount: Int,
    val gapCount: Int,
    /** LifecycleStatus.name — see FRAG-1 design doc. */
    val status: String,
    /** Links to the distilled wiki_synthesis item, if any. */
    val distilledItemId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
