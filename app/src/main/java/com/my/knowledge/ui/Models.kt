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

@Serializable
data class ModelConfig(
    val provider: String = "OpenAI",
    val modelName: String = "gpt-4o",
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com/v1"
)
