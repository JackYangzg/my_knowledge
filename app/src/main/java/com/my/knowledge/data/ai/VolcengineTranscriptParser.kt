package com.my.knowledge.data.ai

import org.json.JSONArray
import org.json.JSONObject

internal data class VoiceTranscriptUpdate(
    val finalized: List<String>,
    val partial: String
)

internal class VolcengineTranscriptParser {
    private val finalizedSegmentKeys = mutableSetOf<String>()

    fun reset() {
        finalizedSegmentKeys.clear()
    }

    fun parse(payload: JSONObject): VoiceTranscriptUpdate {
        val utterances = findUtterances(payload)
        if (utterances != null) {
            val finalized = mutableListOf<String>()
            var partial = ""
            for (index in 0 until utterances.length()) {
                val utterance = utterances.optJSONObject(index) ?: continue
                val text = utterance.optString("text").trim()
                if (text.isBlank()) continue
                if (utterance.optBoolean("definite", false)) {
                    if (finalizedSegmentKeys.add(segmentKey(utterance, text))) {
                        finalized += text
                    }
                } else {
                    partial = text
                }
            }
            return VoiceTranscriptUpdate(finalized, partial)
        }

        val text = findText(payload).trim()
        if (text.isBlank()) return VoiceTranscriptUpdate(emptyList(), "")
        return if (isFinalPayload(payload)) {
            VoiceTranscriptUpdate(
                finalized = if (finalizedSegmentKeys.add("text:$text")) listOf(text) else emptyList(),
                partial = ""
            )
        } else {
            VoiceTranscriptUpdate(emptyList(), text)
        }
    }

    private fun segmentKey(utterance: JSONObject, text: String): String {
        val start = utterance.optLong("start_time", Long.MIN_VALUE)
        val end = utterance.optLong("end_time", Long.MIN_VALUE)
        return if (start != Long.MIN_VALUE || end != Long.MIN_VALUE) {
            "$start:$end"
        } else {
            "text:$text"
        }
    }

    private fun findUtterances(value: Any?): JSONArray? = when (value) {
        is JSONObject -> value.optJSONArray("utterances")
            ?: value.keys().asSequence()
                .mapNotNull { key -> findUtterances(value.opt(key)) }
                .firstOrNull()
        is JSONArray -> (0 until value.length())
            .mapNotNull { index -> findUtterances(value.opt(index)) }
            .firstOrNull()
        else -> null
    }

    private fun findText(value: Any?): String = when (value) {
        is JSONObject -> {
            val directKeys = listOf("text", "utterance", "transcript", "sentence")
            directKeys.firstNotNullOfOrNull { key ->
                value.optString(key).takeIf { it.isNotBlank() }
            } ?: value.keys().asSequence()
                .mapNotNull { key -> findText(value.opt(key)).takeIf { it.isNotBlank() } }
                .firstOrNull()
                .orEmpty()
        }
        is JSONArray -> (value.length() - 1 downTo 0)
            .mapNotNull { index -> findText(value.opt(index)).takeIf { it.isNotBlank() } }
            .firstOrNull()
            .orEmpty()
        else -> ""
    }

    private fun isFinalPayload(value: Any?): Boolean = when (value) {
        is JSONObject -> listOf("definite", "is_final", "final").any { key ->
            value.has(key) && value.optBoolean(key, false)
        } || value.keys().asSequence().any { key -> isFinalPayload(value.opt(key)) }
        is JSONArray -> (0 until value.length()).any { index -> isFinalPayload(value.opt(index)) }
        else -> false
    }
}
