package com.my.knowledge.domain.model

val KNOWLEDGE_CONCEPT_TYPE_NAMES = setOf(
    "concept",
    "method",
    "technique",
    "theory",
    "principle",
    "framework",
    "problem",
    "pattern",
    "protocol",
    "metric",
    "algorithm",
    "mechanism",
    "model",
    "process",
    "heuristic",
    "phenomenon",
    "category"
)

fun normalizeKnowledgeEntityType(type: String): String {
    val normalized = type.trim().lowercase().ifBlank { "entity" }
    return normalized.substringAfter(':', normalized).ifBlank { "entity" }
}

fun isKnowledgeConceptType(type: String): Boolean {
    val normalized = type.trim().lowercase()
    return normalized.startsWith("concept:") ||
        (!normalized.startsWith("entity:") &&
            normalizeKnowledgeEntityType(normalized) in KNOWLEDGE_CONCEPT_TYPE_NAMES)
}

fun knowledgeEntityTopLevelKind(type: String): String =
    if (isKnowledgeConceptType(type)) "concept" else "entity"
