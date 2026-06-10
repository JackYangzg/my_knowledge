package com.my.knowledge.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T3 migration test — locks the7-day TTL contract for
 * [LongSourceCheckpointStore.load]. Mirrors the design's
 * "Checkpoint TTL & invalidation spec" §5:
 *
 *   - TTL = 7 days
 *   - checkpoint older than 7d → load returns null (start from chunk 1)
 *   - checkpoint within 7d → load returns the checkpoint as before
 *
 * Boundary semantics:
 *   - now - 7d + 1s → PASS (still within TTL)
 *   - now - 7d - 1ms → FAIL (TTL expired)
 *   - updatedAt = 0 (legacy pre-TTL field) → treat as never-stale
 *     so existing on-disk P0-3 checkpoints aren't mass-purged on first
 *     read after this code lands.
 */
class LongSourceCheckpointStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): LongSourceCheckpointStore = LongSourceCheckpointStore(tmp.root)

    private fun validCheckpoint(
        updatedAt: Long,
        completedThrough: Int = 0,
        analyses: List<String> = emptyList()
    ): LongSourceCheckpoint = LongSourceCheckpoint(
        version = LongSourceCheckpointStore.CHECKPOINT_VERSION,
        sourceIdentity = "src-1",
        sourceHash = "abc123",
        sourceLength = 10_000,
        sourceBudget = 30_000,
        targetChars = 16_500,
        overlapChars = 1_320,
        chunkTotal = 3,
        completedThrough = completedThrough,
        globalDigest = "",
        analyses = analyses,
        updatedAt = updatedAt,
    )

    private fun matchingParams() = LongSourceCheckpointParams(
        sourceIdentity = "src-1",
        sourceHash = "abc123",
        sourceLength = 10_000,
        sourceBudget = 30_000,
        targetChars = 16_500,
        overlapChars = 1_320,
        chunkTotal = 3,
    )

    private fun path(): File =
        store().checkpointPath("my-source", "abc123")

    private fun nowMs(): Long = System.currentTimeMillis()

    @Test
    fun load_returnsCheckpointWhenWithinTtl() {
        val cp = validCheckpoint(updatedAt = nowMs())
        assertTrue("save must succeed", store().save(path(), cp))
        val loaded = store().load(path(), matchingParams())
        assertNotNull("fresh checkpoint must load", loaded)
        assertEquals(0, loaded!!.completedThrough)
    }

    @Test
    fun load_returnsNullWhenCheckpointIsStale() {
        val eightDaysAgoMs = nowMs() - (7L * 24 * 60 * 60 * 1000) - 1
        val cp = validCheckpoint(
            updatedAt = eightDaysAgoMs,
            completedThrough = 2,
            analyses = listOf("chunk1 analysis", "chunk2 analysis"),
        )
        assertTrue("save must succeed", store().save(path(), cp))
        val loaded = store().load(path(), matchingParams())
        assertNull("checkpoint older than 7d must return null", loaded)
    }

    @Test
    fun load_acceptsCheckpointAtTtlBoundary() {
        // 7d - 1s → still within TTL → must load.
        // Use 1s margin instead of 1ms to avoid clock-jitter flakiness
        // on slow CI runners.
        val sevenDaysMinusOneSecond = nowMs() - (7L * 24 * 60 * 60 * 1000) + 1_000
        val cp = validCheckpoint(updatedAt = sevenDaysMinusOneSecond)
        assertTrue("save must succeed", store().save(path(), cp))
        val loaded = store().load(path(), matchingParams())
        assertNotNull("checkpoint within TTL (with 1s margin) must load", loaded)
    }

    @Test
    fun load_legacyCheckpointWithZeroUpdatedAtIsTreatedAsNeverStale() {
        // Legacy P0-3 checkpoints carry updatedAt=0. Don't mass-purge them.
        // analyses.size must equal completedThrough per isCompatible — pass
        // a matching pair (1 analysis for 1 completed chunk).
        val cp = validCheckpoint(
            updatedAt = 0L,
            completedThrough = 1,
            analyses = listOf("chunk1 analysis"),
        )
        assertTrue("save must succeed", store().save(path(), cp))
        val loaded = store().load(path(), matchingParams())
        assertNotNull("legacy checkpoint with updatedAt=0 must load (skip TTL)", loaded)
        assertEquals(1, loaded!!.completedThrough)
    }

    @Test
    fun load_corruptedJsonStillReturnsNull() {
        val file = path()
        file.parentFile?.mkdirs()
        file.writeText("this is not valid json {{{", Charsets.UTF_8)
        val loaded = store().load(file, matchingParams())
        assertNull("corrupted JSON must return null (existing behavior)", loaded)
    }
}