package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.AnalysisResultEntity
import com.my.knowledge.data.db.entity.ParsedContentEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for "duplicate entity / concept pages with
 * whitespace-only name differences".
 *
 * The original `WikiPageCompiler.parseNamedObjects` keyed its
 * dedup on `it.name.lowercase()` only — which catches case
 * variation but NOT leading / trailing / internal whitespace
 * differences, NOR Unicode whitespace (NBSP, U+3000 ideographic
 * space, etc.) that LLMs occasionally emit between CJK or
 * English tokens. Same for the matching dedup points further
 * down the pipeline (`rebuildGraphForBase`,
 * `preferAiFileBlocks`, `mergeEntityOrConceptByName`, the
 * long-source `collectNamedObjects`).
 *
 * Symptom the user reported: the same entity / concept
 * appeared twice in the wiki library — once as "Foo Bar" and
 * once as " Foo Bar" (or "Foo  Bar" / "Foo Bar"). The
 * graph view would show two separate nodes that should have
 * been one.
 *
 * These tests pin the fix in `WikiPageCompiler.compile()`:
 * the compiler must collapse whitespace variants of the same
 * logical name down to ONE entity / concept page per
 * (source, analysis) call. The graph / merge / long-source
 * dedup points are tested elsewhere via the equivalent helper
 * calls; the single canonical normalize helper
 * [EntityName.canonical] / [EntityName.dedupKey] backs them
 * all.
 */
class WikiPageCompilerEntityDedupTest {

