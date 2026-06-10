package com.my.knowledge.data.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VolcengineTranscriptParserTest {
    private val parser = VolcengineTranscriptParser()

    @Test
    fun `partial is previewed and final is emitted once`() {
        val partial = parser.parse(payload(utterance("你好世", 0, 800, false)))
        assertEquals(emptyList<String>(), partial.finalized)
        assertEquals("你好世", partial.partial)

        val final = parser.parse(payload(utterance("你好世界", 0, 1000, true)))
        assertEquals(listOf("你好世界"), final.finalized)
        assertEquals("", final.partial)

        assertEquals(emptyList<String>(), parser.parse(payload(utterance("你好世界", 0, 1000, true))).finalized)
    }

    @Test
    fun `cumulative response only emits newly finalized segments`() {
        val first = parser.parse(
            payload(
                utterance("第一句。", 0, 1000, true),
                utterance("第二", 1000, 1600, false)
            )
        )
        assertEquals(listOf("第一句。"), first.finalized)
        assertEquals("第二", first.partial)

        val second = parser.parse(
            payload(
                utterance("第一句。", 0, 1000, true),
                utterance("第二句。", 1000, 2000, true)
            )
        )
        assertEquals(listOf("第二句。"), second.finalized)
        assertEquals("", second.partial)
    }

    @Test
    fun `reset allows a new session to reuse timestamps`() {
        val json = payload(utterance("新会话", 0, 1000, true))
        assertEquals(listOf("新会话"), parser.parse(json).finalized)
        assertEquals(emptyList<String>(), parser.parse(json).finalized)
        parser.reset()
        assertEquals(listOf("新会话"), parser.parse(json).finalized)
    }

    private fun payload(vararg utterances: JSONObject): JSONObject =
        JSONObject().put(
            "result",
            JSONObject()
                .put("text", utterances.joinToString("") { it.getString("text") })
                .put("utterances", JSONArray().apply { utterances.forEach(::put) })
        )

    private fun utterance(text: String, start: Long, end: Long, definite: Boolean): JSONObject =
        JSONObject()
            .put("text", text)
            .put("start_time", start)
            .put("end_time", end)
            .put("definite", definite)
}
