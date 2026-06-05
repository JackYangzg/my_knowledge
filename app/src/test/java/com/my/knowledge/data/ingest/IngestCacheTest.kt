package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.AnalysisResultEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CQ-12 / ARCH-6: contract for the ingest cache fast-path.
 *
 *   1. Cache hit requires a *different* `source_document.id` with
 *      the same `sha256` whose status reached `STATUS_GENERATED`.
 *   2. The previous source's `analysis_result.promptVersion` must
 *      match [PromptVersions.INGEST_ANALYSIS_V1] — a previous
 *      analysis produced under an older prompt invalidates the hit
 *      so the pipeline reruns against the new prompt.
 *   3. `"reprocess": true` in the task inputJson is an explicit
 *      user override that always returns a miss.
 *   4. A blank sha256, a self-match, a missing analysis row, or a
 *      previous source still in flight all return a miss.
 */
class IngestCacheTest {

    private fun makeSource(
        id: String,
        sha256: String,
        status: String = SourceDocumentEntity.STATUS_GENERATED,
    ) = SourceDocumentEntity(
        id = id,
        sourceType = "markdown",
        title = id,
        originalUri = null,
        localPath = null,
        mimeType = "text/markdown",
        sizeBytes = 1L,
        sha256 = sha256,
        importFrom = "test",
        folderHint = null,
        status = status,
        errorMessage = null,
        targetKnowledgeBaseId = "kb-1",
        createdAt = 0,
        updatedAt = 0,
    )

    private fun makeAnalysis(
        sourceId: String,
        promptVersion: String = PromptVersions.INGEST_ANALYSIS_V1,
    ) = AnalysisResultEntity(
        id = "analysis-$sourceId",
        sourceId = sourceId,
        parsedContentId = "parsed-$sourceId",
        summary = "s",
        tagsJson = "[]",
        entitiesJson = "[]",
        conceptsJson = "[]",
        relationsJson = "[]",
        claimsJson = "[]",
        gapsJson = "[]",
        archiveRecommendationJson = "[]",
        confidence = 1f,
        modelName = null,
        promptVersion = promptVersion,
        analysisHash = "h",
        createdAt = 0,
    )

    private fun makeTask(sourceId: String, inputJson: String = "{}") = ProcessingTaskEntity(
        id = "task-$sourceId",
        targetType = "source_document",
        targetId = sourceId,
        taskType = "parse",
        status = "running",
        priority = 0,
        dependsOnTaskIdsJson = null,
        retryCount = 0,
        maxRetry = 0,
        errorMessage = null,
        createdAt = 0,
        updatedAt = 0,
        finishedAt = null,
        sourceId = sourceId,
        itemId = null,
        progress = 0,
        currentStep = null,
        inputJson = inputJson,
        outputJson = null,
        startedAt = null,
    )

    @Test
    fun `cache hit when sibling source is generated under current prompt`() = runBlocking {
        val sourceDao = IngestCacheFakeSourceDao(
            byId = mapOf("src-new" to makeSource("src-new", "abc")),
            bySha = mapOf("abc" to makeSource("src-prev", "abc")),
        )
        val analysisDao = IngestCacheFakeAnalysisDao(
            bySource = mapOf("src-prev" to makeAnalysis("src-prev")),
        )
        val cache = IngestCache(sourceDao, analysisDao)
        assertTrue(cache.isHit(makeTask("src-new")))
    }

    @Test
    fun `cache miss when previous analysis is under an older prompt`() = runBlocking {
        val sourceDao = IngestCacheFakeSourceDao(
            byId = mapOf("src-new" to makeSource("src-new", "abc")),
            bySha = mapOf("abc" to makeSource("src-prev", "abc")),
        )
        val analysisDao = IngestCacheFakeAnalysisDao(
            bySource = mapOf("src-prev" to makeAnalysis("src-prev", promptVersion = "ingest_analysis_v0")),
        )
        val cache = IngestCache(sourceDao, analysisDao)
        assertFalse("stale prompt must invalidate the cache", cache.isHit(makeTask("src-new")))
    }

