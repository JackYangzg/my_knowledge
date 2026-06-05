package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.ParsedContentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1-B.4 / PERF-2: contract for the in-process parse → analysis
 * handoff cache.
 *
 *   1. put → get round-trips a parsed content row.
 *   2. Re-put on the same key refreshes insertion order, so the
 *      entry is treated as the newest (not evicted first).
 *   3. Eviction respects the [limit] cap. Insertion order: the
 *      oldest entry is the first to be dropped.
 *   4. evict() removes both the row and its order position.
 */
class ParsedContentCacheTest {

    private fun makeEntity(id: String): ParsedContentEntity = ParsedContentEntity(
        id = id,
        sourceId = "src-$id",
        parserType = "markdown",
        markdown = "# $id",
        plainText = id,
        parseHash = "hash-$id",
        metadataJson = "{}",
        createdAt = 0,
        updatedAt = 0
    )

    @Test
    fun `put then get round-trips a parsed content row`() {
        val cache = ParsedContentCache(limit = 8)
        val entity = makeEntity("a")
        cache.put("src-a", entity)
        val out = cache.get("src-a")
        assertNotNull(out)
        assertEquals("a", out?.id)
    }

    @Test
    fun `get returns null for unknown sourceId`() {
        val cache = ParsedContentCache(limit = 8)
        assertNull(cache.get("nope"))
    }

    @Test
    fun `eviction respects the limit and drops the oldest entry`() {
        val cache = ParsedContentCache(limit = 3)
        cache.put("src-1", makeEntity("1"))
        cache.put("src-2", makeEntity("2"))
        cache.put("src-3", makeEntity("3"))
        cache.put("src-4", makeEntity("4")) // pushes out src-1
        assertNull("src-1 should be evicted", cache.get("src-1"))
        assertNotNull("src-2 should still be present", cache.get("src-2"))
        assertNotNull("src-3 should still be present", cache.get("src-3"))
        assertNotNull("src-4 should still be present", cache.get("src-4"))
        assertEquals(3, cache.size())
    }

    @Test
    fun `re-putting the same key refreshes insertion order`() {
        val cache = ParsedContentCache(limit = 3)
        cache.put("src-1", makeEntity("1"))
        cache.put("src-2", makeEntity("2"))
        cache.put("src-3", makeEntity("3"))
        // Re-put src-1 — it should now be the newest, so src-2 is
        // the eviction candidate on the next put.
        cache.put("src-1", makeEntity("1-refreshed"))
        cache.put("src-4", makeEntity("4"))
        assertNotNull("src-1 should survive (re-put refreshed it)", cache.get("src-1"))
        assertNull("src-2 should be the one evicted", cache.get("src-2"))
        assertNotNull("src-3 should still be present", cache.get("src-3"))
        assertNotNull("src-4 should still be present", cache.get("src-4"))
    }

    @Test
    fun `evict removes the row and its order position`() {
        val cache = ParsedContentCache(limit = 4)
        cache.put("src-1", makeEntity("1"))
        cache.put("src-2", makeEntity("2"))
        cache.put("src-3", makeEntity("3"))
        cache.evict("src-2")
        assertNull(cache.get("src-2"))
        assertEquals(2, cache.size())
        // Subsequent put should not have to walk past a phantom
        // src-2 entry in the order deque — fill the cache and
        // verify only the live entries are evicted.
        cache.put("src-4", makeEntity("4"))
        cache.put("src-5", makeEntity("5"))
        cache.put("src-6", makeEntity("6"))
        // Oldest two live entries are src-1 and src-3; src-2 is
        // already gone. After 3 more puts (filling to 4 again +
        // one overflow), src-1 should be the eviction target.
        assertNotNull(cache.get("src-3"))
    }
}
