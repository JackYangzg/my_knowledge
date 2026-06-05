package com.my.knowledge.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.ProcessingTaskLogEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.data.parser.WeChatArticleParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import kotlinx.coroutines.CoroutineScope
import com.my.knowledge.data.util.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

/**
 * KnowledgeManager acts as a bridge for MVP processing.
 * P0: Includes local-first preference management
 */
object KnowledgeManager {
    
    private const val PREFS_NAME = "knowledge_prefs"
    private const val KEY_AI_EXTERNAL_ENABLED = "ai_external_calls_enabled"
    private const val KEY_PROVIDER = "model_provider"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_IMAGE_PROVIDER = "image_analysis_provider"
    private const val KEY_IMAGE_API_KEY = "image_analysis_api_key"
    private const val KEY_IMAGE_BASE_URL = "image_analysis_base_url"
    private const val KEY_SEARCH_PROVIDER = "search_analysis_provider"
    private const val KEY_SEARCH_API_KEY = "search_analysis_api_key"
    private const val KEY_SEARCH_BASE_URL = "search_analysis_base_url"
    private const val KEY_VOICE_PROVIDER = "voice_provider"
    private const val KEY_VOICE_API_KEY = "voice_api_key"
    private const val KEY_VOICE_APP_ID = "voice_app_id"
    private const val KEY_VOICE_CLUSTER_ID = "voice_cluster_id"
    private const val KEY_DEBUG_PROMPT_ENABLED = "debug_prompt_enabled"

    private var db: AppDatabase? = null
    private var scheduler: ProcessingTaskScheduler? = null
    private var fileStore: LocalFileStore? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // P0: SharedPreferences for local-first toggle
    lateinit var preferences: SharedPreferences
        private set

    // Legacy public state for compatibility during migration
    val insights = mutableStateListOf<KnowledgeInsight>()
    val fragments = mutableStateListOf<KnowledgeFragmentData>()
    val originalFiles = mutableStateListOf<RecentNote>()
    val libraries = mutableStateListOf<Library>() // Use ViewModel's flow instead in new screens
    
    var modelConfig by mutableStateOf(ModelConfig())
        private set

