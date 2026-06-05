package com.my.knowledge.data.db.entity

/**
 * PERF-7 / shadow class. The actual `knowledge_item_fts` FTS4
 * virtual table is created manually in
 * [com.my.knowledge.data.db.AppDatabase.rebuildFtsWithDiacritics]
 * (called from
 * [com.my.knowledge.data.db.AppDatabase.FtsDiacriticsCallback.onCreate]
 * for fresh installs and from
 * [com.my.knowledge.data.db.AppDatabase.MIGRATION_10_11] for
 * v10→v11 upgrades), not by Room.
 *
 * Why this isn't a Room `@Entity` anymore: when Room saw
 * `KnowledgeItemFts` as a managed entity with `@Fts4(contentEntity=...)`,
 * it emitted the FTS virtual-table DDL plus the four sync triggers
 * inside `AppDatabase_Impl.createAllTables` *before* it had
 * created the source table `knowledge_item` (and likewise for
 * `knowledge_fragment` and `knowledge_fragment_fts`). SQLite
 * resolves trigger `BEFORE UPDATE ON knowledge_fragment` at
 * `CREATE TRIGGER` time and refuses if the table doesn't yet
 * exist — that's the "no such table: main.knowledge_fragment"
 * crash at AppDatabase_Impl.java:129. Moving the FTS tables out
 * of Room's auto-DDL puts creation order under our control: the
 * regular entity tables come first, then
 * `FtsDiacriticsCallback.onCreate` / `MIGRATION_10_11` runs the
 * FTS DDL once everything else is in place.
 *
 * The class survives (without the `@Entity` / `@Fts4` annotations)
 * so existing call sites that import `KnowledgeItemFts` continue
 * to compile. The table name constant below is the single source
 * of truth for the FTS table name and must stay in lock-step
 * with the manual DDL in `AppDatabase`.
 */
data class KnowledgeItemFts(
    val title: String,
    val contentMarkdown: String,
    val summary: String?,
) {
    companion object {
        const val TABLE_NAME = "knowledge_item_fts"
    }
}
