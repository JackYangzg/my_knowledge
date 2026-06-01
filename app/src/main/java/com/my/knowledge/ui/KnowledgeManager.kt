package com.my.knowledge.ui

import android.content.Context
import android.content.SharedPreferences
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
import java.security.MessageDigest
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

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
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
                contentHash = sha256(content),
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
