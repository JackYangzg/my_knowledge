package com.my.knowledge.data.search

data class KnowledgeSearchResult(
    val itemId: String,
    val fragmentId: String?,
    val knowledgeBaseId: String,
    val title: String,
    val snippet: String,
    val sourceType: String,
    val score: Float,
    val matchType: String
)

data class SemanticSearchCandidate(
    val itemId: String,
    val fragmentId: String?,
    val knowledgeBaseId: String,
    val title: String,
    val snippet: String,
    val sourceType: String,
    val embeddingJson: String
)
