package com.my.knowledge.data.ingest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ARCH-7 / PR1 (§3.2 schema + §3.3 chunk prompt split).
 * Locks two contracts:
 *   - [IngestOrchestrator.ANALYSIS_SCHEMA] no longer carries the
 *     deprecated / dead field markers (entities.type,
 *     concepts.category, source_refs, evidenceFragmentIds);
 *   - the chunk analysis system prompt returns a *full* prompt
 *     for chunk 1 and a *compact* prefix for chunks 2..N that
 *     carries the prior chunk digest.
 */
class IngestOrchestratorChunkPromptTest {
    @Test
    fun analysisSchema_omitsDeprecatedFields() {
        val s = IngestOrchestrator.ANALYSIS_SCHEMA
        assertFalse("DEPRECATED alias marker must be gone", s.contains("DEPRECATED"))
        // 精确匹配 `type` / `category` 的 DEPRECATED 别名形态:
        // schema 里仍保留 `relations.type`(enum)和 `pageRecommendations.type`(enum),
        // 所以不能用 `s.contains("\"type\":")` 这种会误伤的全局子串。
        assertFalse("entities.type alias must be gone", s.contains("\"type\":\"DEPRECATED"))
        assertFalse("concepts.category alias must be gone", s.contains("\"category\":\"DEPRECATED"))
        assertFalse("source_refs field must be gone", s.contains("source_refs"))
        assertFalse("evidenceFragmentIds field must be gone", s.contains("evidenceFragmentIds"))
    }

    @Test
    fun analysisSchema_keepsPrimaryFields() {
        val s = IngestOrchestrator.ANALYSIS_SCHEMA
        assertTrue(s.contains("\"entityType\""))
        assertTrue(s.contains("\"conceptCategory\""))
        assertTrue(s.contains("\"summary\""))
    }

    @Test
    fun chunk1SystemPrompt_containsFullContext() {
        val s = buildChunkAnalysisSystemPrompt(
            purpose = "test purpose",
            schema = "",
            index = "- foo (entity)",
            language = "中文",
            chunkTotal = 3,
            chunkIndex = 1,
            globalDigest = ""
        )
        assertTrue("chunk 1 must include Wiki Purpose",
            s.contains("Wiki Purpose") || s.contains("test purpose"))
        assertTrue("chunk 1 must include index items", s.contains("foo (entity)"))
    }

    @Test
    fun chunkNSystemPrompt_usesCompactPrefix() {
        val s = buildChunkAnalysisSystemPrompt(
            purpose = "test purpose",
            schema = "",
            index = "- foo (entity)",
            language = "中文",
            chunkTotal = 3,
            chunkIndex = 2,
            globalDigest = "summary of prior chunk"
        )
        assertTrue("chunk N must include digest", s.contains("summary of prior chunk"))
        assertTrue("chunk N must signal continuation",
            s.contains("Continue the same") || s.contains("Maintain"))
    }

    @Test
    fun chunkNSystemPrompt_isSmallerThanChunk1() {
        val bigIndex = (1..50).joinToString("\n") { "- page$it (entity): summary" }
        val c1 = buildChunkAnalysisSystemPrompt(
            purpose = "p", schema = "", index = bigIndex, language = "中文",
            chunkTotal = 3, chunkIndex = 1, globalDigest = ""
        )
        val c2 = buildChunkAnalysisSystemPrompt(
            purpose = "p", schema = "", index = bigIndex.take(2_000), language = "中文",
            chunkTotal = 3, chunkIndex = 2, globalDigest = "digest"
        )
        assertTrue("chunk N should be smaller than chunk 1: c1=${c1.length}, c2=${c2.length}",
            c2.length < c1.length)
    }
}
