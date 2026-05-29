package com.my.knowledge.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

/**
 * KnowledgeManager acts as a bridge for MVP processing.
 */
object KnowledgeManager {

    private var db: AppDatabase? = null
    private var scheduler: ProcessingTaskScheduler? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Legacy public state for compatibility during migration
    val insights = mutableStateListOf<KnowledgeInsight>()
    val fragments = mutableStateListOf<KnowledgeFragmentData>()
    val originalFiles = mutableStateListOf<RecentNote>()
    val libraries = mutableStateListOf<Library>() // Use ViewModel's flow instead in new screens
    
    var modelConfig by mutableStateOf(ModelConfig())
        private set

    fun init(context: Context) {
        db = AppDatabase.getInstance(context)
        scheduler = ProcessingTaskScheduler(context)
    }

    fun updateModelConfig(newConfig: ModelConfig) {
        modelConfig = newConfig
    }

    fun importAndAnalyze(name: String, type: String, content: String = "", targetLibrary: String = "unfiled") {
        scope.launch {
            val database = db ?: return@launch
            val taskScheduler = scheduler ?: return@launch
            
            val id = UUID.randomUUID().toString()
            val item = KnowledgeItemEntity(
                id = id,
                knowledgeBaseId = targetLibrary,
                title = name,
                contentMarkdown = content,
                excerpt = content.take(100),
                sourceType = type,
                status = "processing",
                contentHash = content.hashCode().toString(),
                summary = null,
                tagsJson = "[]",
                rawNoteId = null,
                importance = 1,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                processedAt = null,
                deletedAt = null
            )
            
            database.knowledgeItemDao().insert(item)
            
            // Trigger background pipeline via WorkManager
            taskScheduler.scheduleFullPipeline(id)
            
            // Compatibility: Add to legacy list for UI reactive updates where VMs aren't used yet
            originalFiles.add(0, RecentNote(name, type, "刚刚", "分析中..."))
        }
    }
}
