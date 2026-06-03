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
            currentIndex = "No existing wiki pages.",
            overview = "",
            language = "中文",
        )

        assertTrue(prompt.contains("FILE block template:"))
        assertTrue(prompt.contains("---FILE: wiki/path/to/page.md---"))
        assertTrue(prompt.contains("---END FILE---"))
        assertTrue(prompt.contains("REVIEW block template"))
        assertTrue(prompt.contains("响应的**第一个字符**必须是 `-`"))
        assertTrue(prompt.contains("sources` 字段中包含这个文件名"))
        assertTrue(prompt.contains("wiki/sources/raft.md"))

        val headIdx = prompt.indexOf("MANDATORY OUTPUT LANGUAGE")
        val tailIdx = prompt.lastIndexOf("MANDATORY OUTPUT LANGUAGE")
        assertTrue(headIdx >= 0)
        assertTrue(tailIdx > headIdx)
    }
}
