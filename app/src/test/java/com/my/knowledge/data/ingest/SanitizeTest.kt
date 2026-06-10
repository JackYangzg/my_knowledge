package com.my.knowledge.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the 4 sanitize patterns from llm_wiki's ingest-sanitize.ts.
 * Each test corresponds to one of the documented corruption shapes.
 */
class SanitizeTest {

    @Test
    fun stripOuterCodeFence_yaml() {
        val input = "```yaml\n---\ntype: entity\n---\n# Body\n```\n"
        val out = Sanitize.sanitize(input)
        assertEquals("---\ntype: entity\n---\n# Body\n", out)
    }

    @Test
    fun stripOuterCodeFence_md() {
        val input = "```md\n---\ntype: source\n---\nBody.\n```\n"
        val out = Sanitize.sanitize(input)
        assertEquals("---\ntype: source\n---\nBody.\n", out)
    }

    @Test
    fun stripOuterCodeFence_bareTripleBacktick() {
        val input = "```\n---\ntype: concept\n---\nBody.\n```\n"
        val out = Sanitize.sanitize(input)
        assertEquals("---\ntype: concept\n---\nBody.\n", out)
    }

    @Test
    fun stripOuterCodeFence_doesNotTouchInlineFences() {
        // Only acts when the FIRST line is an opening fence and the LAST is a closer.
        val input = "---\ntype: entity\n---\n# Body\n\n```python\nprint('hi')\n```\n"
        val out = Sanitize.sanitize(input)
        assertEquals(input, out)
    }

    @Test
    fun stripFrontmatterKeyPrefix() {
        val input = "frontmatter:\n---\ntype: entity\n---\n# Body\n"
        val out = Sanitize.sanitize(input)
        assertEquals("---\ntype: entity\n---\n# Body\n", out)
    }

    @Test
    fun stripFrontmatterKeyPrefix_doesNotTouchInlineMention() {
        val input = "---\ntype: entity\ntitle: \"frontmatter: rules\"\n---\n"
        val out = Sanitize.sanitize(input)
        assertEquals(input, out)
    }

    @Test
    fun addMissingOpeningFrontmatterFence() {
        val input = "type: entity\ntitle: Foo\ncreated: 2026-06-10\n---\n# Body\n"
        val out = Sanitize.sanitize(input)
        assertEquals("---\ntype: entity\ntitle: Foo\ncreated: 2026-06-10\n---\n# Body\n", out)
    }

    @Test
    fun addMissingOpeningFrontmatterFence_noopWhenOpeningAlreadyPresent() {
        val input = "---\ntype: entity\n---\n# Body\n"
        val out = Sanitize.sanitize(input)
        assertEquals(input, out)
    }

    @Test
    fun addMissingOpeningFrontmatterFence_noopWhenNoFrontmatter() {
        val input = "# Just a heading\n\nSome prose.\n"
        val out = Sanitize.sanitize(input)
        assertEquals(input, out)
    }

    @Test
    fun repairWikilinkListsInFrontmatter() {
        val input = "---\ntype: concept\nrelated: [[a]], [[b]], [[c]]\n---\n# Body\n"
        val out = Sanitize.sanitize(input)
        assertEquals("---\ntype: concept\nrelated: [\"a\", \"b\", \"c\"]\n---\n# Body\n", out)
    }

    @Test
    fun repairWikilinkListsInFrontmatter_bodyWikilinksUntouched() {
        val input = "---\ntype: entity\n---\n# See [[other-entity]] for details\n"
        val out = Sanitize.sanitize(input)
        assertEquals(input, out)
    }

    @Test
    fun sanitize_chainOrderAppliesAllPatterns() {
        // Realistic corruption shape from llm_wiki's ingest-sanitize.ts docs:
        // outer ```yaml ``` + leading `frontmatter:` key + valid frontmatter
        // block (opening + closing fences) + body wikilink lists.
        // Each pattern applies in order.
        val input = "```yaml\n" +
            "frontmatter:\n" +
            "---\n" +
            "type: entity\n" +
            "related: [[a]], [[b]]\n" +
            "---\n" +
            "# Body\n" +
            "```\n"
        val out = Sanitize.sanitize(input)
        assertEquals(
            "---\n" +
            "type: entity\n" +
            "related: [\"a\", \"b\"]\n" +
            "---\n" +
            "# Body\n",
            out
        )
    }

    @Test
    fun sanitize_emptyInputReturnsEmpty() {
        assertEquals("", Sanitize.sanitize(""))
    }

    @Test
    fun sanitize_validInputIsUnchanged() {
        val input = "---\ntype: entity\ntitle: \"Foo: Bar\"\ntags: [microbiology, ai]\n---\n# Body\n"
        val out = Sanitize.sanitize(input)
        assertEquals(input, out)
    }
}