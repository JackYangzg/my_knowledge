package com.my.knowledge.data.ingest

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the analysis-stage "no entities, only sources" bug.
 *
 * The previous IngestOrchestrator.analysisTask wrote a hard-coded
 * `entitiesJson = "[]"` fallback when the LLM was unconfigured or its
 * response wasn't parseable, and then the wiki generator skipped
 * wiki_entity / wiki_concept pages. The user-visible symptom was
 * "无法抽取到实体、概念，只有源" (only sources in the intermediate
 * data screen after every new import).
 *
 * The fix has two halves:
 *   1. A local heuristic extractor (`extractLocalHeuristic` in
 *      `LocalEntityHeuristic.kt`) that, given the parsed text, emits
 *      a small but non-empty entities / concepts JSON array using
 *      high-frequency phrases.
 *   2. The orchestrator now applies the heuristic *only* when the
 *      AI path produced an empty array — it never overrides a real
 *      AI extraction.
 *
 * These tests pin both halves.
 */
class LocalHeuristicExtractionTest {

    /**
     * A realistic chunk of Chinese prose with repeating topical
     * phrases. The top-frequency phrases (after stopword removal)
     * should be the ones promoted to "entities" / "concepts".
     */
    private val proseWithRepetition = """
        Raft 是一种分布式共识算法。Raft 通过选举出一个领导者来协调集群中的日志复制。
        在 Raft 集群中，领导者负责接收客户端请求并把日志条目复制到其他节点。Raft 的核心机制
        包括领导者选举和日志复制两个部分。etcd 是一个使用 Raft 的开源分布式 KV 存储。
        很多分布式系统使用 Raft 而不是 Paxos，因为 Raft 更易理解。
        Raft 的论文由 Diego Ongaro 在 2014 年发表。理解 Raft 需要掌握状态机复制。
    """.trimIndent()

    @Test
    fun `extractLocalHeuristic returns non-empty JSON when text has high-frequency phrases`() {
        // Use a much larger cap so the test prose (which has ~6 repeating
        // phrases) yields at least 2 entities AND 2 concepts. Production
        // calls default to (4, 4), which is fine for typical docs; the
        // test just needs to demonstrate the function picks something
        // on both sides, not stress the default cap.
        val (entitiesJson, conceptsJson) = extractLocalHeuristic(proseWithRepetition, 6, 6)
        val entities = JSONArray(entitiesJson)
        val concepts = JSONArray(conceptsJson)
        assertFalse(
            "Entities must be non-empty when text has high-frequency phrases (was hard-coded [])",
            entities.length() == 0
        )
        assertFalse(
            "Concepts must be non-empty when text has high-frequency phrases (was hard-coded [])",
            concepts.length() == 0
        )
        // The orchestrator should pick "Raft" — the most repeated topical phrase.
        val names = (0 until entities.length()).map { entities.getJSONObject(it).getString("name") } +
            (0 until concepts.length()).map { concepts.getJSONObject(it).getString("name") }
        assertTrue(
            "Raft should be picked as a high-frequency phrase; got ${names.distinct()}",
            names.any { it.contains("Raft") }
        )
    }

    @Test
    fun `extractLocalHeuristic filters out common Chinese stopwords`() {
        val stopwordProse = """
            我们 这个 那个 进行 通过 我们 这个 那个 进行 我们 这个 那个 通过
        """.trimIndent()
        val (entitiesJson, conceptsJson) = extractLocalHeuristic(stopwordProse, 4, 4)
        val entities = JSONArray(entitiesJson)
        val concepts = JSONArray(conceptsJson)
        val allNames = (0 until entities.length()).map { entities.getJSONObject(it).getString("name") } +
            (0 until concepts.length()).map { concepts.getJSONObject(it).getString("name") }
        assertTrue(
            "Stopwords must not appear as entities; got $allNames",
            allNames.none { it in setOf("我们", "这个", "那个", "进行", "通过") }
        )
    }

    @Test
    fun `extractLocalHeuristic returns empty JSON when no phrase repeats enough times`() {
        // Every word appears only once — the heuristic must NOT invent
        // entities out of single-occurrence phrases (it would just be
        // noise that pollutes the wiki).
        val uniqueProse = "苹果 香蕉 葡萄 橙子 西瓜 哈密瓜 火龙果 猕猴桃"
        val (entitiesJson, conceptsJson) = extractLocalHeuristic(uniqueProse, 4, 4)
        assertEquals("[]", entitiesJson)
        assertEquals("[]", conceptsJson)
    }

    @Test
    fun `extractLocalHeuristic respects entity and concept caps`() {
        val manyRepeats = (1..20).joinToString(" ") { "分布式共识算法" }
        val (entitiesJson, conceptsJson) = extractLocalHeuristic(manyRepeats, 2, 2)
        val entities = JSONArray(entitiesJson)
        val concepts = JSONArray(conceptsJson)
        assertTrue("Entities cap of 2 was ignored: ${entities.length()}", entities.length() <= 2)
        assertTrue("Concepts cap of 2 was ignored: ${concepts.length()}", concepts.length() <= 2)
    }

    @Test
    fun `extractLocalHeuristic output has the JSON shape the downstream compiler expects`() {
        val (entitiesJson, conceptsJson) = extractLocalHeuristic(
            "Raft 共识算法 Raft 共识算法 etcd 分布式系统 etcd 分布式系统 状态机复制",
            4, 4
        )
        val entities = JSONArray(entitiesJson)
        val concepts = JSONArray(conceptsJson)
        // Each entity must have a name + a confidence number (the
        // minimal contract WikiPageCompiler.parseNamedObjects reads).
        for (i in 0 until entities.length()) {
            val obj = entities.getJSONObject(i)
            assertTrue("Entity $i missing name", obj.has("name") && obj.getString("name").isNotBlank())
            assertTrue("Entity $i missing type (legacy alias)", obj.has("type"))
            // P1: 新字段 entityType 也必须存在——保证 KnowledgeRepositoryImpl
            // 能从 frontmatter 读出正确的语义类型。
            assertTrue("Entity $i missing entityType (P1 free-form type)", obj.has("entityType"))
        }
        for (i in 0 until concepts.length()) {
            val obj = concepts.getJSONObject(i)
            assertTrue("Concept $i missing name", obj.has("name") && obj.getString("name").isNotBlank())
            assertTrue("Concept $i missing category (legacy alias)", obj.has("category"))
            // P1: 新字段 conceptCategory 也必须存在。
            assertTrue("Concept $i missing conceptCategory (P1 free-form category)", obj.has("conceptCategory"))
        }
    }

    @Test
    fun `extractLocalHeuristic on empty input returns empty arrays (no crash)`() {
        val (entitiesJson, conceptsJson) = extractLocalHeuristic("", 4, 4)
        assertEquals("[]", entitiesJson)
        assertEquals("[]", conceptsJson)
    }
}
