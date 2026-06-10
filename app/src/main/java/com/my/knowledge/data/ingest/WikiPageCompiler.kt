package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.AnalysisResultEntity
import com.my.knowledge.data.db.entity.ParsedContentEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset

data class WikiPageDraft(
    val type: String,
    val title: String,
    val sourceType: String,
    val markdown: String,
    val summary: String,
    val tagsJson: String,
    val sourceTraceJson: String,
    val wikiPath: String? = null,
)

class WikiPageCompiler {
    fun compile(
        source: SourceDocumentEntity,
        parsed: ParsedContentEntity,
        analysis: AnalysisResultEntity
    ): List<WikiPageDraft> {
        val sourceTitle = source.title.trim().ifBlank { "未命名来源" }
        val entities = parseNamedObjects(analysis.entitiesJson, fallbackType = "entity")
        val concepts = parseNamedObjects(analysis.conceptsJson, fallbackType = "concept")
        val allNames = (entities + concepts).map { it.name }.distinct()
        // Build a `name -> [related names]` map from the analysis
        // relationsJson. The previous compile() ignored relationsJson,
        // so entity / concept pages never showed relation-driven
        // neighbors. This map augments each page's `related` list
        // (entity-declared relations + every cross-entity / cross-
        // concept relation the LLM emitted in the analysis stage).
        val relationsByName: Map<String, List<String>> = buildRelationsByName(analysis.relationsJson, allNames)
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val sourceRef = sourceTitle.escapeYaml()
        val tags = parseStringArray(analysis.tagsJson).take(8)
        val pages = mutableListOf<WikiPageDraft>()

        // P1: frontmatter `type:` 是固定 enum(由 sourceType 决定),不再用
        // LLM 的实体 / 概念语义类型。LLM 给的 "Person" / "Algorithm" /
        // "Theory" 等值改走 `entityType` / `conceptCategory` 新字段,
        // 这样 UI 才能按语义类型分组 / 上色,viewer's type 过滤器也不会被
        // enum 外的值搞坏。
        pages += WikiPageDraft(
            type = "source",
            title = sourceTitle,
            sourceType = "wiki_source",
            markdown = buildString {
                appendFrontMatter(
                    type = "source",
                    title = sourceTitle,
                    created = today,
                    updated = today,
                    tags = tags,
                    related = allNames,
                    sources = listOf(sourceRef)
                )
                appendLine("# $sourceTitle")
                appendLine()
                appendLine("## 摘要")
                appendLine(analysis.summary.ifBlank { parsed.plainText.take(240) })
                appendLine()
                // 始终输出"## 相关页面"段,即使没有识别出实体/概念,也会给一条
                // 跳回 overview 的兜底链接——保证来源页里也有 wikilink 跳转标识,
                // 不会成为"裸来源页"。
                appendLine("## 相关页面")
                if (allNames.isNotEmpty()) {
                    allNames.forEach { appendLine("- [[${it.escapeWikiLink()}]]") }
                } else {
                    appendLine("- [[overview.md]]")
                }
                appendLine()
                appendLine("## 原始内容")
                appendLine(parsed.markdown.take(12_000))
            },
            summary = analysis.summary,
            tagsJson = analysis.tagsJson,
            sourceTraceJson = sourceTrace(source, parsed, analysis, "wiki/sources/${sourceTitle.slug()}.md")
        )

        pages += WikiPageDraft(
            type = "synthesis",
            title = "index.md",
            sourceType = "wiki_index",
            markdown = buildString {
                appendFrontMatter("synthesis", "index.md", today, today, tags, allNames, listOf(sourceRef))
                appendLine("# Wiki Index")
                appendLine()
                appendLine("## Sources")
                appendLine("- [[${sourceTitle.escapeWikiLink()}]]")
                appendLine()
                if (entities.isNotEmpty()) {
                    appendLine("## Entities")
                    entities.forEach { appendLine("- [[${it.name.escapeWikiLink()}]]") }
                    appendLine()
                }
                if (concepts.isNotEmpty()) {
                    appendLine("## Concepts")
                    concepts.forEach { appendLine("- [[${it.name.escapeWikiLink()}]]") }
                    appendLine()
                }
            },
            summary = "Wiki 索引",
            tagsJson = tags.toJsonArray(),
            sourceTraceJson = sourceTrace(source, parsed, analysis, "wiki/index.md")
        )

        pages += WikiPageDraft(
            type = "synthesis",
            title = "overview.md",
            sourceType = "wiki_overview",
            markdown = buildString {
                appendFrontMatter("synthesis", "overview.md", today, today, tags, allNames, listOf(sourceRef))
                appendLine("# Overview")
                appendLine()
                appendLine("## 本次新增来源")
                appendLine("- [[${sourceTitle.escapeWikiLink()}]]：${analysis.summary}")
                appendLine()
                if (allNames.isNotEmpty()) {
                    appendLine("## 关键连接")
                    allNames.forEach { appendLine("- [[${it.escapeWikiLink()}]]") }
                }
            },
            summary = analysis.summary.take(180),
            tagsJson = tags.toJsonArray(),
            sourceTraceJson = sourceTrace(source, parsed, analysis, "wiki/overview.md")
        )

        pages += WikiPageDraft(
            type = "synthesis",
            title = "log.md",
            sourceType = "wiki_log",
            markdown = buildString {
                appendFrontMatter("synthesis", "log.md", today, today, tags, listOf(sourceTitle), listOf(sourceRef))
                appendLine("# Ingest Log")
                appendLine()
                appendLine("## $today")
                appendLine("- Ingested [[${sourceTitle.escapeWikiLink()}]]")
                appendLine("- Generated ${entities.size} entity pages and ${concepts.size} concept pages")
                if (analysis.confidence < 0.6f) appendLine("- REVIEW: low confidence ${analysis.confidence}")
            },
            summary = "Ingest log for $sourceTitle",
            tagsJson = tags.toJsonArray(),
            sourceTraceJson = sourceTrace(source, parsed, analysis, "wiki/log.md")
        )

        entities.forEach { entity ->
            val fromEntity = entity.related
            val fromRelations = relationsByName[entity.name].orEmpty()
            val fromConcepts = concepts.map { it.name }
            val related = (fromEntity + fromConcepts + fromRelations)
                .distinct()
                .filterNot { it.equals(entity.name, ignoreCase = true) }
                .take(12)
            pages += WikiPageDraft(
                type = "entity", // P1: 锁死 entity,不再用 LLM 的语义类型
                title = entity.name,
                sourceType = "wiki_entity",
                markdown = buildEntityPage(entity, today, tags, sourceRef, related),
                summary = entity.description.ifBlank { "实体：${entity.name}" },
                tagsJson = tags.toJsonArray(),
                sourceTraceJson = sourceTrace(source, parsed, analysis, "wiki/entities/${entity.name.slug()}.md")
            )
        }

        concepts.forEach { concept ->
            val fromConcept = concept.related
            val fromRelations = relationsByName[concept.name].orEmpty()
            val fromEntities = entities.map { it.name }
            val related = (fromConcept + fromEntities + fromRelations)
                .distinct()
                .filterNot { it.equals(concept.name, ignoreCase = true) }
                .take(12)
            pages += WikiPageDraft(
                type = "concept", // P1: 锁死 concept
                title = concept.name,
                sourceType = "wiki_concept",
                markdown = buildConceptPage(concept, today, tags, sourceRef, related),
                summary = concept.description.ifBlank { "概念：${concept.name}" },
                tagsJson = tags.toJsonArray(),
                sourceTraceJson = sourceTrace(source, parsed, analysis, "wiki/concepts/${concept.name.slug()}.md")
            )
        }

        return pages.map {
            it.copy(
                markdown = Sanitize.sanitize(it.markdown),
                wikiPath = runCatching {
                    JSONObject(it.sourceTraceJson).optString("wikiPath").takeIf(String::isNotBlank)
                }.getOrNull(),
            )
        }
    }

