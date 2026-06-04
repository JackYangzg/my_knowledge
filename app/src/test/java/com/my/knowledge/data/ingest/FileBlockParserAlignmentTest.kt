package com.my.knowledge.data.ingest

import com.my.knowledge.data.ai.AiPromptTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Android ingest parser / prompt against drifting away from
 * llm_wiki's ingest contract:
 *
 * - FILE blocks are line-oriented, CRLF-tolerant, and fence-aware.
 * - unsafe paths are rejected at the parse boundary.
 * - Stage 2 prompt requires FILE/REVIEW blocks and head+tail language pins.
 */
class FileBlockParserAlignmentTest {

    @Test
    fun `parseDetailed accepts CRLF markers and whitespace variants`() {
        val text = "--- FILE: wiki/concepts/raft.md ---\r\n# Raft\r\n--- END FILE ---\r\n"

        val result = FileBlockParser.parseDetailed(text)

        assertEquals(emptyList<String>(), result.unsafePaths)
        assertFalse(result.truncated)
        assertEquals(1, result.blocks.size)
        assertEquals("wiki/concepts/raft.md", result.blocks.single().path)
        assertEquals("# Raft", result.blocks.single().content)
    }

    @Test
    fun `parseDetailed ignores END FILE inside fenced code`() {
        val text = """
            ---FILE: wiki/concepts/parser-contract.md---
            # Parser Contract

            ```text
            ---END FILE---
            ```

            Still inside the real file body.
            ---END FILE---
        """.trimIndent()

        val result = FileBlockParser.parseDetailed(text)

        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.single().content.contains("Still inside the real file body."))
        assertTrue(result.blocks.single().content.contains("---END FILE---"))
    }

    @Test
    fun `parseDetailed rejects paths outside wiki tree`() {
        val text = """
            ---FILE: ../../evil.md---
            bad
            ---END FILE---

            ---FILE: wiki/entities/safe.md---
            good
            ---END FILE---
        """.trimIndent()

        val result = FileBlockParser.parseDetailed(text)

        assertEquals(listOf("../../evil.md"), result.unsafePaths)
        assertEquals(1, result.blocks.size)
        assertEquals("wiki/entities/safe.md", result.blocks.single().path)
    }

    @Test
    fun `generationPrompt preserves llm wiki FILE REVIEW contract`() {
        val prompt = AiPromptTemplates.generationPrompt(
            fileName = "raft.md",
            analysisResult = "Raft analysis",
            sourceContent = "Raft source",
            schema = "Use default wiki routing.",
            currentIndex = "No existing wiki pages.",
            overview = "",
            language = "中文",
        )

        assertTrue(prompt.contains("FILE block template:"))
        assertTrue(prompt.contains("---FILE: wiki/path/to/page.md---"))
        assertTrue(prompt.contains("---END FILE---"))
        assertTrue(prompt.contains("REVIEW block template"))
        assertTrue(prompt.contains("The FIRST character of your response MUST be `-`"))
        assertTrue(prompt.contains("All wiki pages generated from this source MUST include this filename"))
        assertTrue(prompt.contains("Project Schema and Routing"))
        assertTrue(prompt.contains("wiki/sources/raft.md"))

        val headIdx = prompt.indexOf("MANDATORY OUTPUT LANGUAGE")
        val tailIdx = prompt.lastIndexOf("MANDATORY OUTPUT LANGUAGE")
        assertTrue(headIdx >= 0)
        assertTrue(tailIdx > headIdx)
    }

    @Test
    fun `analysisPrompt does not promise local entity heuristic supplements`() {
        val prompt = AiPromptTemplates.analysisPrompt(
            title = "raft.md",
            currentIndex = "No existing wiki pages.",
            language = "中文",
        )

        assertTrue(prompt.contains("There is no\nlocal entity/concept supplement"))
        assertTrue(prompt.contains("will preserve those empty arrays"))
        assertFalse(prompt.contains("local heuristic extracts high-frequency noun phrases"))
    }
}
