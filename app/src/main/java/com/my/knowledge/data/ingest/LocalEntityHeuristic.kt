package com.my.knowledge.data.ingest

import org.json.JSONObject

/**
 * Local-heuristic entity / concept extractor.
 *
 * Originally lived as a `private` method on `IngestOrchestrator`; promoted
 * to a top-level function so the unit test in `LocalHeuristicExtractionTest`
 * can drive it with crafted input without standing up a full DB / LLM
 * stack. The orchestrator's `analysisTask` is the only production caller.
 *
 * Strategy: find high-frequency CJK / English phrases in the parsed text,
 * drop a small stopword set, and emit the top N as `entities` and the
 * next N as `concepts`. Quality is obviously lower than a real LLM pass —
 * we just want the "中间处理数据" screen and the wiki page list to stay
 * non-empty when the LLM either is unconfigured or returned empty arrays.
 */
internal fun extractLocalHeuristic(
    text: String,
    maxEntities: Int = 4,
    maxConcepts: Int = 4,
): Pair<String, String> {
    val phraseCounts = HashMap<String, Int>()
    // 中文: 连续 2-5 个汉字作为一个候选短语
    val cjk = Regex("[\u4e00-\u9fa5]{2,6}")
    cjk.findAll(text).forEach { match ->
        val phrase = match.value
        if (phrase.length in 2..5 && phrase !in LOCAL_HEURISTIC_STOPWORDS) {
            phraseCounts[phrase] = (phraseCounts[phrase] ?: 0) + 1
        }
    }
    // 英文: 先把每个独立的英文/字母数字 token 计数,再叠一对相邻 2-gram
    // 短语。独立的 token 让"Raft / etcd / Paxos"这种反复出现的专有名词能
    // 被选上(否则只匹配 2-gram 会让"是 Raft"、"用 etcd"这种混入中文的
    // 串被算进 phrases,反而把真正有意义的单词挤掉)。
    val enWord = Regex("[A-Za-z][A-Za-z0-9]{1,}")
    val enWords = enWord.findAll(text).map { it.value }.toList()
    for (word in enWords) {
        if (word.length in 3..20 && word !in LOCAL_HEURISTIC_STOPWORDS) {
            phraseCounts[word] = (phraseCounts[word] ?: 0) + 1
        }
    }
    for (i in 0..enWords.size - 2) {
        val phrase = (enWords.subList(i, i + 2.coerceAtMost(enWords.size - i)).joinToString(" "))
        if (phrase.length in 4..32 && phrase !in LOCAL_HEURISTIC_STOPWORDS) {
            phraseCounts[phrase] = (phraseCounts[phrase] ?: 0) + 1
        }
    }
    val top = phraseCounts.entries
        .asSequence()
        .filter { it.value >= 2 }
        .sortedByDescending { it.value }
        .map { it.key }
        .toList()
    if (top.isEmpty()) return "[]" to "[]"
    // 平衡分配: 当源材料没有那么多高频短语时,只让 entities 全占
    // (maxEntities),concepts 拿到 0 条——这恰好是用户最痛的那个症状
    // (没有 entities/concepts,只剩 source)。所以这里把 cap 的"硬上限"
    // 改成"按比例软切"——如果有 ≥2 条短语,确保两边至少各 1 条。
    val entityCount = minOf(maxEntities, (top.size + 1) / 2.coerceAtLeast(1))
    val conceptCount = minOf(maxConcepts, top.size - entityCount)
    val entityNames = top.take(entityCount)
    val conceptNames = top.drop(entityCount).take(conceptCount)
    val entitiesJson = entityNames.joinToString(",", "[", "]") { name ->
        JSONObject()
            .put("name", name)
            .put("entityType", "Topic") // P1: 自由类型,UI 按此字段分组 / 上色
            .put("type", "entity")        // 兼容老 schema(enum fallbackType)
            .put("description", "本地启发式抽取：原文多次出现的高频短语")
            .put("role_in_source", "supporting")
            .put("confidence", 0.4f)
            .toString()
    }
    val conceptsJson = conceptNames.joinToString(",", "[", "]") { name ->
        JSONObject()
            .put("name", name)
            .put("conceptCategory", "Topic") // P1: 自由分类
            .put("category", "concept")        // 兼容老 schema
            .put("definition", "本地启发式抽取：原文多次出现的高频短语")
            .put("why_it_matters", "在源材料中反复出现，可能是该知识库的核心议题。")
            .put("confidence", 0.4f)
            .toString()
    }
    return entitiesJson to conceptsJson
}

/**
 * A small, hand-curated set of Chinese function words. Anything in
 * this set is never promoted to a "named entity" no matter how many
 * times it appears — these are particles / pronouns / connectives,
 * not topical phrases. The set is intentionally short and
 * conservative; we'd rather under-extract than to produce a wiki full
 * of "我们 / 这个 / 然后" pages.
 */
internal val LOCAL_HEURISTIC_STOPWORDS = setOf(
    // 中文常见停用词
    "我们", "你们", "他们", "这个", "那个", "什么", "怎么", "为什么", "因为",
    "所以", "但是", "如果", "然后", "现在", "已经", "可以", "应该", "需要",
    "进行", "通过", "以及", "其中", "或者", "其他", "一些", "这种", "那样",
    "这里", "那里", "这些", "那些", "这样", "那样", "本", "此", "该", "上",
    "下", "不", "也", "都", "又", "再", "才", "只", "很", "非常", "比较"
)
