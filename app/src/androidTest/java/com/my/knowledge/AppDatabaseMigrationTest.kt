package com.my.knowledge

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.my.knowledge.data.db.AppDatabase
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
}
