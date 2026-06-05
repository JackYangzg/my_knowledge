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
        ReviewItemEntity::class,
        KnowledgeFragmentChainEntity::class,
        KnowledgeFragmentGapEntity::class
    ],
    version = 12,
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
    abstract fun fragmentChainDao(): KnowledgeFragmentChainDao
    abstract fun fragmentGapDao(): KnowledgeFragmentGapDao

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
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
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
     * v11 -> v12: FRAG-1 knowledge fragment curation. Adds:
     *  - `starredAt` column to `knowledge_item` (for the distill product item)
     *  - `chainId` column to `knowledge_fragment` (v1: chainId == threadId)
     *  - `knowledge_fragment_chain` table (status single source of truth)
     *  - `knowledge_fragment_gap` table (8 GapType enum mapped 1:1 from
     *    `ThreadEvolutionRunner.detectGaps`)
     *
     * Backfill (per P3 of FRAG-1 design): scans every `knowledge_thread` row,
     * materialises one chain per thread (id = thread.id, status =
     * `NEED_REVIEW` if gapsJson is non-empty else `DISTILL_READY`), and
     * parses gapsJson into structured gap rows. The legacy `gapsJson` field
     * is preserved verbatim and continues to be readable; UI side flips to
     * the structured table from FRAG-1.3 onward.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `knowledge_item` ADD COLUMN `starredAt` INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE `knowledge_fragment` ADD COLUMN `chainId` TEXT DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_fragment_chainId` ON `knowledge_fragment` (`chainId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_fragment_chain` (
                    `id` TEXT NOT NULL,
                    `knowledgeBaseId` TEXT NOT NULL,
                    `threadId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `goalSummary` TEXT NOT NULL,
                    `confidence` REAL NOT NULL,
                    `entityCount` INTEGER NOT NULL,
                    `sourceCount` INTEGER NOT NULL,
                    `gapCount` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `distilledItemId` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_fragment_chain_threadId` ON `knowledge_fragment_chain` (`threadId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_fragment_chain_knowledgeBaseId_status` ON `knowledge_fragment_chain` (`knowledgeBaseId`, `status`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_fragment_gap` (
                    `id` TEXT NOT NULL,
                    `chainId` TEXT NOT NULL,
                    `gapType` TEXT NOT NULL,
                    `priority` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `suggestion` TEXT NOT NULL,
                    `resolved` INTEGER NOT NULL DEFAULT 0,
                    `resolvedByItemId` TEXT,
                    `resolvedByUserText` TEXT,
                    `resolvedAt` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_fragment_gap_chainId_resolved` ON `knowledge_fragment_gap` (`chainId`, `resolved`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_fragment_gap_gapType` ON `knowledge_fragment_gap` (`gapType`)")

            backfillFragmentChainsAndGaps(db)
        }

        /**
         * Walk every `knowledge_thread` row, write one chain row and N
         * gap rows. We use raw SQL with `JSON_EACH` so the migration
         * stays self-contained (no DAO call, no classpath dependency
         * on the gap detector / suggestion generator at migration
         * time). The substring rules mirror
         * `ThreadEvolutionRunner.detectGaps` (FRAG-1 design §1.6) and
         * `generateSuggestions` (ThreadEvolutionRunner.kt:378). New
         * ingest / reanalysis code uses the structured
         * `KnowledgeFragmentGapEntity` table directly; the substring
         * parser below only runs on the v11 -> v12 upgrade path.
         */
        private fun backfillFragmentChainsAndGaps(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            db.query("SELECT id, knowledgeBaseId, description, gapsJson FROM knowledge_thread").use { cursor ->
                while (cursor.moveToNext()) {
                    val threadId = cursor.getString(0) ?: continue
                    val kbId = cursor.getString(1) ?: continue
                    val description = cursor.getString(2) ?: ""
                    val gapsJson = cursor.getString(3) ?: "[]"

                    val gapStrings = parseJsonStringArray(gapsJson)
                    val status = if (gapStrings.isEmpty()) "DISTILL_READY" else "NEED_REVIEW"

                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO `knowledge_fragment_chain`
                            (id, knowledgeBaseId, threadId, title, goalSummary, confidence,
                             entityCount, sourceCount, gapCount, status, distilledItemId,
                             createdAt, updatedAt)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            threadId,
                            kbId,
                            threadId,
                            description.take(80).ifBlank { "知识脉络" },
                            description,
                            0.0f,
                            0,
                            0,
                            gapStrings.size,
                            status,
                            now,
                            now,
                        )
                    )

                    gapStrings.forEachIndexed { index, gapString ->
                        val (gapType, priority, suggestion) = classifyGap(gapString)
                        db.execSQL(
                            """
                            INSERT OR IGNORE INTO `knowledge_fragment_gap`
                                (id, chainId, gapType, priority, description, suggestion,
                                 resolved, resolvedByItemId, resolvedByUserText, resolvedAt, createdAt)
                            VALUES (?, ?, ?, ?, ?, ?, 0, NULL, NULL, NULL, ?)
                            """.trimIndent(),
                            arrayOf(
                                "${threadId}_gap_$index",
                                threadId,
                                gapType,
                                priority,
                                gapString,
                                suggestion,
                                now,
                            )
                        )
                    }
                }
            }
        }

        /**
         * Minimal JSON string-array parser. We only need to split
         * `["str1","str2",...]` — no nested objects, no escape
         * handling beyond the obvious `\"` and `\\`. Inputs come from
         * `ThreadEvolutionRunner`, which writes literal Chinese
         * strings with double-quote-escaped commas; that is the only
         * shape we ever see in the DB.
         */
        private fun parseJsonStringArray(json: String): List<String> {
            val trimmed = json.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
            val inner = trimmed.substring(1, trimmed.length - 1)
            if (inner.isBlank()) return emptyList()
            return inner.split(",").mapNotNull { raw ->
                val unquoted = raw.trim().removePrefix("\"").removeSuffix("\"")
                val unescaped = unquoted.replace("\\\"", "\"").replace("\\\\", "\\")
                unescaped.ifBlank { null }
            }
        }

        /**
         * Substring classifier mirroring
         * `ThreadEvolutionRunner.detectGaps` (8 rules) and
         * `generateSuggestions` (FRAG-1 design §1.6 mapping table).
         * Returns `(GapType, Priority, Suggestion)`. The fallback
         * bucket is the same as the original "缺标签" path so legacy
         * rows with a custom string still land on a sensible row.
         */
        private fun classifyGap(description: String): Triple<String, String, String> {
            return when {
                description.contains("合成页") || description.contains("index") || description.contains("overview") -> Triple(
                    "MISSING_SYNTHESIS", "HIGH", "补充 index / overview / log 合成页形成主线"
                )
                description.contains("低置信度") || description.contains("复核") -> Triple(
                    "LOW_CONFIDENCE", "HIGH", "人工复核低置信度知识条目"
                )
                description.contains("主线") || description.contains("标签聚类") -> Triple(
                    "NO_MAINLINE", "MEDIUM", "补充更明确的标签以形成主线"
                )
                description.contains("标签") -> Triple(
                    "MISSING_TAGS", "MEDIUM", "为超过半数缺少标签的知识补充标签"
                )
                description.contains("摘要") -> Triple(
                    "MISSING_SUMMARY", "LOW", "为缺少摘要的知识补充摘要"
                )
                description.contains("关系") || description.contains("引用") || description.contains("同主题") -> Triple(
                    "NO_RELATIONS", "HIGH", "建立知识之间的显式引用或同主题关联"
                )
                description.contains("wiki 页面") || description.contains("wiki") -> Triple(
                    "NO_WIKI_PAGES", "HIGH", "先完成知识加工产出 wiki 页面"
                )
                description.contains("尚无") || description.contains("空") -> Triple(
                    "KB_EMPTY", "HIGH", "导入首批知识条目以启动脉络"
                )
                else -> Triple(
                    "MISSING_TAGS", "MEDIUM", "完善知识条目元数据以提高脉络完整度"
                )
            }
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

        // ReCREATE with the default unicode61 tokenizer.
        //
        // We do NOT pass `remove_diacritics=1` even though the
        // comment in the original v10→v11 design called for it:
        // that option is FTS5-only, and the stock Android SQLite
        // ships FTS4. FTS4's `unknown tokenizer` error from an
        // unknown option string is raised at CREATE VIRTUAL
        // TABLE time, which is what crashed the user's app on
        // first launch (AppDatabase.kt:400). FTS4 unicode61 does
        // fold CJK into the basic letter class, so a stray
        // combining mark still won't break phrase queries — the
        // diacritic-folding case ("café" vs "cafe") simply falls
        // back to a secondary LIKE scan in the DAO if needed.
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS `knowledge_item_fts` USING fts4(
                `title`,
                `contentMarkdown`,
                `summary`,
                tokenize=unicode61,
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
                tokenize=unicode61,
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
            // Backfill (no-op on fresh installs). Bare column names
            // only — `OLD.col` is a trigger-body qualifier, not a
            // top-level SELECT reference, so using it here would make
            // the v10→v11 migration fail on any non-empty source
            // table and leave the FTS index half-populated (which
            // would then surface as a SQLITE_INTERNAL "no such file
            // or directory" the next time a generation-stage
            // knowledge_item insert fired the AFTER_INSERT sync
            // trigger). Match the v9→v10 backfill at the top of
            // MIGRATION_9_10, which is correct.
            db.execSQL(
                """
                INSERT INTO `$ftsName`(`docid`, $columnList)
                SELECT rowid, $columnList FROM `$sourceName`
                """.trimIndent()
            )
        }
    }
    }
}
