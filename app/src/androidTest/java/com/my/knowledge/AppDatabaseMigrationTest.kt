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

    private fun androidx.sqlite.db.SupportSQLiteDatabase.countOf(table: String): Int {
        // FTS4 with `content=` external content tables don't expose
        // a normal rowid count, so we use the docid column directly.
        query("SELECT COUNT(*) AS n FROM `$table`").use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }
}
