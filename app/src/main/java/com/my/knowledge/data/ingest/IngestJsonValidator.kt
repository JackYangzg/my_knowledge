package com.my.knowledge.data.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tolerant repair of analysis-JSON returned by LLMs.
 *
 * The previous implementation did:
 *   replace(Regex(",\\s*}"), "}")
 *   replace(Regex(",\\s*]"), "]")
 * on the raw text. This is unsafe: a value such as
 *   "reason": "foo, bar, baz"
 * contains commas inside a string literal, but the regex fires there too and
 * silently corrupts the value. We now walk the text in a string-aware way.
 *
 * We also avoid the crude `first '{' to last '}'` slice: if the model emits
 * a stray `}` inside a string (rare but possible), the slice would clip the
 * body. We instead scan for the first balanced `{` and matching `}`.
 */
object IngestJsonValidator {
    private val json = Json { ignoreUnknownKeys = true }

    fun repairJsonObject(raw: String): String {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val balanced = extractBalancedBraces(trimmed) ?: return trimmed
        return balanced
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
            .let { stripTrailingCommasOutsideStrings(it) }
    }

    /**
     * Return the substring from the first balanced `{` to its matching `}`,
     * or null if no balanced pair is found.
     */
    private fun extractBalancedBraces(text: String): String? {
        val firstOpen = text.indexOf('{')
        if (firstOpen < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        var i = firstOpen
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return text.substring(firstOpen, i + 1)
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Remove `,` immediately followed (only whitespace) by `}` or `]` — but
     * only when those characters appear outside of a string literal. The
     * previous implementation operated on the raw text and would happily
     * delete a comma from inside a string value.
     */
    private fun stripTrailingCommasOutsideStrings(text: String): String {
        val out = StringBuilder(text.length)
        var inString = false
        var escape = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                out.append(c)
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
                i++
                continue
            }
            if (c == '"') {
                inString = true
                out.append(c)
                i++
                continue
            }
            if (c == ',') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && (text[j] == '}' || text[j] == ']')) {
                    // skip the comma, keep going from j
                    i = j
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
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
        // Defensive: every `fallbackObj[key]!!` is a `!`-assertion
        // that used to NPE if a future caller passed a fallback
        // missing one of these fields (the previous version of
        // `fallbackAnalysisJson` was missing `confidence` and the
        // call site threw NPE deep inside this function). We now
        // fall through to a typed default instead so a partial
        // fallback degrades gracefully into "missing field" rather
        // than crashing the whole pipeline.
        return kotlinx.serialization.json.buildJsonObject {
            put("title", rawObj["title"] ?: fallbackObj["title"] ?: JsonPrimitive(""))
            put("summary", rawObj["summary"] ?: fallbackObj["summary"] ?: JsonPrimitive(""))
            put("tags", rawObj["tags"]?.takeIf { it is JsonArray } ?: fallbackObj["tags"] ?: JsonArray(emptyList()))
            put("entities", rawObj["entities"]?.takeIf { it is JsonArray } ?: fallbackObj["entities"] ?: JsonArray(emptyList()))
            put("concepts", rawObj["concepts"]?.takeIf { it is JsonArray } ?: fallbackObj["concepts"] ?: JsonArray(emptyList()))
            put("relations", rawObj["relations"]?.takeIf { it is JsonArray } ?: fallbackObj["relations"] ?: JsonArray(emptyList()))
            put("claims", rawObj["claims"]?.takeIf { it is JsonArray } ?: fallbackObj["claims"] ?: JsonArray(emptyList()))
            put("gaps", rawObj["gaps"]?.takeIf { it is JsonArray } ?: fallbackObj["gaps"] ?: JsonArray(emptyList()))
            put("archiveRecommendation", rawObj["archiveRecommendation"]?.takeIf { it is JsonObject } ?: fallbackObj["archiveRecommendation"] ?: buildJsonObject {})
            put("confidence", rawObj["confidence"] ?: fallbackObj["confidence"] ?: JsonPrimitive(0.5))
            put("needHumanReview", rawObj["needHumanReview"] ?: fallbackObj["needHumanReview"] ?: JsonPrimitive(false))
            put("reviewReasons", rawObj["reviewReasons"]?.takeIf { it is JsonArray } ?: fallbackObj["reviewReasons"] ?: JsonArray(emptyList()))
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
        // NOTE: `concepts` is intentionally `[]`, NOT `$tagsJson`.
        // The previous version of this function filled `concepts`
        // with the same array as `tags`, which mirrored the same
        // bug in the orchestrator's old hard-coded assignment
        // (`conceptsJson = tags.toJsonArray()`). With concepts =
        // tags, the downstream `WikiPageCompiler.compile()` would
        // synthesize "concept" pages whose `name` was a tag and
        // whose `description` was empty. The local fallback has
        // no real concept information, so the right answer is
        // `[]` and let the generation stage's AI path
        // (`requestAiRawOutput`) produce pages from the raw text.
        return """
            {
              "title": "$escapedTitle",
              "summary": "$escapedSummary",
              "tags": $tagsJson,
              "entities": [],
              "concepts": [],
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
              "confidence": $confidence,
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
