package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * PERF-7: FTS4 mirror of [KnowledgeFragmentEntity]. The
 * `searchFragments*` queries in [com.my.knowledge.data.db.dao.SearchDao]
 * used to fall back to `LIKE '%q%'` substring scans because
 * fragments had no FTS index — which means a 3-token search
 * over a 50k-fragment library did a full table scan with three
 * wildcards per row. With this FTS4 table, the same query
 * becomes a tokenized MATCH over `content` / `summary` /
 * `tagsJson` and runs in O(log n).
 *
 * Mirrors [KnowledgeItemFts] shape: contentEntity keeps Room's
 * "single source of truth" model (the FTS table is regenerated
 * by sync triggers defined in MIGRATION_9_10 whenever the
 * underlying `knowledge_fragment` row is inserted / updated /
 * deleted), tokenizer matches the item FTS so phrase queries
 * work the same way.
 */
@Fts4(
    contentEntity = KnowledgeFragmentEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61
)
@Entity(tableName = "knowledge_fragment_fts")
data class KnowledgeFragmentFts(
    val content: String,
    val summary: String?,
    val tagsJson: String,
)