    /**
     * Backfill variant: only emit entity / concept pages from the
     * analysis JSON, no source / index / overview / log. Used by the
     * "重新生成图谱" path to repopulate the knowledge graph for
     * knowledge bases whose wiki pages were created by the previous
     * (buggy) ingest path that hard-coded `entitiesJson = "[]"`.
     *
     * Returns a fully-formed [WikiPageDraft] list, ready to be
     * converted into `KnowledgeItemEntity` rows by the caller. The
     * `sourceType` is `wiki_entity` / `wiki_concept` exactly like
     * `compile()`, so [KnowledgeRepositoryImpl.rebuildGraphForBase]
     * picks them up via the same `sourceType.startsWith("wiki_")`
     * filter that drives the live ingest.
     */
    fun compileEntityAndConceptPages(
        source: SourceDocumentEntity,
        analysis: AnalysisResultEntity
    ): List<WikiPageDraft> {
        val entities = parseNamedObjects(analysis.entitiesJson, fallbackType = "entity")
        val concepts = parseNamedObjects(analysis.conceptsJson, fallbackType = "concept")
        if (entities.isEmpty() && concepts.isEmpty()) return emptyList()
        val allNames = (entities + concepts).map { it.name }.distinct()
        val relationsByName = buildRelationsByName(analysis.relationsJson, allNames)
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val sourceRef = source.title.trim().ifBlank { source.id }.escapeYaml()
        val tags = parseStringArray(analysis.tagsJson).take(8)
        val pages = mutableListOf<WikiPageDraft>()

        entities.forEach { entity ->
            val related = (entity.related + concepts.map { it.name } + relationsByName[entity.name].orEmpty())
                .distinct()
                .filterNot { it.equals(entity.name, ignoreCase = true) }
                .take(12)
            pages += WikiPageDraft(
                type = "entity", // P1: 锁死 entity,与 compile() 保持一致
                title = entity.name,
                sourceType = "wiki_entity",
                markdown = buildEntityPage(entity, today, tags, sourceRef, related),
                summary = entity.description.ifBlank { "实体：${entity.name}" },
                tagsJson = tags.toJsonArray(),
                sourceTraceJson = sourceTrace(source, parsed = null, analysis, "wiki/entities/${entity.name.slug()}.md")
            )
        }
        concepts.forEach { concept ->
            val related = (concept.related + entities.map { it.name } + relationsByName[concept.name].orEmpty())
                .distinct()
                .filterNot { it.equals(concept.name, ignoreCase = true) }
                .take(12)
            pages += WikiPageDraft(
                type = "concept",
                title = concept.name,
                sourceType = "wiki_concept",
                markdown = buildConceptPage(concept, today, tags, sourceRef, related),
                summary = concept.description.ifBlank { "概念：${concept.name}" },
                tagsJson = tags.toJsonArray(),
                sourceTraceJson = sourceTrace(source, parsed = null, analysis, "wiki/concepts/${concept.name.slug()}.md")
            )
        }
        return pages.map {
            it.copy(
                markdown = Sanitize.sanitize(it.markdown),
                wikiPath = runCatching {
                    JSONObject(it.sourceTraceJson).optString("wikiPath").takeIf(String::isNotBlank)
                }.getOrNull(),
            )
        }
    }

