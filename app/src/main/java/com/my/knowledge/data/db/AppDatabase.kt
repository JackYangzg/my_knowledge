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
    version = 6,
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
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
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

                addColumnIfMissing(db, "knowledge_fragment", "sourceId", "TEXT")
                addColumnIfMissing(db, "knowledge_fragment", "parsedContentId", "TEXT")
                addColumnIfMissing(db, "knowledge_fragment", "knowledgeItemId", "TEXT")
                addColumnIfMissing(db, "knowledge_fragment", "orderIndex", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "knowledge_fragment", "heading", "TEXT")
                addColumnIfMissing(db, "knowledge_fragment", "tokenCount", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "knowledge_fragment", "embeddingId", "TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_fragment_sourceId` ON `knowledge_fragment` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_fragment_parsedContentId` ON `knowledge_fragment` (`parsedContentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_fragment_knowledgeItemId` ON `knowledge_fragment` (`knowledgeItemId`)")

                addColumnIfMissing(db, "processing_task", "sourceId", "TEXT")
                addColumnIfMissing(db, "processing_task", "itemId", "TEXT")
                addColumnIfMissing(db, "processing_task", "progress", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "processing_task", "currentStep", "TEXT")
                addColumnIfMissing(db, "processing_task", "inputJson", "TEXT NOT NULL DEFAULT '{}'")
                addColumnIfMissing(db, "processing_task", "outputJson", "TEXT")
                addColumnIfMissing(db, "processing_task", "startedAt", "INTEGER")
            }

            private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, spec: String) {
                db.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == column) return
                    }
                }
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $spec")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "knowledge_item", "sourceId", "TEXT")
                addColumnIfMissing(db, "knowledge_item", "sourceTraceJson", "TEXT NOT NULL DEFAULT '[]'")
                addColumnIfMissing(db, "knowledge_item", "confidence", "REAL NOT NULL DEFAULT 1.0")
                addColumnIfMissing(db, "knowledge_item", "archivedAt", "INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_item_sourceId` ON `knowledge_item` (`sourceId`)")
            }

            private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, spec: String) {
                db.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == column) return
                    }
                }
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $spec")
            }
        }
    }
}