    // P0: AI external calls setting
    var aiExternalCallsEnabled: Boolean
        get() = preferences.getBoolean(KEY_AI_EXTERNAL_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_AI_EXTERNAL_ENABLED, value).apply()
        }

    fun init(context: Context) {
        db = AppDatabase.getInstance(context)
        scheduler = ProcessingTaskScheduler(context)
        fileStore = LocalFileStore(context)
        // PDFBox-Android reads its bundled font/CMAP resources from the APK
        // the first time Loader.loadPDF is called. Initializing once at app
        // startup is cheaper than re-checking on every PDF and ensures the
        // call is on the main thread (PDFBoxResourceLoader is not documented
        // as thread-safe for init).
        PDFBoxResourceLoader.init(context.applicationContext)
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedVoiceApiKey = preferences.getString(KEY_VOICE_API_KEY, "") ?: ""
        val sanitizedVoiceApiKey = storedVoiceApiKey.takeUnless {
            it == "bad43d9b-7b28-430c-a8a2-c1aa35430195"
        }.orEmpty()
        if (storedVoiceApiKey != sanitizedVoiceApiKey) {
            preferences.edit().putString(KEY_VOICE_API_KEY, sanitizedVoiceApiKey).apply()
        }
        modelConfig = ModelConfig(
            provider = preferences.getString(KEY_PROVIDER, "minimax") ?: "minimax",
            modelName = preferences.getString(KEY_MODEL_NAME, "MiniMax-M3") ?: "MiniMax-M3",
            apiKey = preferences.getString(KEY_API_KEY, "") ?: "",
            baseUrl = preferences.getString(KEY_BASE_URL, "https://api.minimaxi.com/v1") ?: "https://api.minimaxi.com/v1",
            imageAnalysisProvider = preferences.getString(KEY_IMAGE_PROVIDER, "minimax") ?: "minimax",
            imageAnalysisApiKey = preferences.getString(KEY_IMAGE_API_KEY, "") ?: "",
            imageAnalysisBaseUrl = preferences.getString(KEY_IMAGE_BASE_URL, "https://api.minimaxi.com/v1") ?: "https://api.minimaxi.com/v1",
            searchAnalysisProvider = preferences.getString(KEY_SEARCH_PROVIDER, "minimax") ?: "minimax",
            searchAnalysisApiKey = preferences.getString(KEY_SEARCH_API_KEY, "") ?: "",
            searchAnalysisBaseUrl = preferences.getString(KEY_SEARCH_BASE_URL, "https://api.minimaxi.com/v1") ?: "https://api.minimaxi.com/v1",
            voiceProvider = preferences.getString(KEY_VOICE_PROVIDER, "volcengine") ?: "volcengine",
            voiceApiKey = sanitizedVoiceApiKey,
            voiceAppId = preferences.getString(KEY_VOICE_APP_ID, "") ?: "",
            voiceClusterId = preferences.getString(KEY_VOICE_CLUSTER_ID, "volc_ent_asr_streaming") ?: "volc_ent_asr_streaming",
            debugPromptEnabled = preferences.getBoolean(KEY_DEBUG_PROMPT_ENABLED, false)
        )
        scheduler?.scheduleIngestQueue()
    }

    fun updateModelConfig(newConfig: ModelConfig) {
        modelConfig = newConfig
        preferences.edit()
            .putString(KEY_PROVIDER, newConfig.provider)
            .putString(KEY_MODEL_NAME, newConfig.modelName)
            .putString(KEY_API_KEY, newConfig.apiKey)
            .putString(KEY_BASE_URL, newConfig.baseUrl)
            .putString(KEY_IMAGE_PROVIDER, newConfig.imageAnalysisProvider)
            .putString(KEY_IMAGE_API_KEY, newConfig.imageAnalysisApiKey)
            .putString(KEY_IMAGE_BASE_URL, newConfig.imageAnalysisBaseUrl)
            .putString(KEY_SEARCH_PROVIDER, newConfig.searchAnalysisProvider)
            .putString(KEY_SEARCH_API_KEY, newConfig.searchAnalysisApiKey)
            .putString(KEY_SEARCH_BASE_URL, newConfig.searchAnalysisBaseUrl)
            .putString(KEY_VOICE_PROVIDER, newConfig.voiceProvider)
            .putString(KEY_VOICE_API_KEY, newConfig.voiceApiKey)
            .putString(KEY_VOICE_APP_ID, newConfig.voiceAppId)
            .putString(KEY_VOICE_CLUSTER_ID, newConfig.voiceClusterId)
            .putBoolean(KEY_DEBUG_PROMPT_ENABLED, newConfig.debugPromptEnabled)
            .apply()
    }

    private fun sha256(content: String): String = Sha256.hex(content)

    fun importAndAnalyze(
        name: String,
        type: String,
        content: String = "",
        targetLibrary: String = "unfiled",
        sourceUri: String? = null,
        localPath: String? = null
    ) {
        scope.launch {
            val database = db ?: return@launch
            val taskScheduler = scheduler ?: return@launch
            
            // Resolve target KB ID (Fixes issue #1: using type string instead of UUID)
            val kbId = if (targetLibrary == "unfiled" || targetLibrary == "inspiration" || targetLibrary == "system") {
                database.knowledgeBaseDao().getByType(targetLibrary)?.id ?: targetLibrary
            } else {
                targetLibrary
            }

            val sourceId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val finalLocalPath = localPath ?: fileStore?.saveTextSource(sourceId, content)?.absolutePath
            val contentHash = sha256(content)

            // 1. Create SourceDocumentEntity (Visibility in Log Center - fixes issue #2)
            val source = SourceDocumentEntity(
                id = sourceId,
                sourceType = type,
                title = name,
                originalUri = sourceUri,
                localPath = finalLocalPath,
                mimeType = if (type == "wechat_article" || type.contains("markdown")) "text/markdown" else "text/plain",
                sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                sha256 = contentHash,
                importFrom = "share_or_manual",
                folderHint = null,
                status = SourceDocumentEntity.STATUS_IMPORTED,
                errorMessage = null,
                targetKnowledgeBaseId = kbId,
                createdAt = now,
                updatedAt = now
            )
            database.sourceDocumentDao().insert(source)

            // 2. Create initial KnowledgeItem so it's visible in the knowledge base list immediately
            val item = KnowledgeItemEntity(
                id = UUID.randomUUID().toString(),
                sourceId = sourceId,
                knowledgeBaseId = kbId,
                title = name,
                contentMarkdown = content,
                excerpt = "正在排队等待加工...",
                sourceType = type,
                status = KnowledgeItemEntity.STATUS_PROCESSING,
                contentHash = contentHash,
                sourceTraceJson = """{"sourceId":"$sourceId","localPath":"${finalLocalPath.orEmpty().escapeJson()}"}""",
                confidence = 0f,
                summary = null,
                tagsJson = "[]",
                rawNoteId = null,
                importance = 1,
                createdAt = now,
                updatedAt = now,
                processedAt = null,
                archivedAt = null,
                deletedAt = null
            )
            database.knowledgeItemDao().insert(item)
            // 立刻把 kb.itemCount +1,避免用户切回主页看到"导入完成但知识库
            // 数量没变"的假象(此前这里依赖 generationTask 跑完才更新,中间
            // 这段空窗期 user 看到的就是错的数字)。generationTask 完成后
            // 会再次 updateItemCount,值会重新计算——这是幂等的,不会重复
            // 累加,因为只 insert 了一条 root item,后续 rootItem 会被 reuse。
            database.knowledgeItemDao().updateItemCount(kbId)

            // 3. Create initial parse task for the Ingest Pipeline
            val taskId = UUID.randomUUID().toString()
            database.processingTaskDao().insert(
                ProcessingTaskEntity(
                    id = taskId,
                    targetType = "source_document",
                    targetId = sourceId,
                    taskType = "parse",
                    status = "pending",
                    priority = 10,
                    dependsOnTaskIdsJson = null,
                    retryCount = 0,
                    maxRetry = 3,
                    errorMessage = null,
                    createdAt = now,
                    updatedAt = now,
                    finishedAt = null,
                    sourceId = sourceId,
                    itemId = null,
                    progress = 0,
                    currentStep = "等待解析",
                    inputJson = """{"sourceId":"$sourceId"}"""
                )
            )
            database.processingTaskLogDao().insert(
                ProcessingTaskLogEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    targetType = "source_document",
                    targetId = sourceId,
                    stage = "parse",
                    status = "pending",
                    message = "已入库并排队，等待解析",
                    createdAt = now
                )
            )
            
            // 4. Trigger background pipeline via WorkManager
            taskScheduler.scheduleIngestQueue()
            
            // Compatibility: Add to legacy list for UI reactive updates
            originalFiles.add(0, RecentNote(name, type, "刚刚", "排队中"))
        }
    }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    fun importFromWeChat(url: String) {
        scope.launch {
            try {
                val parser = WeChatArticleParser()
                val article = parser.parse(url)
                importAndAnalyze(
                    name = article.title,
                    type = "wechat_article",
                    content = article.contentText,
                    targetLibrary = "unfiled",
                    sourceUri = url
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