    /**
     * Project `analysis.relationsJson` (`[{source, target, type, ...}]`)
     * into a `name -> [neighbor names]` map keyed on the source side
     * AND the target side, so a page that appears as the target of
     * someone else's relation also shows that relation in its
     * `related` list. This is what the previous `compile()` was
     * missing — without it, entity pages only saw `related_concepts`
     * declared on themselves, and concept pages only saw
     * `related_entities` declared on themselves. Cross-source relations
     * emitted by the analysis stage never made it into the page body.
     */
    private fun buildRelationsByName(
        relationsJson: String,
        allKnownNames: List<String>
    ): Map<String, List<String>> {
        if (relationsJson.isBlank() || relationsJson == "[]") return emptyMap()
        val arr = runCatching { JSONArray(relationsJson) }.getOrNull() ?: return emptyMap()
        // Use the same dedup key as `parseNamedObjects` so relations
        // emitted for "Foo Bar" can resolve to a page titled " Foo Bar"
        // (and vice versa). With the old `.lowercase()` only, a relation
        // for the canonical name missed its whitespace-variant node and
        // got silently dropped here.
        val known = allKnownNames.map { EntityName.dedupKey(it) }.toSet()
        val out = linkedMapOf<String, LinkedHashSet<String>>()
        for (i in 0 until arr.length()) {
            val rel = arr.optJSONObject(i) ?: continue
            val source = rel.optString("source").trim()
            val target = rel.optString("target").trim()
            if (source.isBlank() || target.isBlank()) continue
            if (source.equals(target, ignoreCase = true)) continue
            // Drop edges to nodes we never materialized — otherwise the
            // generated page would carry a dead wikilink the graph
            // rebuild would later warn about as a dangling reference.
            if (source.lowercase() !in known && target.lowercase() !in known) continue
            out.getOrPut(source) { linkedSetOf() } += target
            out.getOrPut(target) { linkedSetOf() } += source
        }
        return out.mapValues { (_, v) -> v.toList() }
    }

