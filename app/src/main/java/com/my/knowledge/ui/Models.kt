package com.my.knowledge.ui

import kotlinx.serialization.Serializable

// --- Visible UI Models ---

@Serializable
data class Library(
    val name: String,
    val count: Int,
    val desc: String,
    val note: String,
    val system: Boolean = false
)

@Serializable
data class RecentNote(
    val title: String,
    val source: String,
    val time: String,
    val status: String,
    val size: String = ""
)

@Serializable
data class Mainline(
    val title: String,
    val desc: String,
    val nodes: List<String>
)

@Serializable
data class FragmentItem(
    val title: String,
    val type: String,
    val summary: String,
    val action: String,
    val confidence: String
)

@Serializable
data class ThemeCluster(
    val title: String,
    val count: Int,
    val output: String
)

/**
 * Represent a user-visible Insight extracted from data.
 */
@Serializable
data class KnowledgeInsight(
    val id: String,
    val summary: String,
    val keyTakeaways: List<String>,
    val timestamp: Long
)

/**
 * Represent an organized Knowledge Fragment.
 */
@Serializable
data class KnowledgeFragmentData(
    val id: String,
    val title: String,
    val content: String,
    val sourceFile: String,
    val tags: List<String>
)

/**
 * Legacy support for old lists in Data.kt
 */
@Serializable
data class KnowledgeItemData(
    val title: String,
    val excerpt: String,
    val meta: List<String>
)

// --- Internal (Invisible) Management Models ---
// These are stored locally but never directly exposed in the UI lists as "Entities" or "Graph Nodes"

@Serializable
data class InternalEntity(
    val id: String,
    val name: String,
    val type: String, // e.g., "Person", "Concept", "Tech"
    val metadata: Map<String, String> = emptyMap(),
    val relatedEntityIds: List<String> = emptyList()
)

@Serializable
data class InternalAnalysisState(
    val fileId: String,
    val extractedEntityIds: List<String>,
    val vectorEmbeddingStatus: Boolean,
    val llmRefined: Boolean
)

/**
 * ARCH-8: 思考强度档位，对应 MiniMax `/v1/responses` API 的
 * `reasoning.effort` 字段（参考 https://platform.minimaxi.com/docs/api-reference/responses-input-tokens）。
 *
 * [apiValue] 是写入请求体 `reasoning.effort` 的小写字符串。
 * 持久化时存枚举名（如 "MEDIUM"），由 [KnowledgeManager] 负责
 * SharedPreferences ↔ enum 的双向映射，未识别值回落到 [MEDIUM]。
 */
@Serializable
enum class ReasoningEffort(val apiValue: String) {
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun fromNameOrDefault(name: String?): ReasoningEffort =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: MEDIUM
    }
}

@Serializable
data class ModelConfig(
    val provider: String = "minimax",
    val modelName: String = "MiniMax-M3",
    val apiKey: String = "",
    val baseUrl: String = "https://api.minimaxi.com/v1",
    val imageAnalysisProvider: String = "minimax",
    val imageAnalysisApiKey: String = "",
    val imageAnalysisBaseUrl: String = "https://api.minimaxi.com/v1",
    val searchAnalysisProvider: String = "minimax",
    val searchAnalysisApiKey: String = "",
    val searchAnalysisBaseUrl: String = "https://api.minimaxi.com/v1",
    val voiceProvider: String = "volcengine",
    val voiceApiKey: String = "",
    val voiceAppId: String = "",
    val voiceClusterId: String = "volc_ent_asr_streaming",
    val debugPromptEnabled: Boolean = false,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    // AI 全库对话检索开关 (T3 AskRetrievalPipeline 用)
    val askGraphEnabled: Boolean = true,   // 共现 tag 关系图扩展,默认开
    val askWebEnabled: Boolean = false,    // web 搜索,默认关(成本+幻觉)
    /** 模型 context window (字符). 0/未配置 → ContextBudgetCalculator 回退到 204_800.
     *  Phase 1: 仅数据层字段,Phase 6 在 SettingsScreen 加 UI 输入框.
     *  ⚠️ JSON 序列化警告:maxContextSize=0 会被序列化为 0;调用方走 effectiveMaxContextSize()
     *  而不是裸读字段,以避免把"未配置"状态泄漏给服务端的请求体. */
    val maxContextSize: Int = 0,
) {
    /** 真实 context window. 把 0/未配置 映射到 DEFAULT_MAX_CTX(204_800),
     *  保证消费方(预算计算、max_tokens 缩放)始终拿到有效值. */
    fun effectiveMaxContextSize(): Int =
        if (maxContextSize > 0) maxContextSize else DEFAULT_MAX_CTX

    companion object {
        const val DEFAULT_MAX_CTX: Int = 204_800
    }
}
