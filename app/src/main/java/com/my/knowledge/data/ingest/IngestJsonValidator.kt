package com.my.knowledge.data.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object IngestJsonValidator {
    private val json = Json { ignoreUnknownKeys = true }

    fun repairJsonObject(raw: String): String {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val objectStart = trimmed.indexOf('{')
        val objectEnd = trimmed.lastIndexOf('}')
        val body = if (objectStart >= 0 && objectEnd > objectStart) {
            trimmed.substring(objectStart, objectEnd + 1)
        } else {
            trimmed
        }
        return body
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace(Regex(",\\s*}"), "}")
            .replace(Regex(",\\s*]"), "]")
    }

    fun parseObjectOrNull(raw: String): JsonObject? {
        return runCatching {
            json.parseToJsonElement(repairJsonObject(raw)).jsonObject
        }.getOrNull()
    }

    fun validateAnalysisJson(raw: String): Boolean {
        val obj = parseObjectOrNull(raw) ?: return false
        val required = listOf(
            "title",
            "summary",
            "tags",
            "entities",
            "concepts",
            "relations",
            "claims",
            "gaps",
            "archiveRecommendation",
            "needHumanReview",
            "reviewReasons"
        )
        return required.all { obj.containsKey(it) }
    }

    fun normalizeAnalysisJson(raw: String?, fallback: String): String {
        val fallbackObj = parseObjectOrNull(fallback) ?: return fallback
        val rawObj = raw?.let { parseObjectOrNull(it) } ?: return fallback
        return kotlinx.serialization.json.buildJsonObject {
            put("title", rawObj["title"] ?: fallbackObj["title"]!!)
            put("summary", rawObj["summary"] ?: fallbackObj["summary"]!!)
            put("tags", rawObj["tags"]?.takeIf { it is JsonArray } ?: fallbackObj["tags"]!!)
            put("entities", rawObj["entities"]?.takeIf { it is JsonArray } ?: fallbackObj["entities"]!!)
            put("concepts", rawObj["concepts"]?.takeIf { it is JsonArray } ?: fallbackObj["concepts"]!!)
            put("relations", rawObj["relations"]?.takeIf { it is JsonArray } ?: fallbackObj["relations"]!!)
            put("claims", rawObj["claims"]?.takeIf { it is JsonArray } ?: fallbackObj["claims"]!!)
            put("gaps", rawObj["gaps"]?.takeIf { it is JsonArray } ?: fallbackObj["gaps"]!!)
            put("archiveRecommendation", rawObj["archiveRecommendation"]?.takeIf { it is JsonObject } ?: fallbackObj["archiveRecommendation"]!!)
            put("confidence", rawObj["confidence"] ?: fallbackObj["confidence"]!!)
            put("needHumanReview", rawObj["needHumanReview"] ?: fallbackObj["needHumanReview"]!!)
            put("reviewReasons", rawObj["reviewReasons"]?.takeIf { it is JsonArray } ?: fallbackObj["reviewReasons"]!!)
        }.toString()
    }

    fun fallbackAnalysisJson(
        title: String,
        summary: String,
        tagsJson: String,
        confidence: Float,
        reviewReason: String?
    ): String {
        val escapedTitle = title.escapeJson()
        val escapedSummary = summary.escapeJson()
        val reasons = reviewReason?.let { "[\"${it.escapeJson()}\"]" } ?: "[]"
        val needReview = confidence < 0.6f
        return """
            {
              "title": "$escapedTitle",
              "summary": "$escapedSummary",
              "tags": $tagsJson,
              "entities": [],
              "concepts": $tagsJson,
              "relations": [],
              "claims": [],
              "gaps": $reasons,
              "archiveRecommendation": {
                "targetKnowledgeBaseId": null,
                "targetKnowledgeBaseName": "",
                "confidence": $confidence,
                "reason": "本地规则兜底",
                "suggestCreateNewBase": false,
                "newBaseName": null
              },
              "needHumanReview": $needReview,
              "reviewReasons": $reasons
            }
        """.trimIndent()
    }

    fun string(obj: JsonObject, key: String, fallback: String = ""): String =
        obj[key]?.jsonPrimitive?.content ?: fallback

    fun stringOrNull(obj: JsonObject, key: String): String? =
        obj[key]?.jsonPrimitive?.contentOrNull

    fun float(obj: JsonObject, key: String, fallback: Float): Float =
        obj[key]?.jsonPrimitive?.floatOrNull ?: fallback

    fun arrayAsJson(obj: JsonObject, key: String): String =
        when (val value = obj[key]) {
            is JsonArray -> value.toString()
            null -> "[]"
            else -> "[]"
        }

    fun archiveRecommendationJson(obj: JsonObject, fallback: String): String =
        obj["archiveRecommendation"]?.jsonObject?.toString() ?: fallback

    fun reviewReason(obj: JsonObject): String? {
        val reasons = obj["reviewReasons"] as? JsonArray ?: return null
        return reasons.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
    }

    fun firstJsonArrayText(raw: String): String? {
        val array = runCatching {
            json.parseToJsonElement(raw) as? JsonArray
        }.getOrNull() ?: return null
        return array.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