    fun merge(existingMarkdown: String, incomingMarkdown: String, pageTitle: String? = null): String {
        if (existingMarkdown.isBlank()) return incomingMarkdown
        if (incomingMarkdown.isBlank()) return existingMarkdown

        // Detect log.md either by an explicit title argument, or by the
        // title field inside the frontmatter (with or without quotes). The
        // original code only matched `title: log.md` (unquoted), which broke
        // for any LLM that wrapped the title in quotes — the very case the
        // prompt asks for.
        val normalizedTitle = pageTitle?.substringAfterLast('/')?.removeSuffix(".md")
        val incomingTitle = frontMatterValue(incomingMarkdown, "title")
        val existingTitle = frontMatterValue(existingMarkdown, "title")
        val isLog = normalizedTitle == "log" || incomingTitle == "log" || existingTitle == "log"

        if (isLog) {
            // Both should have frontmatter; if not, just append body-to-body.
            val incomingBody = stripFrontMatter(incomingMarkdown)
            val existingBody = stripFrontMatter(existingMarkdown)
            if (existingBody.isBlank()) return incomingMarkdown
            val incomingTail = incomingBody.trim().lines().lastOrNull().orEmpty()
            val existingTail = existingBody.trim().lines().lastOrNull().orEmpty()
            val joined = if (incomingTail.isNotBlank() && incomingTail == existingTail) {
                existingMarkdown
            } else {
                existingMarkdown.trimEnd() + "\n\n" + incomingBody.trim()
            }
            return joined
        }

        // ---- Step 1 — deterministic array-field union ----------------
        //
        // Per llm_wiki's `mergePageContent` (src/lib/page-merge.ts):
        // `sources`, `tags`, `related` always get a CASE-INSENSITIVE
        // union of the existing + incoming values, with the first-seen
        // casing winning. We do the union by hand so we never trust the
        // LLM output to be conservative.
        var merged = incomingMarkdown
        for (key in listOf("sources", "tags", "related")) {
            val incomingList = extractFrontMatterList(incomingMarkdown, key)
            val existingList = extractFrontMatterList(existingMarkdown, key)
            val union = mergeListsCaseInsensitive(existingList, incomingList)
            // Only rewrite the line if the union differs from what the
            // LLM already produced, to keep the on-disk page stable when
            // nothing changed.
            if (union.toSet() != incomingList.toSet()) {
                merged = rewriteFrontMatterList(merged, key, union)
            }
        }

        // ---- Step 2 — LOCKED_FIELDS overwrite ------------------------
        //
        // `type`, `title`, `created` are immutable on merge so wikilink
        // referrers stay valid. We rewrite these frontmatter lines to
        // match whatever was already on disk, regardless of what the LLM
        // emitted.
        for (key in listOf("type", "title", "created")) {
            val existingValue = frontMatterValue(existingMarkdown, key)
            if (!existingValue.isNullOrBlank()) {
                merged = rewriteFrontMatterValue(merged, key, existingValue)
            }
        }

        // ---- Step 3 — today `updated` --------------------------------
        //
        // Per llm_wiki the `updated` field is always today, even if the
        // LLM said otherwise. We compute the date in UTC so the same
        // file produces the same stamp regardless of device timezone.
        val today = Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(ZoneOffset.UTC).toLocalDate().toString()
        merged = rewriteFrontMatterValue(merged, "updated", today)

        // ---- Step 4 — body sanity check -----------------------------
        // The caller (IngestOrchestrator) is expected to have already
        // sanity-checked the LLM-merged body. If the LLM's body is
        // empty, fall back to the existing body. If the LLM's body is
        // already a superset of the existing body (the common case for
        // LLM merges that simply appended), we don't tack on a duplicate
        // "合并自旧页面" section.
        val existingBody = stripFrontMatter(existingMarkdown)
        val incomingBody = stripFrontMatter(merged)
        if (incomingBody.isBlank()) return existingMarkdown
        if (existingBody.isBlank()) return merged.trim()
        if (existingBody.contains(incomingBody.take(120))) return merged.trim()
        return merged.trim() + "\n\n## 合并自旧页面\n\n" + existingBody
    }

