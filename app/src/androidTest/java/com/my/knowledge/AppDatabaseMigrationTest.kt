package com.my.knowledge

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.my.knowledge.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration4To6_addsIngestTablesQueueColumnsAndItemTrace() {
        val dbName = "migration-4-6-test"
        helper.createDatabase(dbName, 4).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_item` (
                    `id` TEXT NOT NULL,
                    `knowledgeBaseId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `contentMarkdown` TEXT NOT NULL,
                    `excerpt` TEXT NOT NULL,
                    `sourceType` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `contentHash` TEXT NOT NULL,
                    `summary` TEXT,
                    `tagsJson` TEXT NOT NULL,
                    `rawNoteId` TEXT,
                    `importance` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `processedAt` INTEGER,
                    `deletedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_fragment` (
                    `id` TEXT NOT NULL,
                    `itemId` TEXT NOT NULL,
                    `knowledgeBaseId` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `summary` TEXT,
                    `tagsJson` TEXT NOT NULL,
                    `sourceRef` TEXT,
                    `sourceManifestId` TEXT,
                    `startOffset` INTEGER NOT NULL,
                    `endOffset` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `processing_task` (
                    `id` TEXT NOT NULL,
                    `targetType` TEXT NOT NULL,
                    `targetId` TEXT NOT NULL,
                    `taskType` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `priority` INTEGER NOT NULL,
                    `dependsOnTaskIdsJson` TEXT,
                    `retryCount` INTEGER NOT NULL,
                    `maxRetry` INTEGER NOT NULL,
                    `errorMessage` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `finishedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            6,
            false,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6
        )

        assertTrue(migrated.hasTable("source_document"))
        assertTrue(migrated.hasTable("parsed_content"))
        assertTrue(migrated.hasTable("analysis_result"))
        assertTrue(migrated.hasTable("review_item"))
        assertTrue(migrated.hasColumn("processing_task", "progress"))
        assertTrue(migrated.hasColumn("knowledge_fragment", "sourceId"))
        assertTrue(migrated.hasColumn("knowledge_item", "sourceId"))
        assertTrue(migrated.hasColumn("knowledge_item", "sourceTraceJson"))
        assertTrue(migrated.hasColumn("knowledge_item", "confidence"))
        assertTrue(migrated.hasColumn("knowledge_item", "archivedAt"))
        migrated.close()
    }

    @Test
    fun migration10To11_backfillsFtsOnPopulatedTables() {
        // Regression: v10→v11 rebuilds `knowledge_item_fts` and
        // `knowledge_fragment_fts` with `remove_diacritics=1`. The
        // backfill must end up with the same row count as the
        // source table — previously the migration used `OLD.col`
        // in a top-level SELECT (only valid inside a trigger body),
        // so the backfill failed silently and the FTS index was
        // left half-populated. Subsequent generation-stage
        // `INSERT INTO knowledge_item` then fired the AFTER_INSERT
        // sync trigger against a broken FTS state and surfaced as
        // "SQL logic error (OS error -2: No such file or directory)"
        // in the catch block of IngestOrchestrator.runTask.
        val dbName = "migration-10-11-test"
        helper.createDatabase(dbName, 10).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_item` (
                    `id` TEXT NOT NULL,
                    `sourceId` TEXT,
                    `knowledgeBaseId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `contentMarkdown` TEXT NOT NULL,
                    `excerpt` TEXT NOT NULL,
                    `sourceType` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `contentHash` TEXT NOT NULL,
                    `sourceTraceJson` TEXT NOT NULL,
                    `confidence` REAL NOT NULL,
                    `summary` TEXT,
                    `tagsJson` TEXT NOT NULL,
                    `rawNoteId` TEXT,
                    `importance` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `processedAt` INTEGER,
                    `archivedAt` INTEGER,
                    `deletedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_fragment` (
                    `id` TEXT NOT NULL,
                    `itemId` TEXT NOT NULL,
                    `knowledgeBaseId` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `summary` TEXT,
                    `tagsJson` TEXT NOT NULL,
                    `sourceRef` TEXT,
                    `sourceManifestId` TEXT,
                    `startOffset` INTEGER NOT NULL,
                    `endOffset` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `sourceId` TEXT,
                    `parsedContentId` TEXT,
                    `knowledgeItemId` TEXT,
                    `orderIndex` INTEGER NOT NULL,
                    `heading` TEXT,
                    `tokenCount` INTEGER NOT NULL,
                    `embeddingId` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            // Seed both tables with three rows each so the backfill
            // has real data to copy. The exact column shapes are
            // v10's; we don't go through a DAO here because the
            // test only exercises the migration path.
            for (i in 1..3) {
                execSQL(
                    "INSERT INTO `knowledge_item` " +
                        "(`id`, `knowledgeBaseId`, `title`, `contentMarkdown`, `excerpt`, `sourceType`, `status`, `contentHash`, `sourceTraceJson`, `confidence`, `tagsJson`, `importance`, `createdAt`, `updatedAt`) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        "item-$i",
                        "kb-1",
                        "Café $i — naïve $i",
                        "São Paulo $i — résumé $i",
                        "excerpt-$i",
                        "wiki_entity",
                        "archived",
                        "hash-$i",
                        "{}",
                        0.9,
                        "[]",
                        1,
                        1_700_000_000_000L + i,
                        1_700_000_000_000L + i,
                    )
                )
                execSQL(
                    "INSERT INTO `knowledge_fragment` " +
                        "(`id`, `itemId`, `knowledgeBaseId`, `content`, `tagsJson`, `startOffset`, `endOffset`, `createdAt`, `orderIndex`, `tokenCount`) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        "frag-$i",
                        "item-$i",
                        "kb-1",
                        "naïve fragment $i — São Paulo $i",
                        "[]",
                        0,
                        10,
                        1_700_000_000_000L + i,
                        i,
                        4,
                    )
                )
            }
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            11,
            false,
            AppDatabase.MIGRATION_10_11
        )

        // FTS tables must exist with the new schema and contain
        // the same number of rows we just seeded.
        assertTrue(migrated.hasTable("knowledge_item_fts"))
        assertTrue(migrated.hasTable("knowledge_fragment_fts"))
        val itemCount = migrated.countOf("knowledge_item_fts")
        val fragmentCount = migrated.countOf("knowledge_fragment_fts")
        assertEquals(
            "knowledge_item_fts backfill must copy every source row",
            3, itemCount
        )
        assertEquals(
            "knowledge_fragment_fts backfill must copy every source row",
            3, fragmentCount
        )

        // Triggers: all 4 Room sync triggers per FTS table must be
        // present, and the 3 legacy MIGRATION_9_10 fragment triggers
        // must be gone.
        for (name in listOf(
            "room_fts_content_sync_knowledge_item_fts_BEFORE_UPDATE",
            "room_fts_content_sync_knowledge_item_fts_BEFORE_DELETE",
            "room_fts_content_sync_knowledge_item_fts_AFTER_UPDATE",
            "room_fts_content_sync_knowledge_item_fts_AFTER_INSERT",
            "room_fts_content_sync_knowledge_fragment_fts_BEFORE_UPDATE",
            "room_fts_content_sync_knowledge_fragment_fts_BEFORE_DELETE",
            "room_fts_content_sync_knowledge_fragment_fts_AFTER_UPDATE",
            "room_fts_content_sync_knowledge_fragment_fts_AFTER_INSERT",
        )) {
            assertTrue("trigger $name should exist post-migration", migrated.hasTrigger(name))
        }
        for (name in listOf("knowledge_fragment_ai", "knowledge_fragment_ad", "knowledge_fragment_au")) {
            assertFalse("legacy trigger $name should have been dropped", migrated.hasTrigger(name))
        }

        // End-to-end smoke: a real `knowledge_item` insert must
        // succeed — the trigger fires, the FTS table is in a
        // consistent state, no SQLITE_INTERNAL/ENOENT is raised.
        // This is the exact path that used to fail in the
        // generation stage of the ingest pipeline.
        migrated.execSQL(
            "INSERT INTO `knowledge_item` " +
                "(`id`, `knowledgeBaseId`, `title`, `contentMarkdown`, `excerpt`, `sourceType`, `status`, `contentHash`, `sourceTraceJson`, `confidence`, `tagsJson`, `importance`, `createdAt`, `updatedAt`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                "item-post-migration",
                "kb-1",
                "post-migration row",
                "naïve content",
                "excerpt",
                "wiki_entity",
                "archived",
                "hash-post",
                "{}",
                0.9,
                "[]",
                1,
                1_700_000_000_000L,
                1_700_000_000_000L,
            )
        )
        assertEquals(
            "post-migration insert must show up in the FTS index",
            4, migrated.countOf("knowledge_item_fts")
        )

        migrated.close()
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.hasTable(name: String): Boolean {
        query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.hasTrigger(name: String): Boolean {
        query(
            "SELECT name FROM sqlite_master WHERE type='trigger' AND name=?",
            arrayOf(name)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    @Test
    fun migration11To12_addsChainAndGapTablesAndBackfillsFromThreadGaps() {
        // FRAG-1 v11 -> v12: adds starredAt/chainId columns, the new
        // `knowledge_fragment_chain` and `knowledge_fragment_gap` tables,
        // and backfills one chain + N gap rows per legacy `knowledge_thread`
        // row by parsing the legacy `gapsJson` string array.
        val dbName = "migration-11-12-test"
        helper.createDatabase(dbName, 11).apply {
            // v11 knowledge_thread — same shape as the entity.
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_thread` (
                    `id` TEXT NOT NULL,
                    `knowledgeBaseId` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `coreQuestion` TEXT NOT NULL,
                    `mainlineJson` TEXT NOT NULL,
                    `relationsJson` TEXT NOT NULL,
                    `gapsJson` TEXT NOT NULL,
                    `nextSuggestionsJson` TEXT NOT NULL,
                    `inputHash` TEXT,
                    `version` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            // v11 knowledge_item (no starredAt yet).
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_item` (
                    `id` TEXT NOT NULL,
                    `knowledgeBaseId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `contentMarkdown` TEXT NOT NULL,
                    `excerpt` TEXT NOT NULL,
                    `sourceType` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `contentHash` TEXT NOT NULL,
                    `sourceTraceJson` TEXT NOT NULL,
                    `confidence` REAL NOT NULL,
                    `summary` TEXT,
                    `tagsJson` TEXT NOT NULL,
                    `rawNoteId` TEXT,
                    `importance` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `processedAt` INTEGER,
                    `archivedAt` INTEGER,
                    `deletedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            // v11 knowledge_fragment (no chainId yet).
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_fragment` (
                    `id` TEXT NOT NULL,
                    `itemId` TEXT NOT NULL,
                    `knowledgeBaseId` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `summary` TEXT,
                    `tagsJson` TEXT NOT NULL,
                    `sourceRef` TEXT,
                    `sourceManifestId` TEXT,
                    `startOffset` INTEGER NOT NULL,
                    `endOffset` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `sourceId` TEXT,
                    `parsedContentId` TEXT,
                    `knowledgeItemId` TEXT,
                    `orderIndex` INTEGER NOT NULL,
                    `heading` TEXT,
                    `tokenCount` INTEGER NOT NULL,
                    `embeddingId` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )

            // Seed: 1 thread with 3 gap strings + 1 thread with empty gaps.
            // The 3 gap strings cover three of the 8 GapType substring rules
            // (MISSING_SYNTHESIS / NO_RELATIONS / MISSING_TAGS).
            execSQL(
                "INSERT INTO `knowledge_thread` " +
                    "(`id`, `knowledgeBaseId`, `description`, `coreQuestion`, `mainlineJson`, " +
                    " `relationsJson`, `gapsJson`, `nextSuggestionsJson`, `version`, " +
                    " `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "thread-1", "kb-1",
                    "知识库概述: example topic",
                    "what is the main question",
                    "[]", "[]",
                    "[\"缺少 index / overview / log 合成页\", \"知识之间没有形成显式引用或同主题关联\", \"超过半数知识缺少标签\"]",
                    "[]", 1,
                    1_700_000_000_000L, 1_700_000_000_000L,
                )
            )
            execSQL(
                "INSERT INTO `knowledge_thread` " +
                    "(`id`, `knowledgeBaseId`, `description`, `coreQuestion`, `mainlineJson`, " +
                    " `relationsJson`, `gapsJson`, `nextSuggestionsJson`, `version`, " +
                    " `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "thread-2", "kb-1",
                    "completed thread",
                    "no gaps",
                    "[]", "[]",
                    "[]", "[]", 1,
                    1_700_000_000_000L, 1_700_000_000_000L,
                )
            )

            // Seed at least one row in knowledge_item and knowledge_fragment
            // so post-migration smoke checks (column added) work against
            // non-empty data.
            execSQL(
                "INSERT INTO `knowledge_item` " +
                    "(`id`, `knowledgeBaseId`, `title`, `contentMarkdown`, `excerpt`, " +
                    " `sourceType`, `status`, `contentHash`, `sourceTraceJson`, `confidence`, " +
                    " `tagsJson`, `importance`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "item-1", "kb-1", "title", "body", "excerpt",
                    "wiki_entity", "archived", "hash-1", "[]", 0.9,
                    "[]", 1, 1_700_000_000_000L, 1_700_000_000_000L,
                )
            )
            execSQL(
                "INSERT INTO `knowledge_fragment` " +
                    "(`id`, `itemId`, `knowledgeBaseId`, `content`, `tagsJson`, " +
                    " `startOffset`, `endOffset`, `createdAt`, `orderIndex`, `tokenCount`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "frag-1", "item-1", "kb-1", "content", "[]",
                    0, 10, 1_700_000_000_000L, 1, 4,
                )
            )

            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            12,
            false,
            AppDatabase.MIGRATION_11_12
        )

        // New columns exist on the existing tables.
        assertTrue(migrated.hasColumn("knowledge_item", "starredAt"))
        assertTrue(migrated.hasColumn("knowledge_fragment", "chainId"))
        // New tables exist.
        assertTrue(migrated.hasTable("knowledge_fragment_chain"))
        assertTrue(migrated.hasTable("knowledge_fragment_gap"))

        // Backfill: thread-1 (3 gaps) -> 1 chain NEED_REVIEW + 3 gap rows.
        // thread-2 (0 gaps)  -> 1 chain DISTILL_READY + 0 gap rows.
        assertEquals(2, migrated.countOf("knowledge_fragment_chain"))
        assertEquals(3, migrated.countOf("knowledge_fragment_gap"))

        // Verify the chain rows carry the right status.
        val chainStatuses = mutableListOf<Pair<String, String>>()
        migrated.query("SELECT id, status FROM knowledge_fragment_chain ORDER BY id ASC").use { c ->
            while (c.moveToNext()) {
                chainStatuses.add(c.getString(0) to c.getString(1))
            }
        }
        assertEquals(
            listOf("thread-1" to "NEED_REVIEW", "thread-2" to "DISTILL_READY"),
            chainStatuses
        )

        // Verify gap rows: 3 rows all on thread-1, gapType values match
        // the 8-GapType substring classifier.
        val gapTypes = mutableListOf<String>()
        migrated.query("SELECT gapType FROM knowledge_fragment_gap ORDER BY id ASC").use { c ->
            while (c.moveToNext()) gapTypes.add(c.getString(0))
        }
        assertEquals(listOf("MISSING_SYNTHESIS", "NO_RELATIONS", "MISSING_TAGS"), gapTypes)

        migrated.close()
    }

    @Test
    fun migration12To13_addsAskCitationColumnsPreservesRows() {
        // v12 -> v13: AI 全库对话来源透明
        // - 加 sourceKnowledgeBaseId (索引) 和 sourceKnowledgeBaseName (无索引)
        //   到 ask_citation 表
        // - 老行这两列为 NULL (UI 降级显示「(已删除)」)
        // - 不 destructive migration,保留所有历史 chat 引用
        val dbName = "migration-12-13-test"
        helper.createDatabase(dbName, 12).apply {
            // v12 ask_citation shape (无 source KB cols)
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ask_citation` (
                    `id` TEXT NOT NULL,
                    `messageId` TEXT NOT NULL,
                    `itemId` TEXT,
                    `fragmentId` TEXT,
                    `quote` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ask_citation_messageId` " +
                    "ON `ask_citation` (`messageId`)"
            )
            execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ask_citation_itemId` " +
                    "ON `ask_citation` (`itemId`)"
            )
            execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ask_citation_fragmentId` " +
                    "ON `ask_citation` (`fragmentId`)"
            )

            // Seed 2 行历史引用 (没有 source KB 字段)
            execSQL(
                "INSERT INTO `ask_citation` " +
                    "(`id`, `messageId`, `itemId`, `fragmentId`, `quote`, `label`, `createdAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "cite-1", "msg-1", "item-1", null,
                    "历史引用 quote 1", "来自原文", 1_700_000_000_000L,
                )
            )
            execSQL(
                "INSERT INTO `ask_citation` " +
                    "(`id`, `messageId`, `itemId`, `fragmentId`, `quote`, `label`, `createdAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "cite-2", "msg-2", "item-2", null,
                    "历史引用 quote 2", "AI推理", 1_700_000_001_000L,
                )
            )

            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            13,
            false,
            AppDatabase.MIGRATION_12_13
        )

        // 加了 2 列
        assertTrue(migrated.hasColumn("ask_citation", "sourceKnowledgeBaseId"))
        assertTrue(migrated.hasColumn("ask_citation", "sourceKnowledgeBaseName"))

        // 加了新索引
        assertTrue(
            "sourceKnowledgeBaseId index should exist post-migration",
            migrated.hasIndex("index_ask_citation_sourceKnowledgeBaseId")
        )

        // 2 行老数据保留,新列都为 NULL
        assertEquals(2, migrated.countOf("ask_citation"))
        val rowCount = mutableMapOf<String, Pair<String?, String?>>()
        migrated.query(
            "SELECT id, sourceKnowledgeBaseId, sourceKnowledgeBaseName FROM ask_citation ORDER BY id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val kbId = if (c.isNull(1)) null else c.getString(1)
                val kbName = if (c.isNull(2)) null else c.getString(2)
                rowCount[id] = kbId to kbName
            }
        }
        assertEquals(null to null, rowCount["cite-1"])
        assertEquals(null to null, rowCount["cite-2"])

        // Post-migration smoke: 新插入一行带 source KB 字段,验证读写正常
        migrated.execSQL(
            "INSERT INTO `ask_citation` " +
                "(`id`, `messageId`, `itemId`, `fragmentId`, `quote`, `label`, " +
                " `createdAt`, `sourceKnowledgeBaseId`, `sourceKnowledgeBaseName`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                "cite-3", "msg-3", "item-3", null,
                "新插入的引用", "来自原文",
                1_700_000_002_000L, "kb-product", "产品手册",
            )
        )
        assertEquals(3, migrated.countOf("ask_citation"))

        migrated.close()
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.hasIndex(indexName: String): Boolean {
        query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name=?",
            arrayOf(indexName)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.countOf(table: String): Int {
        // FTS4 with `content=` external content tables don't expose
        // a normal rowid count, so we use the docid column directly.
        query("SELECT COUNT(*) AS n FROM `$table`").use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }
}
