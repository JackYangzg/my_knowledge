package com.my.knowledge.data.db.entity

/**
 * PERF-7 / shadow class. See [KnowledgeItemFts] for the full
 * rationale — this entity was removed from Room's
 * `createAllTables` for the same trigger-vs-source-table
 * creation-order reason.
 *
 * The actual `knowledge_fragment_fts` FTS4 virtual table is
 * created manually in
 * [com.my.knowledge.data.db.AppDatabase.rebuildFtsWithDiacritics]
 * (called from
 * [com.my.knowledge.data.db.AppDatabase.FtsDiacriticsCallback.onCreate]
 * and
 * [com.my.knowledge.data.db.AppDatabase.MIGRATION_10_11])
 * after all entity tables exist, so the four sync triggers
 * can resolve `knowledge_fragment` at `CREATE TRIGGER` time.
 */
data class KnowledgeFragmentFts(
    val content: String,
    val summary: String?,
    val tagsJson: String,
) {
    companion object {
        const val TABLE_NAME = "knowledge_fragment_fts"
    }
}