    fun mergeFrontmatterOnly(
        existingMarkdown: String,
        incomingMarkdown: String,
    ): String {
        if (existingMarkdown.isBlank()) return incomingMarkdown
        if (incomingMarkdown.isBlank()) return existingMarkdown
        var merged = incomingMarkdown
        for (key in listOf("sources", "tags", "related")) {
            val incomingList = extractFrontMatterList(incomingMarkdown, key)
            val existingList = extractFrontMatterList(existingMarkdown, key)
            merged = rewriteFrontMatterList(
                merged,
                key,
                mergeListsCaseInsensitive(existingList, incomingList),
            )
        }
        for (key in listOf("type", "title", "created")) {
            frontMatterValue(existingMarkdown, key)
                ?.takeIf(String::isNotBlank)
                ?.let { merged = rewriteFrontMatterValue(merged, key, it) }
        }
        val today = Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(ZoneOffset.UTC).toLocalDate().toString()
        return rewriteFrontMatterValue(merged, "updated", today).trim()
    }

    /**
     * Case-insensitive list union with first-seen casing winning.
     * Mirrors `mergeLists` in llm_wiki's `src/lib/sources-merge.ts:119-132`.
     */
    private fun mergeListsCaseInsensitive(
        existing: List<String>,
        incoming: List<String>
    ): List<String> {
        // Re-project to the first-seen casing for each case-folded key.
        val caseByKey = linkedMapOf<String, String>()
        for (value in existing + incoming) {
            val key = value.lowercase()
            if (key !in caseByKey) caseByKey[key] = value
        }
        // Preserve the order: existing first, then anything new from incoming.
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<String>()
        for (value in existing + incoming) {
            val key = value.lowercase()
            if (seen.add(key)) result += caseByKey[key]!!
        }
        return result
    }

    /**
     * Rewrite the `key: [...]` line in the YAML frontmatter to the
     * given array value. Preserves the original line's leading indent
     * when present, and falls back to inserting a new line if the key
     * wasn't there to begin with.
     */
    private fun rewriteFrontMatterList(markdown: String, key: String, values: List<String>): String {
        val replacement = if (values.isEmpty()) "$key: []" else "$key: ${values.toYamlArray()}"
        val lineRegex = Regex("^(\\s*${Regex.escape(key)}\\s*:\\s*).*$", RegexOption.MULTILINE)
        return if (lineRegex.containsMatchIn(markdown)) {
            lineRegex.replace(markdown, "$1$replacement")
        } else {
            // Insert before the closing `---` of the frontmatter.
            markdown.replaceFirst(Regex("^---\\s*$", RegexOption.MULTILINE), "---\n$replacement\n---")
        }
    }

