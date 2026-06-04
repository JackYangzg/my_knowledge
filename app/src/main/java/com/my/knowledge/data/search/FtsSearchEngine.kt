package com.my.knowledge.data.search

import com.my.knowledge.data.db.dao.SearchDao
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import kotlinx.coroutines.flow.Flow
import java.util.Locale

interface SearchEngine {
    fun search(query: String, knowledgeBaseId: String? = null): Flow<List<KnowledgeItemEntity>>
    suspend fun searchResults(query: String, knowledgeBaseId: String? = null, limit: Int = 20): List<KnowledgeSearchResult>
}

class FtsSearchEngine(
    private val searchDao: SearchDao
) : SearchEngine {
    override fun search(query: String, knowledgeBaseId: String?): Flow<List<KnowledgeItemEntity>> {
        val useFts = query.length >= 2 && !query.contains("*")
        return if (useFts) {
            if (knowledgeBaseId == null) searchDao.ftsSearchAll("\"$query\"")
            else searchDao.ftsSearchByKb("\"$query\"", knowledgeBaseId)
        } else {
            if (knowledgeBaseId == null) searchDao.searchAll(query)
            else searchDao.searchByKb(query, knowledgeBaseId)
        }
    }

    // PERF-8: searchResults used to be a `Flow<...>` that every caller
    // consumed with `.firstOrNull()`. Wrapping a one-shot read in Flow
    // forced Room to register an InvalidationTracker observer per
    // table, re-running the query on every unrelated write. Now it's
    // a plain suspend that returns the assembled ranking list — the
    // fragment / item / semantic pieces are joined in-memory because
    // they're three independent one-shots, not a stream of updates.
    override suspend fun searchResults(
        query: String,
        knowledgeBaseId: String?,
        limit: Int
    ): List<KnowledgeSearchResult> {
        val useFts = query.length >= 2 && !query.contains("*")
        val fragments: List<KnowledgeSearchResult> = if (useFts) {
            if (knowledgeBaseId == null) {
                searchDao.ftsSearchFragmentsAll("\"$query\"", limit)
            } else {
                searchDao.ftsSearchFragmentsByKb("\"$query\"", knowledgeBaseId, limit)
            }
        } else {
            if (knowledgeBaseId == null) {
                searchDao.searchFragmentsAll(query, limit)
            } else {
                searchDao.searchFragmentsByKb(query, knowledgeBaseId, limit)
            }
        }
        val items: List<KnowledgeSearchResult> = if (knowledgeBaseId == null) {
            searchDao.searchItemsAsResultsAll(query, limit)
        } else {
            searchDao.searchItemsAsResultsByKb(query, knowledgeBaseId, limit)
        }
        val semanticCandidates = if (knowledgeBaseId == null) {
            searchDao.semanticCandidatesAll(limit * 8)
        } else {
            searchDao.semanticCandidatesByKb(knowledgeBaseId, limit * 8)
        }
        val semantic: List<KnowledgeSearchResult> = run {
            val queryEmbedding = localEmbedding(query)
            semanticCandidates.mapNotNull { candidate ->
                val score = cosine(queryEmbedding, parseEmbedding(candidate.embeddingJson))
                if (score <= 0.05f) null else KnowledgeSearchResult(
                    itemId = candidate.itemId,
                    fragmentId = candidate.fragmentId,
                    knowledgeBaseId = candidate.knowledgeBaseId,
                    title = candidate.title,
                    snippet = candidate.snippet,
                    sourceType = candidate.sourceType,
                    score = 1.2f + score,
                    matchType = "semantic"
                )
            }.sortedByDescending { it.score }.take(limit)
        }
        return (fragments + semantic + items)
            .groupBy { it.itemId to it.fragmentId }
            .map { (_, group) -> group.maxBy { it.score } }
            .sortedWith(compareByDescending<KnowledgeSearchResult> { it.score }.thenBy { it.title })
            .take(limit)
    }

    private fun localEmbedding(content: String): FloatArray {
        val vector = FloatArray(16)
        tokenize(content).forEach { token ->
            val bucket = (token.hashCode() and Int.MAX_VALUE) % vector.size
            vector[bucket] += 1f
        }
        val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
        for (i in vector.indices) vector[i] = vector[i] / norm
        return vector
    }

    private fun parseEmbedding(json: String): FloatArray {
        return json.removeSurrounding("[", "]")
            .split(",")
            .mapNotNull { it.trim().toFloatOrNull() }
            .let { values ->
                FloatArray(16) { index -> values.getOrNull(index) ?: 0f }
            }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b.getOrElse(i) { 0f }
        return dot.coerceIn(0f, 1f)
    }

    private fun tokenize(text: String): List<String> =
        text.replace(Regex("[\\[\\]{}\"#*`~!?.:;，。！？、（）()<>/\\\\|]+"), " ")
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
}
