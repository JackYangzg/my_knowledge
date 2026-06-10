package com.my.knowledge.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiDedupParityTest {
    private val page = """
        ---
        type: concept
        title: Volatile Fatty Acids
        updated: 2026-01-01
        tags: [water]
        related: [vfa, reactor]
        sources: [a.md]
        ---
        # VFA

        See [[vfa]] and [[vfa|the acids]].
    """.trimIndent()

    @Test
    fun `detector response validates slugs and ignores whitelisted groups`() {
        val raw = """prefix {"groups":[{"slugs":["vfa","volatile-fatty-acids"],"reason":"alias","confidence":"high"},{"slugs":["invented","vfa"],"confidence":"medium"}]} suffix"""
        val groups = WikiDedupParity.parseDetectorResponse(
            raw,
            setOf("vfa", "volatile-fatty-acids"),
            listOf(listOf("OTHER", "group")),
        )
        assertEquals(1, groups.size)
        assertEquals("high", groups.single().confidence)
        val blocked = WikiDedupParity.parseDetectorResponse(
            raw,
            setOf("vfa", "volatile-fatty-acids"),
            listOf(listOf("VOLATILE-FATTY-ACIDS", "VFA")),
        )
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `reference rewrite preserves alias and deduplicates related`() {
        val rewritten = WikiDedupParity.rewriteCrossReferences(
            page,
            mapOf("vfa" to "volatile-fatty-acids"),
        )
        assertTrue(rewritten.contains("[[volatile-fatty-acids]]"))
        assertTrue(rewritten.contains("[[volatile-fatty-acids|the acids]]"))
        assertTrue(rewritten.contains("related: [volatile-fatty-acids, reactor]"))
    }

    @Test
    fun `confirmed merge returns backup rewrites and deletions`() {
        val canonical = WikiDedupPage("volatile-fatty-acids", "wiki/concepts/volatile-fatty-acids.md", page)
        val duplicate = WikiDedupPage("vfa", "wiki/concepts/vfa.md", page.replace("a.md", "b.md"))
        val other = WikiDedupPage("reactor", "wiki/entities/reactor.md", page)
        val result = WikiDedupParity.mergeConfirmedGroup(
            listOf(canonical, duplicate),
            canonical.slug,
            listOf(other),
            page,
        )
        assertEquals(canonical.path, result.canonicalPath)
        assertEquals(listOf(duplicate.path), result.pagesToDelete)
        assertFalse(result.rewrites.isEmpty())
        assertEquals(3, result.backup.size)
        assertTrue(result.canonicalContent.contains("sources: [a.md, b.md]"))
    }
}
