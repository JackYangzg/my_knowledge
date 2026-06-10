package com.my.knowledge.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the duplicate-import fix (B2: "re-importing an
 * already-imported document creates new records / no prompt").
 *
 * The full behavioural test for [ImportSourceUseCase] would need a
 * Robolectric harness to fake [com.my.knowledge.data.file.LocalFileStore]
 * (which depends on `android.content.Context`) and the four Room DAOs.
 * Until that infrastructure is in place, this test pins the public
 * contract of the new [ImportResult] type so a future refactor
 * doesn't silently regress the duplicate signal.
 *
 * If the test fails, callers can no longer tell whether a given
 * import call wrote a fresh `SourceDocumentEntity` or short-circuited
 * via the sha256 dedupe — that would re-introduce the user-visible
 * "already imported but no prompt" bug.
 */
class ImportSourceUseCaseContractTest {

    @Test
    fun `fresh import result has isDuplicate false`() {
        val result = ImportResult(sourceId = "src-new-1", isDuplicate = false)
        assertFalse(result.isDuplicate)
        assertEquals("src-new-1", result.sourceId)
    }

    @Test
    fun `duplicate import result has isDuplicate true and preserves existing sourceId`() {
        // After a dedupe hit, ImportSourceUseCase returns the EXISTING
        // source id, not a new one. The UI uses this to render the
        // "已导入" toast without re-pathing to a new source.
        val existing = ImportResult(sourceId = "src-existing-42", isDuplicate = true)
        assertTrue(existing.isDuplicate)
        assertEquals("src-existing-42", existing.sourceId)
    }

    @Test
    fun `fresh and duplicate results are distinguishable by isDuplicate`() {
        val fresh = ImportResult(sourceId = "src-1", isDuplicate = false)
        val dup = ImportResult(sourceId = "src-1", isDuplicate = true)
        // Same sourceId shape, but the UI must branch on isDuplicate.
        assertNotEquals(fresh.isDuplicate, dup.isDuplicate)
    }

    @Test
    fun `data class equality is value based`() {
        // KnowledgeHomeViewModel deduplication / Snackbar gating
        // relies on the data class equals / hashCode. Pin that
        // semantics so a refactor to a regular class would be loud.
        val a = ImportResult(sourceId = "src-7", isDuplicate = true)
        val b = ImportResult(sourceId = "src-7", isDuplicate = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
