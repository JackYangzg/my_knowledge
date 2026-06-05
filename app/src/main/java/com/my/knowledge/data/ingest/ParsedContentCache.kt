package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.ParsedContentEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * P1-B.4 / PERF-2: in-process handoff for the parse → analysis relay.
 *
 * Parse still writes the row to `parsed_content` (so a process
 * restart or a Worker-driven scheduling path can pick up from the
 * same source), but the analysis stage reads from this cache first.
 * The DB read stays as a fallback for cold starts and cross-process
 * handoffs.
 *
 * Bounded LRU — capped to [limit] entries (default 64, mirroring
 * the old `parsedContentCacheLimit` in `IngestOrchestrator`) so a
 * runaway 1K-file burst doesn't OOM the process. Eviction is
 * insertion-order: the oldest entry is dropped when the limit is
 * exceeded.
 */
class ParsedContentCache(private val limit: Int = 64) {
    private val entries: ConcurrentHashMap<String, ParsedContentEntity> = ConcurrentHashMap()
    private val order: ConcurrentLinkedDeque<String> = ConcurrentLinkedDeque()
    private val lock = Any()

    fun put(sourceId: String, entity: ParsedContentEntity) {
        synchronized(lock) {
            // Refresh on re-put: remove the existing entry from
            // the order deque so the new put counts as a fresh
            // insertion at the tail.
            if (entries.containsKey(sourceId)) {
                order.remove(sourceId)
            }
            entries[sourceId] = entity
            order.addLast(sourceId)
            while (order.size > limit) {
                val evicted = order.pollFirst() ?: break
                entries.remove(evicted)
            }
        }
    }

    fun get(sourceId: String): ParsedContentEntity? = entries[sourceId]

    fun evict(sourceId: String) {
        synchronized(lock) {
            entries.remove(sourceId)
            order.remove(sourceId)
        }
    }

    fun size(): Int = entries.size
}