    private fun rewriteFrontMatterValue(markdown: String, key: String, value: String): String {
        val quotedValue = if (value.contains(":") || value.contains("\"")) {
            "\"${value.escapeYaml()}\""
        } else value.escapeYaml()
        val lineRegex = Regex("^(\\s*${Regex.escape(key)}\\s*:\\s*).*$", RegexOption.MULTILINE)
        return if (lineRegex.containsMatchIn(markdown)) {
            lineRegex.replace(markdown, "$1$quotedValue")
        } else {
            markdown.replaceFirst(Regex("^---\\s*$", RegexOption.MULTILINE), "---\n$key: $quotedValue\n---")
        }
    }

    /**
     * Returns the body of a markdown document, stripping the YAML frontmatter
     * if present. Robust against the frontmatter being missing, malformed, or
     * having stray `---` markers inside the body.
     */
    private fun stripFrontMatter(markdown: String): String {
        val firstSep = markdown.indexOf("\n---")
        if (!markdown.startsWith("---") || firstSep < 0) return markdown.trim()
        // Find the closing `---` line that ends the frontmatter.
        val lines = markdown.substring(firstSep + 1).split("\n")
        for (i in lines.indices) {
            if (lines[i].trim() == "---") {
                return lines.drop(i + 1).joinToString("\n").trim()
            }
        }
        return markdown.trim()
    }

    private fun buildEntityPage(
        entity: WikiObject,
        today: String,
        tags: List<String>,
        sourceRef: String,
        related: List<String>
    ): String = buildString {
        // 即使 related 为空,也保留 frontmatter 的 related 字段——viewer
        // 会把 frontmatter.related 渲染为顶部跳转 chips,让孤立实体也能
        // 通过"返回索引"链接跳走。这样就不会出现"body 里完全没有 wikilink"
        // 的"裸"实体页。
        //
        // P1: frontmatter `type:` 锁死为 "entity"——由 sourceType 决定。
        // 语义类型(LLM 给的 "Person"/"Algorithm"/"Project" 等)走
        // `entityType` 字段,KnowledgeRepositoryImpl / UI 拿它来分组 + 上色。
        appendFrontMatter(
            type = "entity",
            title = entity.name,
            created = today,
            updated = today,
            tags = tags,
            related = related,
            sources = listOf(sourceRef),
            // P5-merge: emit a `description` field in the frontmatter
            // so llm_wiki's dedup module
            // (nashsu/llm_wiki/src/lib/dedup.ts:124) can use it as a
            // one-line summary for soft-collision clustering.
            // Truncated to 200 chars to keep the frontmatter header
            // scannable in the file viewer.
            description = entity.description.ifBlank { null }?.take(200),
            extraFields = extraFrontMatterFields(entityType = entity.semanticType),
        )
        appendLine("# ${entity.name}")
        appendLine()
        appendLine("## 定义")
        appendLine(entity.description.ifBlank { "从来源资料中识别出的实体。" })
        appendLine()
        appendLine("## 在来源中的作用")
        appendLine(entity.role.ifBlank { "与来源主题相关。" })
        appendLine()
        if (entity.evidence.isNotBlank()) {
            appendLine("## 证据")
            appendLine("> ${entity.evidence}")
            appendLine()
        }
        appendLine("## 相关")
        if (related.isNotEmpty()) {
            related.forEach { appendLine("- [[${it.escapeWikiLink()}]]") }
        } else {
            // 没有任何 related 时的兜底:给个"返回索引"的跳转,保证 body
            // 至少有一处 wikilink,用户点击能跳到 overview.md。
            appendLine("- [[overview.md]]")
        }
    }

