package com.my.knowledge.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * FRAG-1: Structured representation of a single gap detected on a chain.
 *
 * Eight [gapType] values are 1:1 with the eight rules in
 * `ThreadEvolutionRunner.detectGaps` (see design_doc/FRAG-1 §1.6). The
 * backfill in MIGRATION_11_12 parses the legacy `knowledge_thread.gapsJson`
 * string list and materialises one row per gap, with the type derived by
 * substring match. After v12 the structured table is the only source of
 * truth for gap UI; the legacy `gapsJson` field is preserved but no longer
 * written.
 *
 * P12 — [resolvedByUserText] records the user's verbatim claim when they
 * assert a gap is closed via natural-language reanalysis. The UI shows a
 * "user-asserted, not yet verified by new ingest" badge in that case.
 *
 * P13 — [resolvedByItemId] records the item whose ingest-time content
 * covered this gap (best-effort LLM match, never a hard guarantee).
 */
@Entity(
    tableName = "knowledge_fragment_gap",
    indices = [
        Index(value = ["chainId", "resolved"]),
        Index(value = ["gapType"]),
    ],
)
data class KnowledgeFragmentGapEntity(
    @PrimaryKey val id: String,
    val chainId: String,
    /** GapType.name — 8 values, see FRAG-1 design §5.2. */
    val gapType: String,
    /** Priority.name — HIGH / MEDIUM / LOW. */
    val priority: String,
    val description: String,
    val suggestion: String,
    val resolved: Boolean = false,
    val resolvedByItemId: String? = null,
    val resolvedByUserText: String? = null,
    val resolvedAt: Long? = null,
    val createdAt: Long,
)
