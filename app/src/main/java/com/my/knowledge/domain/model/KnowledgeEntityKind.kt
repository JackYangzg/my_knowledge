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

fun normalizeKnowledgeEntityType(type: String): String =
    type.trim().lowercase().ifBlank { "entity" }

fun isKnowledgeConceptType(type: String): Boolean =
    normalizeKnowledgeEntityType(type) in KNOWLEDGE_CONCEPT_TYPE_NAMES

fun knowledgeEntityTopLevelKind(type: String): String =
    if (isKnowledgeConceptType(type)) "concept" else "entity"