    private fun buildConceptPage(
        concept: WikiObject,
        today: String,
        tags: List<String>,
        sourceRef: String,
        related: List<String>
    ): String = buildString {
        // P1: frontmatter `type:` 锁死为 "concept"——由 sourceType 决定。
        // 语义分类(LLM 给的 "Theory"/"Method"/"Framework" 等)走
        // `conceptCategory` 字段,UI 拿它来分组 + 上色。
        appendFrontMatter(
            type = "concept",
            title = concept.name,
            created = today,
            updated = today,
            tags = tags,
            related = related,
            sources = listOf(sourceRef),
            // See note on `buildEntityPage` — llm_wiki dedup reads
            // the `description` field as a one-line summary.
            description = concept.description.ifBlank { null }?.take(200),
            extraFields = extraFrontMatterFields(conceptCategory = concept.semanticType),
        )
        appendLine("# ${concept.name}")
        appendLine()
        appendLine("## 定义")
        appendLine(concept.description.ifBlank { "从来源资料中识别出的概念。" })
        appendLine()
        appendLine("## 为什么重要")
        appendLine(concept.role.ifBlank { "它帮助组织和连接来源资料中的关键观点。" })
        appendLine()
        if (concept.evidence.isNotBlank()) {
            appendLine("## 来源语境")
            appendLine("> ${concept.evidence}")
            appendLine()
        }
        appendLine("## 相关")
        if (related.isNotEmpty()) {
            related.forEach { appendLine("- [[${it.escapeWikiLink()}]]") }
        } else {
            // 概念页:与实体页同样的兜底,确保孤立概念也能通过"返回索引"导航
            appendLine("- [[overview.md]]")
        }
    }

