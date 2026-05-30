package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

@Fts4(
    contentEntity = KnowledgeItemEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61
)
@Entity(tableName = "knowledge_item_fts")
data class KnowledgeItemFts(
    val title: String,
    val contentMarkdown: String,
    val summary: String?
)
