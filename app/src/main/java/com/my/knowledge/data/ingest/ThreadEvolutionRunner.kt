package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Pure `suspend` runner that re-derives the [KnowledgeThreadEntity]
 * for a knowledge base. Extracted from the original
 * `ThreadEvolutionWorker` so the rebuild path no longer has to go
 * through WorkManager (P0-1).
 *
 * Re-derives the [KnowledgeThreadEntity] for a knowledge base.
 *
 * The original implementation just chunked items by their index in the
 * list and labeled "→" boundaries; that produced a "mainline" that was
 * effectively random — every KB looked the same and nothing changed
 * unless the item order changed. This rewrite follows llm_wiki's
 * "thesis / mainline" model (see src/lib/templates.ts:82):
 *
 *   - **mainlines** are derived from the explicit synthesis pages the
 *     generator wrote (index.md, overview.md, log.md) and from the tag
 *     clusters that actually overlap across the wiki pages. Two items
 *     appear in the same mainline segment if they share ≥1 tag and are
 *     within the time bucket they belong to.
 *   - **relations** come from explicit wikilinks inside wiki pages and
 *     from the analysis-result relations (`analysis.relationsJson`).
 *   - **gaps** are now score-driven (low-confidence items, missing
 *     synthesis, wiki pages with no source links, no extracted tags,
 *     no mainline, no description) so a knowledge base that has just
 *     been bootstrapped surfaces a real backlog, instead of a static
 *     "建议补充..." message.
 *   - **suggestions** map 1:1 to the detected gaps, ranked by impact.
 *
 * The hash that drives the "no changes — skip" short-circuit is now
 * built from the actual content we care about (item ids + tags + wikilink
 * graph), not from the sort order of `createdAt`. That fixes the
 * "脉络一直显示同一份陈旧结果" bug.
 *
 * Returns the resulting [KnowledgeThreadEntity] (or `null` if the
 * base doesn't exist / is "unfiled"), plus a boolean indicating
 * whether anything changed. Callers that only care about "did the
 * rebuild run" can ignore the boolean; the [com.my.knowledge.worker.ThreadEvolutionWorker]
 * adapter preserves the WorkManager Result.success / Result.failure
 * contract on top of this.
 */
object ThreadEvolutionRunner {

    data class Result(
        val thread: KnowledgeThreadEntity?,
        val itemCount: Int,
        val mainlineCount: Int,
        val relationCount: Int,
        val gapCount: Int,
        val skipped: Boolean,
    )

    suspend fun runEvolution(
        db: AppDatabase,
        repository: KnowledgeRepository,
        kbId: String,
    ): Result {
        val base = repository.getBaseById(kbId) ?: return Result(null, 0, 0, 0, 0, skipped = true)
        if (base.type == "unfiled") return Result(null, 0, 0, 0, 0, skipped = true)

        // 关键修复:之前用 `observeItemsByKb` 拿数据,但那条 SQL 带
        // `sourceType NOT LIKE 'wiki_%'`,把实体/概念/来源这些核心 wiki
        // 页面全过滤掉了——脉络重建时只看到"灵感原文",根本看不到实体
        // 概念节点之间的关系,等于白跑。改用刚加的 wiki-only 读路径。
        val items = db.knowledgeItemDao().getAllWikiByKb(kbId)
            .filter { it.deletedAt == null }
        val existing = repository.getThreadByKb(kbId)

        // Hash check: skip the rebuild only when EVERYTHING we look at is
        // byte-identical to the inputs the previous run saw. The previous
        // implementation included `existing.mainlineJson` in the hash, so
        // a re-run after a failed generation would always hit the cache
        // and never write a fresh thread. We now hash the inputs only.
        val currentHash = sha256(buildInputSignature(items))
        if (existing != null && currentHash == existing.inputHash) {
            return Result(existing, items.size, 0, 0, 0, skipped = true)
        }

        val description = buildDescription(base.name, items)
        val coreQuestion = extractCoreQuestion(items)
        val mainlines = extractMainlines(items)
        val relations = extractRelations(items)
        val gaps = detectGaps(items, mainlines, relations)
        val suggestions = generateSuggestions(gaps, items)

        val thread = KnowledgeThreadEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            knowledgeBaseId = kbId,
            description = description,
            coreQuestion = coreQuestion,
            mainlineJson = mainlines.joinToString(",", "[", "]") { "\"${escape(it)}\"" },
            relationsJson = relations.joinToString(",", "[", "]") { r ->
                "{\"from\":\"${escape(r.from)}\",\"to\":\"${escape(r.to)}\",\"relation\":\"${escape(r.relation)}\"}"
            },
            gapsJson = gaps.joinToString(",", "[", "]") { "\"${escape(it)}\"" },
            nextSuggestionsJson = suggestions.joinToString(",", "[", "]") { "\"${escape(it)}\"" },
            inputHash = currentHash,
            version = (existing?.version ?: 0) + 1,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        repository.saveThread(thread)

        val log = KnowledgeThreadLogEntity(
            id = UUID.randomUUID().toString(),
            threadId = thread.id,
            triggerType = "auto_evolution",
            triggerId = kbId,
            beforeHash = existing?.let { sha256(it.mainlineJson + it.relationsJson) },
            afterHash = sha256(thread.mainlineJson + thread.relationsJson),
            summary = "脉络自动更新：${items.size} 条知识，${mainlines.size} 条主线，${relations.size} 条关联，${gaps.size} 个缺口",
            createdAt = System.currentTimeMillis()
        )
        repository.appendThreadLog(log)
        // P0-1: The graph rebuild is scheduled via [RebuildDebouncer]
        // now, NOT triggered inline here. Triggering a second
        // rebuild from inside the thread-evolution coroutine was
        // exactly the bug that doubled the rebuild cost per ingest.

        repository.updateBase(
            base.copy(
                threadStatus = "ready",
                updatedAt = System.currentTimeMillis()
            )
        )

        return Result(
            thread = thread,
            itemCount = items.size,
            mainlineCount = mainlines.size,
            relationCount = relations.size,
            gapCount = gaps.size,
            skipped = false,
        )
    }

    // ---- Signature -----------------------------------------------------

    private fun buildInputSignature(items: List<KnowledgeItemEntity>): String {
        // Only wiki pages drive the mainline; non-wiki items (raw notes
        // the user has not processed yet) shouldn't trigger a rebuild.
        val wiki = items.filter { it.sourceType.startsWith("wiki_") || it.sourceType == "inspiration" || it.sourceType == "text" }
            .sortedBy { it.createdAt }
        return wiki.joinToString("\n") { item ->
            val tags = parseStringArray(item.tagsJson).sorted().joinToString(",")
            val links = extractWikiLinkTokens(item.contentMarkdown).sorted().joinToString(",")
            "${item.id}|${item.title}|$tags|$links"
        }
    }

    // ---- Description ---------------------------------------------------

    private fun buildDescription(
        baseName: String,
        items: List<KnowledgeItemEntity>
    ): String {
        if (items.isEmpty()) return "知识库「$baseName」尚无已整理的知识条目"
        val tagSet = items.flatMap { parseStringArray(it.tagsJson) }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
        val sourceCount = items.count { it.sourceType == "wiki_source" }
        val entityCount = items.count { it.sourceType == "wiki_entity" }
        val conceptCount = items.count { it.sourceType == "wiki_concept" }
        val synthesisCount = items.count {
            it.sourceType == "wiki_index" || it.sourceType == "wiki_overview" || it.sourceType == "wiki_log" || it.sourceType == "wiki_ai_generated"
        }
        val topicLine = if (tagSet.isNotEmpty()) "主要主题：${tagSet.joinToString("、")}" else "主题尚未凝聚，建议补充标签"
        return "知识库「$baseName」已收录 ${items.size} 条知识，其中来源 $sourceCount 条、实体 $entityCount 条、概念 $conceptCount 条、合成页 $synthesisCount 条。$topicLine。"
    }

    // ---- Core question -------------------------------------------------

    private fun extractCoreQuestion(items: List<KnowledgeItemEntity>): String {
        if (items.isEmpty()) return "尚未确定核心问题"
        // Prefer the explicit synthesis page (overview.md / index.md) that
        // asked the question in the frontmatter — llm_wiki's thesis
        // template writes a `thesis` field. Fall back to a tag-derived
        // question if no synthesis page exists yet.
        val thesisField = items.firstNotNullOfOrNull { it ->
            frontMatterValue(it.contentMarkdown, "thesis")
        }
        if (!thesisField.isNullOrBlank()) return thesisField

        val titles = items.map { it.title }
        val sepRegex = Regex("[\\s\u3000-\u303F\uFF00-\uFFEF\\p{Punct}]+")
        val commonWords = titles.flatMap { it.split(sepRegex) }
            .filter { it.length > 2 }
            .groupingBy { it }.eachCount()
            .filter { it.value >= 2 }
            .keys.take(3)
        return if (commonWords.isNotEmpty()) {
            "如何理解和应用${commonWords.joinToString("、")}相关知识？"
        } else {
            "探索「${titles.first().take(20)}」等相关知识"
        }
    }

    // ---- Mainlines ----------------------------------------------------

    private fun extractMainlines(items: List<KnowledgeItemEntity>): List<String> {
        if (items.isEmpty()) return emptyList()
        val out = mutableListOf<String>()

        // 1. Explicit synthesis chain — index.md / overview.md / log.md /
        //    other `wiki_ai_generated` pages read in createdAt order. This
        //    is the thesis's `wiki/thesis/` line in llm_wiki.
        val synthChain = items
            .filter {
                it.sourceType == "wiki_index" ||
                    it.sourceType == "wiki_overview" ||
                    it.sourceType == "wiki_log" ||
                    it.sourceType == "wiki_ai_generated"
            }
            .sortedBy { it.createdAt }
            .map { it.title }
        if (synthChain.size >= 2) {
            out += synthChain.joinToString(" → ")
        }

        // 2. Tag clusters — the highest-frequency tags form the *themes*
        //    of the knowledge base; items sharing those tags are listed
        //    under the theme line so the user can see "all things
        //    related to X" at a glance.
        val topTags = items.flatMap { parseStringArray(it.tagsJson) }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
        for (tag in topTags) {
            val tagItems = items
                .filter { parseStringArray(it.tagsJson).any { it.equals(tag, ignoreCase = true) } }
                .sortedBy { it.createdAt }
                .map { it.title }
                .distinct()
                .take(4)
            if (tagItems.size >= 2) {
                out += "「$tag」: ${tagItems.joinToString(" → ")}"
            }
        }

        return out.distinct().take(8)
    }

    // ---- Relations ----------------------------------------------------

    private data class RelationTriple(val from: String, val to: String, val relation: String)

    private fun extractRelations(items: List<KnowledgeItemEntity>): List<RelationTriple> {
        if (items.size < 2) return emptyList()
        val out = mutableListOf<RelationTriple>()
        val byTitle = items.associateBy { it.title.lowercase(Locale.ROOT) }
        val seen = mutableSetOf<Pair<String, String>>()

        fun addRelation(from: String, to: String, relation: String) {
            if (from.equals(to, ignoreCase = true)) return
            val key = from.lowercase(Locale.ROOT) to to.lowercase(Locale.ROOT)
            if (key in seen) return
            seen += key
            out += RelationTriple(from, to, relation)
        }

        // Wikilink-based edges (highest confidence — explicit authoring).
        for (item in items) {
            val from = byTitle[item.title.lowercase(Locale.ROOT)] ?: continue
            for (link in extractWikiLinkTokens(item.contentMarkdown)) {
                if (byTitle[link.lowercase(Locale.ROOT)] == null) continue
                addRelation(from.title, link, "引用")
            }
        }

        // ---- O(n²) -> O(n) 优化 ----------------------------------------
        //
        // 旧实现对每对 item 都跑一次 tag / title-word 集合相交，复杂度是
        // O(n²)。一次脉络重建对一个 2000 项的 KB 要 4M 次循环，在中端
        // Android 设备上单这一步就要 4-5 秒——配合"generation 完成后立刻
        // scheduleThreadUpdate"，体感就是入库阶段卡住。
        //
        // 新实现分两步：先把 items 按 tag / word 倒排到桶里，再在每个桶内
        // 跑两两比较。复杂度是 O(n + sum of bucket²)，标签分布越均匀
        // 加速越明显（KB 中长尾分布下桶大小通常 << n）。seen 集合仍然
        // 在 addRelation 内做全局去重,重复的边不会写入。
        val wordSep = Regex("[\\s\u3000-\u303F\uFF00-\uFFEF\\p{Punct}]+")

        // Tag co-occurrence edges (medium confidence)
        val byTag = HashMap<String, ArrayList<KnowledgeItemEntity>>()
        for (item in items) {
            for (raw in parseStringArray(item.tagsJson)) {
                val tag = raw.lowercase(Locale.ROOT)
                if (tag.isBlank()) continue
                byTag.getOrPut(tag) { ArrayList() }.add(item)
            }
        }
        for ((tag, bucket) in byTag) {
            if (bucket.size < 2) continue
            for (i in bucket.indices) {
                val a = bucket[i]
                for (j in i + 1 until bucket.size) {
                    val b = bucket[j]
                    if (a.id == b.id) continue
                    addRelation(a.title, b.title, "同主题：$tag")
                }
            }
        }

        // Title-word co-occurrence edges (low confidence)
        val byWord = HashMap<String, ArrayList<KnowledgeItemEntity>>()
        for (item in items) {
            for (raw in item.title.split(wordSep)) {
                val w = raw.lowercase(Locale.ROOT)
                if (w.length <= 1) continue
                byWord.getOrPut(w) { ArrayList() }.add(item)
            }
        }
        for ((_, bucket) in byWord) {
            if (bucket.size < 2) continue
            for (i in bucket.indices) {
                val a = bucket[i]
                for (j in i + 1 until bucket.size) {
                    val b = bucket[j]
                    if (a.id == b.id) continue
                    addRelation(a.title, b.title, "标题相关")
                }
            }
        }

        return out.take(30)
    }

    // ---- Gaps & suggestions -------------------------------------------

    private fun detectGaps(
        items: List<KnowledgeItemEntity>,
        mainlines: List<String>,
        relations: List<RelationTriple>
    ): List<String> {
        val gaps = mutableListOf<String>()
        if (items.isEmpty()) {
            gaps += "知识库尚无已整理的知识条目"
            return gaps
        }
        val wikiPages = items.filter { it.sourceType.startsWith("wiki_") }
        if (wikiPages.isEmpty()) gaps += "还没有任何 wiki 页面，需要先完成知识加工"
        val synthesisPages = wikiPages.count {
            it.sourceType == "wiki_index" || it.sourceType == "wiki_overview" || it.sourceType == "wiki_log"
        }
        if (synthesisPages == 0) gaps += "缺少 index / overview / log 合成页，无法形成主线"
        if (mainlines.isEmpty()) gaps += "未能识别到主线（标签聚类为空），建议补充更明确的标签"
        if (relations.isEmpty()) gaps += "知识之间没有形成显式引用或同主题关联"
        if (items.count { parseStringArray(it.tagsJson).isEmpty() } > items.size / 2) {
            gaps += "超过半数知识缺少标签"
        }
        if (items.count { it.summary.isNullOrBlank() } > items.size / 3) {
            gaps += "部分知识缺少摘要，建议补充"
        }
        if (items.any { it.confidence in 0f..0.59f }) {
            gaps += "存在低置信度知识，需要人工复核"
        }
        return gaps
    }

    private fun generateSuggestions(
        gaps: List<String>,
        items: List<KnowledgeItemEntity>
    ): List<String> {
        if (gaps.isEmpty() && items.isNotEmpty()) {
            return listOf("脉络已对齐，建议在知识库内继续添加内容，主线会自动扩展")
        }
        val suggestions = mutableListOf<String>()
        if (gaps.any { it.contains("wiki 页面") }) suggestions += "导入或整理更多内容，让 wiki 页面覆盖核心主题"
        if (gaps.any { it.contains("主线") }) suggestions += "为已归档知识补充标签，主题聚类会自动生成主线"
        if (gaps.any { it.contains("合成页") }) suggestions += "在知识库中创建 index / overview / log 合成页来承载主线"
        if (gaps.any { it.contains("标签") }) suggestions += "为缺少标签的知识补充 3-5 个核心标签"
        if (gaps.any { it.contains("摘要") }) suggestions += "为缺少摘要的知识补充一段 1-2 句的描述"
        if (gaps.any { it.contains("低置信度") }) suggestions += "复核低置信度知识，确认其摘要与归档位置"
        if (gaps.any { it.contains("引用") }) suggestions += "在知识正文里用 [[标题]] 形式建立显式引用"
        if (items.size < 5) suggestions += "知识数量较少，建议继续添加以形成完整脉络"
        if (items.size >= 20) suggestions += "知识条目较多，可考虑拆分为多个知识库以保持主线清晰"
        return suggestions.distinct().ifEmpty { listOf("继续丰富知识库内容") }
    }

    // ---- Helpers ------------------------------------------------------

    private fun parseStringArray(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        val trimmed = json.trim().removePrefix("[").removeSuffix("]")
        if (trimmed.isBlank()) return emptyList()
        return trimmed.split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
    }

    private fun extractWikiLinkTokens(markdown: String): List<String> {
        val regex = Regex("\\[\\[([^\\]\\n]+?)\\]\\]")
        return regex.findAll(markdown).map { it.groupValues[1].substringAfterLast("/").removeSuffix(".md").trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun frontMatterValue(markdown: String, key: String): String? {
        val match = Regex("(?m)^${key}\\s*:\\s*(.+)$").find(markdown) ?: return null
        val raw = match.groupValues[1].trim().removeSurrounding("\"")
        return raw.takeIf { it.isNotBlank() }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