    private fun source(): SourceDocumentEntity = SourceDocumentEntity(
        id = "src-1",
        sourceType = "text",
        title = "Sample",
        originalUri = null,
        localPath = null,
        mimeType = "text/markdown",
        sizeBytes = null,
        sha256 = "deadbeef",
        importFrom = null,
        folderHint = null,
        status = SourceDocumentEntity.STATUS_GENERATED,
        errorMessage = null,
        targetKnowledgeBaseId = "kb-1",
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun parsed(): ParsedContentEntity = ParsedContentEntity(
        id = "parsed-1",
        sourceId = "src-1",
        parserType = "markdown",
        markdown = "# body",
        plainText = "body",
        parseHash = "hash",
        metadataJson = "{}",
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun analysisWithEntitiesJson(entitiesJson: String): AnalysisResultEntity =
        AnalysisResultEntity(
            id = "analysis-1",
            sourceId = "src-1",
            parsedContentId = "parsed-1",
            summary = "summary",
            tagsJson = "[]",
            entitiesJson = entitiesJson,
            conceptsJson = "[]",
            relationsJson = "[]",
            claimsJson = "[]",
            gapsJson = "[]",
            archiveRecommendationJson = "{}",
            confidence = 0.9f,
            modelName = null,
            promptVersion = "v1",
            analysisHash = "ahash",
            createdAt = 0L
        )

    private fun analysisWithBoth(entitiesJson: String, conceptsJson: String): AnalysisResultEntity =
        analysisWithEntitiesJson(entitiesJson).copy(conceptsJson = conceptsJson)

    @Test
    fun `leading and trailing whitespace in entity name is collapsed to one page`() {
        val json = """[
            {"name":"Foo Bar","description":"a real entity"},
            {"name":" Foo Bar ","description":"a duplicate with extra spaces"}
        ]""".trimIndent()
        val drafts = WikiPageCompiler().compile(source(), parsed(), analysisWithEntitiesJson(json))
        val entityPages = drafts.filter { it.sourceType == "wiki_entity" }
        assertEquals(
            "Two whitespace-variants of the same entity must collapse to ONE wiki page; got ${entityPages.map { it.title }}",
            1, entityPages.size
        )
        assertEquals("Foo Bar", entityPages.single().title)
    }

    @Test
    fun `internal double-space in entity name is collapsed to one page`() {
        val json = """[
            {"name":"Foo Bar","description":"first"},
            {"name":"Foo  Bar","description":"second with double space"}
        ]""".trimIndent()
        val drafts = WikiPageCompiler().compile(source(), parsed(), analysisWithEntitiesJson(json))
        val entityPages = drafts.filter { it.sourceType == "wiki_entity" }
        assertEquals(
            "Two whitespace-variants of the same entity must collapse to ONE wiki page; got ${entityPages.map { it.title }}",
            1, entityPages.size
        )
        assertEquals("Foo Bar", entityPages.single().title)
    }

    @Test
    fun `NBSP between entity name tokens is collapsed to one page`() {
        val json = """[
            {"name":"Foo Bar","description":"first"},
            {"name":"Foo Bar","description":"second with NBSP"}
        ]""".trimIndent()
        val drafts = WikiPageCompiler().compile(source(), parsed(), analysisWithEntitiesJson(json))
        val entityPages = drafts.filter { it.sourceType == "wiki_entity" }
        assertEquals(
            "NBSP-variant of an entity name must collapse to ONE wiki page; got ${entityPages.map { it.title }}",
            1, entityPages.size
        )
        assertEquals("Foo Bar", entityPages.single().title)
    }

    @Test
    fun `CJK ideographic-space in entity name is collapsed to one page`() {
        // Both inputs carry a whitespace boundary between the two CJK
        // tokens; one uses two ASCII spaces, the other uses U+3000
        // (IDEOGRAPHIC SPACE). `EntityName.canonical` folds both to
        // a single ASCII space, so the compiler should emit one
        // entity page titled "一致性 哈希".
        val json = """[
            {"name":"一致性  哈希","description":"first, double ASCII space"},
            {"name":"一致性　哈希","description":"second, CJK ideographic space"}
        ]""".trimIndent()
        val drafts = WikiPageCompiler().compile(source(), parsed(), analysisWithEntitiesJson(json))
        val entityPages = drafts.filter { it.sourceType == "wiki_entity" }
        assertEquals(
            "CJK whitespace-variants of an entity name must collapse to ONE wiki page; got ${entityPages.map { it.title }}",
            1, entityPages.size
        )
        assertEquals("一致性 哈希", entityPages.single().title)
    }

    @Test
    fun `concepts dedup the same way as entities`() {
        val entities = "[]"
        val concepts = """[
            {"name":"Distributed Consensus","definition":"a real concept"},
            {"name":"  Distributed  Consensus  ","definition":"same with extra spaces"}
        ]""".trimIndent()
        val drafts = WikiPageCompiler().compile(source(), parsed(), analysisWithBoth(entities, concepts))
        val conceptPages = drafts.filter { it.sourceType == "wiki_concept" }
        assertEquals(
            "Concept whitespace-variants must collapse to ONE wiki page; got ${conceptPages.map { it.title }}",
            1, conceptPages.size
        )
        assertEquals("Distributed Consensus", conceptPages.single().title)
    }

    @Test
    fun `case-only difference is still deduped (no regression on existing behavior)`() {
        val json = """[
            {"name":"Foo Bar","description":"first"},
            {"name":"foo bar","description":"same name, lower case"}
        ]""".trimIndent()
        val drafts = WikiPageCompiler().compile(source(), parsed(), analysisWithEntitiesJson(json))
        val entityPages = drafts.filter { it.sourceType == "wiki_entity" }
        assertEquals(
            "Case-variant of an entity name must collapse to ONE wiki page (regression check); got ${entityPages.map { it.title }}",
            1, entityPages.size
        )
    }

    @Test
    fun `genuinely different entity names are NOT collapsed`() {
        val json = """[
            {"name":"Foo Bar","description":"first"},
            {"name":"Baz Qux","description":"a different entity"}
        ]""".trimIndent()
        val drafts = WikiPageCompiler().compile(source(), parsed(), analysisWithEntitiesJson(json))
        val entityPages = drafts.filter { it.sourceType == "wiki_entity" }
        assertEquals(
            "Two distinct entity names must remain two pages; got ${entityPages.map { it.title }}",
            2, entityPages.size
        )
        val titles = entityPages.map { it.title }.toSet()
        assertTrue("Foo Bar" in titles)
        assertTrue("Baz Qux" in titles)
    }

    @Test
    fun `canonical name preserves original casing for display`() {
        val json = """[
            {"name":"iOS","description":"apple os"},
            {"name":" iOS ","description":"same with extra spaces"}
        ]""".trimIndent()
        val drafts = WikiPageCompiler().compile(source(), parsed(), analysisWithEntitiesJson(json))
        val entityPages = drafts.filter { it.sourceType == "wiki_entity" }
        assertEquals(
            "Normalization must collapse whitespace but keep display casing; got ${entityPages.map { it.title }}",
            1, entityPages.size
        )
        assertEquals(
            "Display title should preserve the first-seen casing (iOS), not lowercase it",
            "iOS", entityPages.single().title
        )
    }

    @Test
    fun `dedup key is case-insensitive but whitespace-normalized`() {
        // Sanity test on the helper directly: confirms "Foo Bar" and
        // "  foo  bar " share a dedup key, while "Foo Bar" and
        // "FooBarr" do NOT (so a real semantic difference still
        // surfaces as two pages).
        assertEquals(
            EntityName.dedupKey("Foo Bar"),
            EntityName.dedupKey("  foo  bar ")
        )
        assertNotEquals(
            EntityName.dedupKey("Foo Bar"),
            EntityName.dedupKey("FooBarr")
        )
    }
}