    /**
     * P1: 把 entityType / conceptCategory 渲染成 frontmatter 行。
     * 走 List<Pair<String, String>> 而不是 Map,确保输出顺序稳定——
     * LLM 给的 "type" / "category" 老字段已经被 WikiObject.semanticType
     * 接管,这里只关心新字段。
     */
    private fun extraFrontMatterFields(
        entityType: String? = null,
        conceptCategory: String? = null,
    ): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (!entityType.isNullOrBlank()) {
            out += "entityType" to entityType.escapeYaml()
        }
        if (!conceptCategory.isNullOrBlank()) {
            out += "conceptCategory" to conceptCategory.escapeYaml()
        }
        return out
    }

    private fun StringBuilder.appendFrontMatter(
        type: String,
        title: String,
        created: String,
        updated: String,
        tags: List<String>,
        related: List<String>,
        sources: List<String>,
        extraFields: List<Pair<String, String>> = emptyList(),
        description: String? = null,
    ) {
        appendLine("---")
        appendLine("type: ${type.escapeYaml()}")
        appendLine("title: ${title.escapeYaml()}")
        appendLine("created: $created")
        appendLine("updated: $updated")
        // P5-merge: emit `description` only when non-blank, so
        // frontmatter stays clean for entities whose LLM did not
        // return any description text. Empty-string would otherwise
        // produce `description: ""` which llm_wiki's YAML parser
        // accepts but the dedup module would treat as a real
        // (and useless) summary.
        if (!description.isNullOrBlank()) {
            appendLine("description: ${description.escapeYaml()}")
        }
        appendLine("tags: ${tags.toYamlArray()}")
        appendLine("related: ${related.toYamlArray()}")
        appendLine("sources: ${sources.toYamlArray()}")
        extraFields.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine("---")
    }

    private fun parseNamedObjects(json: String, fallbackType: String): List<WikiObject> {
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                when (val value = array.opt(index)) {
                    is JSONObject -> {
                        // Whitespace-collapse + Unicode-normalize the name
                        // before storing / dedup. " Foo Bar" / "Foo  Bar" /
                        // "Foo Bar" all collapse to "Foo Bar". Casing
                        // is preserved (EntityName.canonical does NOT
                        // lowercase) so "iOS" stays "iOS" on the wiki page.
                        val rawName = value.optString("name").ifBlank { value.optString("title") }
                        val name = EntityName.canonical(rawName)
                        if (name.isBlank()) null else WikiObject(
                            name = name,
                            // P1: 语义类型走 `entityType` / `conceptCategory`,fallback
                            // 到老的 `type` / `category` 以保持向后兼容(老 LLM 输出
                            // / 老 analysis JSON 仍能正确解析)。
                            semanticType = value.optString("entityType").ifBlank { value.optString("conceptCategory") }
                                .ifBlank { value.optString("type").ifBlank { value.optString("category") } }
                                .ifBlank { fallbackType }
                                .trim(),
                            description = value.optString("description").ifBlank { value.optString("definition") },
                            role = value.optString("role_in_source").ifBlank { value.optString("why_it_matters") },
                            evidence = value.optString("evidence").ifBlank { value.optString("source_context") },
                            related = parseStringArray(value.optJSONArray("related_concepts")) +
                                parseStringArray(value.optJSONArray("related_entities"))
                        )
                    }
                    is String -> WikiObject(name = EntityName.canonical(value), semanticType = fallbackType)
                    else -> null
                }
            }
        }.getOrElse {
            parseStringArray(json).map { WikiObject(name = EntityName.canonical(it), semanticType = fallbackType) }
        }.distinctBy { EntityName.dedupKey(it.name) }.take(24)
    }

    private fun parseStringArray(json: String): List<String> =
        runCatching { parseStringArray(JSONArray(json)) }.getOrDefault(emptyList())

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { value -> value.isNotBlank() } }
    }

    private fun extractFrontMatterList(markdown: String, key: String): List<String> {
        val line = markdown.lines().firstOrNull { it.trimStart().startsWith("$key:") } ?: return emptyList()
        return line.substringAfter("[").substringBefore("]").split(",").map { it.trim().trim('"') }.filter { it.isNotBlank() }
    }

    private fun frontMatterValue(markdown: String, key: String): String? {
        if (!markdown.startsWith("---")) return null
        val lines = markdown.lines()
        if (lines.isEmpty() || lines.first().trim() != "---") return null
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.trim() == "---") break
            if (line.trimStart().startsWith("$key:")) {
                val raw = line.substringAfter(":").trim().trim('"')
                if (raw.isBlank() || raw.startsWith("[")) return null
                return raw
            }
        }
        return null
    }

    private fun sourceTrace(source: SourceDocumentEntity, parsed: ParsedContentEntity?, analysis: AnalysisResultEntity, path: String): String =
        """{"wikiPath":"${path.escapeJson()}","sourceId":"${source.id}","parsedContentId":"${parsed?.id ?: ""}","analysisResultId":"${analysis.id}","sourceTitle":"${source.title.escapeJson()}"}"""

    private data class WikiObject(
        val name: String,
        // P1: `semanticType` 取代了原来的 `type` 字段。`type` 之前被三处
        // 同时用:(1) 作为 WikiObject 自己的 kind 标识;(2) 直接写入 wiki
        // page frontmatter `type:` 字段(导致 enum 冲突);(3) 被
        // KnowledgeRepositoryImpl.normalizeWikiGraphType 当作图谱节点的
        // type。新设计中:
        //   - Wiki page frontmatter `type:` 固定 enum("entity"/"concept"/...),
        //     跟 `WikiPageDraft.sourceType` 一一对应,不由 LLM 给;
        //   - `semanticType` 走 frontmatter 新字段 `entityType` /
        //     `conceptCategory`(由 `fallbackType` 提示是实体还是概念);
        //   - 图谱节点的 `type` 由 KnowledgeRepositoryImpl 读
        //     `entityType` / `conceptCategory`,fallback 到
        //     "entity" / "concept"。
        val semanticType: String = "entity",
        val description: String = "",
        val role: String = "",
        val evidence: String = "",
        val related: List<String> = emptyList()
    )
}

private fun List<String>.toYamlArray(): String =
    distinct().filter { it.isNotBlank() }.joinToString(", ", "[", "]") { "\"${it.escapeYaml()}\"" }

private fun List<String>.toJsonArray(): String =
    distinct().filter { it.isNotBlank() }.joinToString(",", "[", "]") { "\"${it.escapeJson()}\"" }

private fun String.slug(): String {
    val normalized = Normalizer.normalize(this, Normalizer.Form.NFKC).lowercase()
    return normalized.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').ifBlank { "page" }
}

private fun String.escapeWikiLink(): String = replace("[[", "").replace("]]", "").trim()

private fun String.escapeYaml(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
