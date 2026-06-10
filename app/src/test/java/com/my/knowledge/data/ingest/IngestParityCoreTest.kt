package com.my.knowledge.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestParityCoreTest {
    @Test
    fun `source identity includes folder and source summary path is collision resistant`() {
        val first = IngestParityCore.sourceIdentity("paper.md", "raw/sources/a")
        val second = IngestParityCore.sourceIdentity("paper.md", "raw/sources/b")
        assertEquals("raw/sources/a/paper.md", first)
        assertFalse(IngestParityCore.sourceSummarySlug(first) == IngestParityCore.sourceSummarySlug(second))
    }

    @Test
    fun `analysis prompt requests markdown rather than json`() {
        val prompt = IngestParityCore.analysisPrompt("purpose", "index", "en")
        assertTrue(prompt.contains("## Key Entities"))
        assertFalse(prompt.contains("Return JSON only"))
    }

    @Test
    fun `semantic chunks retain overlap and stable numbering`() {
        val content = (1..30).joinToString("\n\n") { "## Section $it\n" + "body ".repeat(120) }
        val chunks = IngestParityCore.splitSourceIntoSemanticChunks(content, 2_000, 200)
        assertTrue(chunks.size > 1)
        assertEquals(1, chunks.first().index)
        assertEquals(chunks.size, chunks.last().total)
        assertTrue(chunks.drop(1).all { it.overlapBefore.isNotBlank() })
    }

    @Test
    fun `merge guard rejects missing frontmatter and severe body shrink`() {
        val existing = "---\ntitle: A\n---\n" + "old ".repeat(100)
        val incoming = "---\ntitle: A\n---\n" + "new ".repeat(100)
        assertFalse(IngestParityCore.acceptsLlmMerge(existing, incoming, "short"))
        assertFalse(IngestParityCore.acceptsLlmMerge(existing, incoming, "---\ntitle: A\n---\nshort"))
        assertTrue(IngestParityCore.acceptsLlmMerge(existing, incoming, incoming))
    }
}
