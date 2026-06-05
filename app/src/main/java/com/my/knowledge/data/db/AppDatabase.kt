package com.my.knowledge.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.my.knowledge.data.db.dao.*
import com.my.knowledge.data.db.entity.*

@Database(
    entities = [
        KnowledgeBaseEntity::class,
        KnowledgeItemEntity::class,
        KnowledgeItemFts::class,
        KnowledgeFragmentFts::class,
        NoteEntity::class,
        AttachmentEntity::class,
        ProcessingTaskEntity::class,
        ArchiveRecommendationEntity::class,
        AiConversationEntity::class,
        AiMessageEntity::class,
        KnowledgeThreadEntity::class,
        KnowledgeThreadLogEntity::class,
        SourceManifestEntity::class,
        KnowledgeFragmentEntity::class,
        ProcessingTaskLogEntity::class,
        AskCitationEntity::class,
        KnowledgeEmbeddingEntity::class,
        KnowledgeEntityEntity::class,
        KnowledgeRelationEntity::class,
        KnowledgeCommunityEntity::class,
        SourceDocumentEntity::class,
        ParsedContentEntity::class,
        AnalysisResultEntity::class,
        ReviewItemEntity::class
    ],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao
    abstract fun knowledgeItemDao(): KnowledgeItemDao
    abstract fun noteDao(): NoteDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun processingTaskDao(): ProcessingTaskDao
    abstract fun archiveRecommendationDao(): ArchiveRecommendationDao
    abstract fun searchDao(): SearchDao
    abstract fun aiConversationDao(): AiConversationDao
    abstract fun aiMessageDao(): AiMessageDao
    abstract fun knowledgeThreadDao(): KnowledgeThreadDao
    abstract fun knowledgeThreadLogDao(): KnowledgeThreadLogDao
    abstract fun sourceManifestDao(): SourceManifestDao
    abstract fun knowledgeFragmentDao(): KnowledgeFragmentDao
    abstract fun processingTaskLogDao(): ProcessingTaskLogDao
    abstract fun askCitationDao(): AskCitationDao
    abstract fun knowledgeGraphDao(): KnowledgeGraphDao
    abstract fun sourceDocumentDao(): SourceDocumentDao
    abstract fun parsedContentDao(): ParsedContentDao
    abstract fun analysisResultDao(): AnalysisResultDao
    abstract fun reviewItemDao(): ReviewItemDao

    companion object {
        private const val DATABASE_NAME = "knowledge_db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                .addCallback(FtsDiacriticsCallback)
                .build()
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `source_document` (
                        `id` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `originalUri` TEXT,
                        `localPath` TEXT,
                        `mimeType` TEXT,
                        `sizeBytes` INTEGER,
                        `sha256` TEXT NOT NULL,
                        `importFrom` TEXT,
                        `folderHint` TEXT,
                        `status` TEXT NOT NULL,
                        `errorMessage` TEXT,
                        `targetKnowledgeBaseId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_document_sha256` ON `source_document` (`sha256`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_document_status` ON `source_document` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_document_sourceType` ON `source_document` (`sourceType`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `parsed_content` (
                        `id` TEXT NOT NULL,
                        `sourceId` TEXT NOT NULL,
                        `parserType` TEXT NOT NULL,
                        `markdown` TEXT NOT NULL,
                        `plainText` TEXT NOT NULL,
                        `parseHash` TEXT NOT NULL,
                        `metadataJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_parsed_content_sourceId` ON `parsed_content` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_parsed_content_parseHash` ON `parsed_content` (`parseHash`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `analysis_result` (
                        `id` TEXT NOT NULL,
                        `sourceId` TEXT NOT NULL,
                        `parsedContentId` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `tagsJson` TEXT NOT NULL,
                        `entitiesJson` TEXT NOT NULL,
                        `conceptsJson` TEXT NOT NULL,
                        `relationsJson` TEXT NOT NULL,
                        `claimsJson` TEXT NOT NULL,
                        `gapsJson` TEXT NOT NULL,
                        `archiveRecommendationJson` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `modelName` TEXT,
                        `promptVersion` TEXT NOT NULL,
                        `analysisHash` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_analysis_result_sourceId` ON `analysis_result` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_analysis_result_parsedContentId` ON `analysis_result` (`parsedContentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_analysis_result_analysisHash` ON `analysis_result` (`analysisHash`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `review_item` (
                        `id` TEXT NOT NULL,
                        `sourceId` TEXT,
                        `itemId` TEXT,
                        `type` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `suggestedActionsJson` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `resolvedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_item_sourceId` ON `review_item` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_item_itemId` ON `review_item` (`itemId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_item_status` ON `review_item` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_item_type` ON `review_item` (`type`)")

                db.addColumnIfMissing( "knowledge_fragment", "sourceId", "TEXT")
                db.addColumnIfMissing( "knowledge_fragment", "parsedContentId", "TEXT")
                db.addColumnIfMissing( "knowledge_fragment", "knowledgeItemId", "TEXT")
                db.addColumnIfMissing( "knowledge_fragment", "orderIndex", "INTEGER NOT NULL DEFAULT 0")
                db.addColumnIfMissing( "knowledge_fragment", "heading", "TEXT")
                db.addColumnIfMissing( "knowledge_fragment", "tokenCount", "INTEGER NOT NULL DEFAULT 0")
                db.addColumnIfMissing( "knowledge_fragment", "embeddingId", "TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_fragment_sourceId` ON `knowledge_fragment` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_fragment_parsedContentId` ON `knowledge_fragment` (`parsedContentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_fragment_knowledgeItemId` ON `knowledge_fragment` (`knowledgeItemId`)")

                db.addColumnIfMissing( "processing_task", "sourceId", "TEXT")
                db.addColumnIfMissing( "processing_task", "itemId", "TEXT")
                db.addColumnIfMissing( "processing_task", "progress", "INTEGER NOT NULL DEFAULT 0")
                db.addColumnIfMissing( "processing_task", "currentStep", "TEXT")
                db.addColumnIfMissing( "processing_task", "inputJson", "TEXT NOT NULL DEFAULT '{}'")
                db.addColumnIfMissing( "processing_task", "outputJson", "TEXT")
                db.addColumnIfMissing( "processing_task", "startedAt", "INTEGER")
            }

        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing( "knowledge_item", "sourceId", "TEXT")
                db.addColumnIfMissing( "knowledge_item", "sourceTraceJson", "TEXT NOT NULL DEFAULT '[]'")
                db.addColumnIfMissing( "knowledge_item", "confidence", "REAL NOT NULL DEFAULT 1.0")
                db.addColumnIfMissing( "knowledge_item", "archivedAt", "INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_item_sourceId` ON `knowledge_item` (`sourceId`)")
            }

        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing( "knowledge_entity", "deletedAt", "INTEGER")
                db.addColumnIfMissing( "knowledge_relation", "deletedAt", "INTEGER")
                db.addColumnIfMissing( "knowledge_community", "deletedAt", "INTEGER")
            }

        }

        /**
         * v7 -> v8: add `confidence` to knowledge_entity so we can distinguish
         * between AI-inferred entities and human-curated ones.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumnIfMissing( "knowledge_entity", "confidence", "REAL NOT NULL DEFAULT 1.0")
            // The new rawNoteId index on knowledge_item was added when we
            // wired InspirationScreen → knowledge_item dedup. v7 never had
            // this index, so without this line Room detects a schema
            // mismatch and crashes the app on first launch after upgrade.
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_item_rawNoteId` ON `knowledge_item` (`rawNoteId`)")
        }
    }

        val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ThreadEvolutionWorker now persists the input hash that drives
            // its no-op short-circuit. Older threads simply leave it null
            // and get re-derived on the next schedule — nothing to backfill.
            db.execSQL("ALTER TABLE `knowledge_thread` ADD COLUMN `inputHash` TEXT")
        }
    }

    /**
     * v9 -> v10: PERF-7 fragment FTS4 index. The
     * `searchFragments*` queries used to do `LIKE '%q%'`
     * substring scans over `knowledge_fragment`, which scales
     * O(N) per query. We now mirror `content` / `summary` /
     * tagsJson into a FTS4 table (`knowledge_fragment_fts`) and
     * route fragment searches through it. Three sync triggers
     * keep the FTS table in lock-step with the underlying
     * `knowledge_fragment` row; Room would have generated
     * them automatically if we had added the entity before
     * the first install, but for an existing v9 DB the
     * triggers + backfill have to come in the migration.
     *
     * Tokenizer matches [KnowledgeItemFts] (unicode61) so
     * phrase queries and ranking work the same way across
     * both FTS indexes.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS `knowledge_fragment_fts` USING fts4(
                    `content`,
                    `summary`,
                    `tagsJson`,
                    tokenize=unicode61,
                    content=`knowledge_fragment`
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `knowledge_fragment_ai` AFTER INSERT ON `knowledge_fragment` BEGIN
                    INSERT INTO `knowledge_fragment_fts`(rowid, content, summary, tagsJson)
                    VALUES (new.rowid, new.content, new.summary, new.tagsJson);
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `knowledge_fragment_ad` AFTER DELETE ON `knowledge_fragment` BEGIN
                    INSERT INTO `knowledge_fragment_fts`(`knowledge_fragment_fts`, rowid, content, summary, tagsJson)
                    VALUES ('delete', old.rowid, old.content, old.summary, old.tagsJson);
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `knowledge_fragment_au` AFTER UPDATE ON `knowledge_fragment` BEGIN
                    INSERT INTO `knowledge_fragment_fts`(`knowledge_fragment_fts`, rowid, content, summary, tagsJson)
                    VALUES ('delete', old.rowid, old.content, old.summary, old.tagsJson);
                    INSERT INTO `knowledge_fragment_fts`(rowid, content, summary, tagsJson)
                    VALUES (new.rowid, new.content, new.summary, new.tagsJson);
                END
                """.trimIndent()
            )
            // Backfill existing rows. Newer versions of Room
            // generate this automatically; for the v9->v10
            // migration we have to populate the FTS table from
            // the live `knowledge_fragment` rows ourselves.
            db.execSQL(
                """
                INSERT INTO `knowledge_fragment_fts`(rowid, content, summary, tagsJson)
                SELECT rowid, content, summary, tagsJson FROM `knowledge_fragment`
                """.trimIndent()
            )
        }
    }

    /**
     * v10 -> v11: PERF-10 FTS `remove_diacritics=1`. The default
     * `tokenize=unicode61` keeps accents in the index, so a search
     * for "cafe" misses "café". `remove_diacritics=1` is a SQLite
     * FTS4 option that strips diacritics at index time and at query
     * time, so the two become equivalent (same for "naïve" /
     * "naive", "São Paulo" / "Sao Paulo", etc.). It also matters
     * for CJK — unicode61's `categories` default already folds CJK
     * into the basic letter class, so a stray combining mark
     * doesn't break phrase queries anymore.
     *
     * FTS4 options are immutable on a virtual table, so we DROP and
     * reCREATE both `knowledge_item_fts` and `knowledge_fragment_fts`
     * (and their sync triggers) and re-backfill from the live
     * source tables. The DB also still carries the three
     * hand-rolled triggers (`knowledge_fragment_ai/_ad/_au`) that
     * MIGRATION_9_10 added for v9->v10 upgrades; those reference
     * the old `rowid` form and would cause double-inserts on top
     * of Room's `docid`-based sync triggers, so we drop them too.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            rebuildFtsWithDiacritics(db, includeLegacyFragmentTriggers = true)
        }
    }

    /**
     * Fresh-install fix-up. The `@Fts4` annotation has no way to
     * express `remove_diacritics=1` (it's not part of the
     * [androidx.room.FtsOptions] API), so Room's auto-DDL creates
     * both FTS tables without it. We run the same DROP / reCREATE
     * routine on `onCreate` so a v11 install has the option set
     * from the first byte. Empty tables, so the backfill is a
     * no-op.
     */
    private object FtsDiacriticsCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            rebuildFtsWithDiacritics(db, includeLegacyFragmentTriggers = false)
        }
    }

    /**
     * DROP both FTS tables + all 11 sync triggers (4 Room + 3
     * legacy for fragment + 4 for item), then reCREATE the tables
     * with `tokenize=unicode61 remove_diacritics=1` and re-emit
     * Room's 4 sync triggers per table, then backfill.
     *
     * Kept as a companion-private helper so MIGRATION_10_11 (DBs
     * that may carry the legacy triggers) and FtsDiacriticsCallback
     * (fresh installs, no legacy triggers) call the same code
     * path — one place to keep the trigger names in sync.
     */
    private fun rebuildFtsWithDiacritics(
        db: SupportSQLiteDatabase,
        includeLegacyFragmentTriggers: Boolean,
    ) {
        // Drop all 8 Room-generated sync triggers (4 per FTS table).
        listOf(
            "room_fts_content_sync_knowledge_item_fts_BEFORE_UPDATE",
            "room_fts_content_sync_knowledge_item_fts_BEFORE_DELETE",
            "room_fts_content_sync_knowledge_item_fts_AFTER_UPDATE",
            "room_fts_content_sync_knowledge_item_fts_AFTER_INSERT",
            "room_fts_content_sync_knowledge_fragment_fts_BEFORE_UPDATE",
            "room_fts_content_sync_knowledge_fragment_fts_BEFORE_DELETE",
            "room_fts_content_sync_knowledge_fragment_fts_AFTER_UPDATE",
            "room_fts_content_sync_knowledge_fragment_fts_AFTER_INSERT",
        ).forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }

        // Drop the 3 legacy hand-rolled triggers from MIGRATION_9_10
        // if the DB is a v9->v10->v11 upgrade. They reference the
        // old `rowid` form and would cause double-inserts on top of
        // Room's `docid`-based triggers.
        if (includeLegacyFragmentTriggers) {
            listOf("knowledge_fragment_ai", "knowledge_fragment_ad", "knowledge_fragment_au")
                .forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
        }

        // Drop the FTS tables themselves. Order matters — if the
        // triggers above already errored, this catches the
        // residual.
        db.execSQL("DROP TABLE IF EXISTS `knowledge_item_fts`")
        db.execSQL("DROP TABLE IF EXISTS `knowledge_fragment_fts`")

        // ReCREATE with remove_diacritics=1.
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS `knowledge_item_fts` USING fts4(
                `title`,
                `contentMarkdown`,
                `summary`,
                tokenize=unicode61 remove_diacritics=1,
                content=`knowledge_item`
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS `knowledge_fragment_fts` USING fts4(
                `content`,
                `summary`,
                `tagsJson`,
                tokenize=unicode61 remove_diacritics=1,
                content=`knowledge_fragment`
            )
            """.trimIndent()
        )

        // Re-emit Room's 4 sync triggers per FTS table. Definitions
        // match what Room would auto-generate from the @Fts4
        // annotation (see app/schemas/.../10.json).
        listOf(
            "knowledge_item_fts" to "knowledge_item" to listOf("title", "contentMarkdown", "summary"),
            "knowledge_fragment_fts" to "knowledge_fragment" to listOf("content", "summary", "tagsJson"),
        ).forEach { (ftsToSource, columns) ->
            val (ftsName, sourceName) = ftsToSource
            val columnList = columns.joinToString(", ") { "`$it`" }
            val columnValues = columns.joinToString(", ") { "NEW.`$it`" }
            val oldColumnValues = columns.joinToString(", ") { "OLD.`$it`" }
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_${ftsName}_BEFORE_UPDATE`
                BEFORE UPDATE ON `$sourceName`
                BEGIN
                    DELETE FROM `$ftsName` WHERE `docid`=OLD.`rowid`;
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_${ftsName}_BEFORE_DELETE`
                BEFORE DELETE ON `$sourceName`
                BEGIN
                    DELETE FROM `$ftsName` WHERE `docid`=OLD.`rowid`;
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_${ftsName}_AFTER_UPDATE`
                AFTER UPDATE ON `$sourceName`
                BEGIN
                    INSERT INTO `$ftsName`(`docid`, $columnList)
                    VALUES (NEW.`rowid`, $columnValues);
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_${ftsName}_AFTER_INSERT`
                AFTER INSERT ON `$sourceName`
                BEGIN
                    INSERT INTO `$ftsName`(`docid`, $columnList)
                    VALUES (NEW.`rowid`, $columnValues);
                END
                """.trimIndent()
            )
            // Backfill (no-op on fresh installs).
            db.execSQL(
                """
                INSERT INTO `$ftsName`(`docid`, $columnList)
                SELECT rowid, $oldColumnValues FROM `$sourceName`
                """.trimIndent()
            )
        }
    }
    }
}
