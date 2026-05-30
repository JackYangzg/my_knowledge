package com.my.knowledge.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        KnowledgeThreadLogEntity::class
    ],
    version = 2,
    exportSchema = false
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
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
}