    @Test
    fun `cache miss when previous source is still in flight`() = runBlocking {
        val sourceDao = IngestCacheFakeSourceDao(
            byId = mapOf("src-new" to makeSource("src-new", "abc")),
            bySha = mapOf("abc" to makeSource("src-prev", "abc", status = SourceDocumentEntity.STATUS_PARSED)),
        )
        val analysisDao = IngestCacheFakeAnalysisDao(
            bySource = mapOf("src-prev" to makeAnalysis("src-prev")),
        )
        val cache = IngestCache(sourceDao, analysisDao)
        assertFalse(cache.isHit(makeTask("src-new")))
    }

    @Test
    fun `cache miss when previous analysis row is missing`() = runBlocking {
        val sourceDao = IngestCacheFakeSourceDao(
            byId = mapOf("src-new" to makeSource("src-new", "abc")),
            bySha = mapOf("abc" to makeSource("src-prev", "abc")),
        )
        val analysisDao = IngestCacheFakeAnalysisDao(bySource = emptyMap())
        val cache = IngestCache(sourceDao, analysisDao)
        assertFalse(cache.isHit(makeTask("src-new")))
    }

    @Test
    fun `cache miss when sha256 self-matches`() = runBlocking {
        val sourceDao = IngestCacheFakeSourceDao(
            byId = mapOf("src-1" to makeSource("src-1", "abc")),
            bySha = mapOf("abc" to makeSource("src-1", "abc")),
        )
        val analysisDao = IngestCacheFakeAnalysisDao(
            bySource = mapOf("src-1" to makeAnalysis("src-1")),
        )
        val cache = IngestCache(sourceDao, analysisDao)
        assertFalse(cache.isHit(makeTask("src-1")))
    }

    @Test
    fun `reprocess true always forces a miss`() = runBlocking {
        val sourceDao = IngestCacheFakeSourceDao(
            byId = mapOf("src-new" to makeSource("src-new", "abc")),
            bySha = mapOf("abc" to makeSource("src-prev", "abc")),
        )
        val analysisDao = IngestCacheFakeAnalysisDao(
            bySource = mapOf("src-prev" to makeAnalysis("src-prev")),
        )
        val cache = IngestCache(sourceDao, analysisDao)
        assertFalse(cache.isHit(makeTask("src-new", inputJson = """{"reprocess":true}""")))
    }

    @Test
    fun `cache miss when source sha256 is blank`() = runBlocking {
        val sourceDao = IngestCacheFakeSourceDao(
            byId = mapOf("src-new" to makeSource("src-new", "")),
            bySha = emptyMap(),
        )
        val analysisDao = IngestCacheFakeAnalysisDao(bySource = emptyMap())
        val cache = IngestCache(sourceDao, analysisDao)
        assertFalse(cache.isHit(makeTask("src-new")))
    }
}

private class IngestCacheFakeSourceDao(
    private val byId: Map<String, SourceDocumentEntity>,
    private val bySha: Map<String, SourceDocumentEntity>,
) : com.my.knowledge.data.db.dao.SourceDocumentDao {
    override suspend fun getById(id: String): SourceDocumentEntity? = byId[id]
    override suspend fun findBySha256(sha256: String): SourceDocumentEntity? = bySha[sha256]
    override fun observeAll() = throw NotImplementedError("unused in this test")
    override fun observeByKnowledgeBase(kbId: String) = throw NotImplementedError("unused in this test")
    override suspend fun getRunnableSourcesWithoutActiveTask(
        statuses: List<String>,
        limit: Int,
    ) = throw NotImplementedError("unused in this test")
    override suspend fun insert(source: SourceDocumentEntity) = throw NotImplementedError("unused in this test")
    override suspend fun update(source: SourceDocumentEntity) = throw NotImplementedError("unused in this test")
    override suspend fun updateStatus(id: String, status: String, errorMessage: String?, updatedAt: Long) =
        throw NotImplementedError("unused in this test")
    override suspend fun markDeleted(id: String, updatedAt: Long) =
        throw NotImplementedError("unused in this test")
}

private class IngestCacheFakeAnalysisDao(
    private val bySource: Map<String, AnalysisResultEntity>,
) : com.my.knowledge.data.db.dao.AnalysisResultDao {
    override suspend fun getLatestBySource(sourceId: String): AnalysisResultEntity? = bySource[sourceId]
    override suspend fun insert(result: AnalysisResultEntity) = throw NotImplementedError("unused in this test")
    override suspend fun deleteBySource(sourceId: String) = throw NotImplementedError("unused in this test")
}
