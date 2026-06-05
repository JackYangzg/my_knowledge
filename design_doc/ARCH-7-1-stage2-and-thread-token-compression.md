# ARCH-7.1 — Generation / 灵感脉络 阶段 LLM Token 压缩（续 ARCH-7）

> 状态：**DRAFT**（2026-06-05）
> 关系：ARCH-7（analysis 阶段，**APPROVED**，PR1 已合入 `6dcd234`，PR2/PR3 未开工）的续篇
> 范围：`com.my.knowledge.data.ai.AiPromptTemplates.generationPrompt` / `mergePrompt` / `inspirationThreadPrompt` / `requestAiRawOutput` / `requestAiMerge` / `LlmInspirationThreadWorker`
> 不动：ARCH-7 已落定的 analysis 阶段改动
> 落地分批：3 个 PR（PR2-G + PR3-G + PR-T，**G = Generation，T = Thread**），不破坏现有业务

---

## 0. 为什么需要续篇

ARCH-7 的标题是 "Analysis 阶段 LLM Prompt / Response 精简"，PR1 完工后，用户在 `/office-hours` 中明确指出：

> "analysis、generation 阶段请求大模型的 prompt 非常长，大模型返回的内容也非常长，请分析如何精简优化，但是不能影响现在的业务。"

但 ARCH-7 §0 范围只覆盖 `AnalysisStage` / `runAnalysisTask` / `requestAiAnalysis` / `requestAiAnalysisLongSource` / `AiPromptTemplates.analysisPrompt` / `ANALYSIS_SCHEMA`，**不包含 generation 阶段**。验证过当前代码后，有 3 个独立 LLM 调用点超出了原 ARCH-7 的范围：

1. **Stage 2 generation**（`runGenerationTask` 调 `requestAiRawOutput`，拼 `generationPrompt` + `buildGenerationUserMessage`）—— 输入侧 5-30K 字符，输出侧 FILE 块 5-15K 字符，中等源单次 20-40K token 来回。
2. **Stage 2 merge**（`requestAiMerge`，拼 `mergePrompt`）—— 输入侧双倍页面长度（existing + incoming），输出侧 1 份合并页面。
3. **灵感脉络增量生成**（`LlmInspirationThreadWorker` 调 `inspirationThreadPrompt`）—— 输入侧历史 30 条摘要 + 本次新灵感完整内容（4K cap）+ 现有脉络，输出侧 ≤ 2K 字符（受 prompt §9 硬约束）。

这三处的 token 浪费模式与 analysis 阶段**不完全一致**：

| 维度 | analysis | generation | merge | inspiration thread |
|------|----------|------------|-------|---------------------|
| 输入主因 | `currentIndex` 20K 重复（PR1 已降 5K） | `WIKI_SCHEMA` 表格 + `generationPrompt` 自身 4K + analysis 摘要 + structured context + source 24K | existing + incoming 双页面（每页 1-5K） | 历史 30 条摘要（每条 ~150 字 = 4.5K）+ 本次完整内容 4K cap |
| 输出主因 | 10 entities + 8 concepts + relations/claims/gaps 全展开 | entity/concept 页 1-2 段 + index.md/overview.md/log.md 全套合成 | 1 份合并 body | 已有 ≤ 2K 硬约束，浪费点在输入而非输出 |
| 浪费点 | 之前是 Postmortem 注释 + alias 字段 + 分块去重 | **frontmatter 规则 +8 段示例 + wiki schema 表格重复** | **重复 sourceFileName 与 languageDirective×2** | **languageDirective×2 重复**（头部+尾部锚），新灵感 content 4K cap 可能 over-shoot |

下文分 3 个 PR 解决，每 PR 都是独立的"不动业务 + 收紧 prompt/response"手术。

---

## 1. 当前 generationPrompt 拼装流程

```
GenerationStage.run
  └─ IngestOrchestrator.runGenerationTask                  (line 746-902)
        ├─ 写 rootItem（line 757-780）
        ├─ if ai.isAvailable()
        │     → requestAiRawOutput                          (line 1237-1321)
        │         ├─ buildCurrentIndex(kbId)               (line 2024, cap=CURRENT_INDEX_PROMPT_CHARS=5_000)
        │         ├─ buildStructuredAnalysisContext(analysis) (line 1332-1394, 拼 entities/concepts/relations markdown)
        │         ├─ systemPrompt = AiPromptTemplates.generationPrompt(
        │         │     fileName=source.title,
        │         │     analysisResult=analysis.summary + structuredContext,
        │         │     sourceContent=parsed.markdown,        ← 调用方不传,只传 schema
        │         │     schema=WIKI_SCHEMA,                   ← line 2495-2530, 1.1K 字符
        │         │     purpose=WIKI_PURPOSE,
        │         │     currentIndex=currentIndex,
        │         │     overview=overview,                    ← wiki/overview.md 全文,无 cap
        │         │     language=detectedLanguage,
        │         │   )                                       ← 系统 prompt 自身 4-5K 字符(见 §1.1)
        │         └─ userPrompt = buildGenerationUserMessage(
        │               fileName, analysis, markdown, structuredContext
        │             )                                       (line 2053-2081, ~5K 字符)
        └─ FileBlockParser.parseDetailed(response)            ← response 通常 5-15K 字符
```

### 1.1 `generationPrompt` 模板拆解（`AiPromptTemplates.kt:181-348`，167 行）

| 段 | 字符 | 必要性 |
|---|-----|------|
| `languageDirective`（line 51-61，头部） | ~600 | 必留 |
| "You are a wiki maintainer..." 角色声明 | ~150 | 必留 |
| `## IMPORTANT: Source File` | ~150 | 必留 |
| `schemaBlock`（`WIKI_SCHEMA` 1.1K 表格 + Project Schema 说明） | ~1.5K | **重复**：后文 "What to generate" 又重写了一遍 type→directory 映射 |
| `## What to generate` 8 条 + Paper/Method 补充 | ~1.5K | **冗长**：第 1/2/3/4 条是核心，6/7/8 是合成页，模板化即可 |
| `## Frontmatter Rules` 5 条 + 完整 example page | ~1.5K | **example 占了大头**（line 258-273，15 行 YAML+body 实际只用 type/title/created/updated/tags/related/sources 这 7 字段） |
| `## Review block types` + OPTIONS 说明 | ~700 | **OPTIONS 规则是反 LLM 自由发挥的死约束，但给得啰嗦** |
| `## Wiki Purpose` + `## Current Wiki Index` + `## Current Overview` | ~80 + 5K + **overview 无 cap** | **overview 是隐形炸弹**：wiki/overview.md 累积到 50K+ 后未截断，整段被拼进 system prompt |
| `## Output Format` FILE/REVIEW 模板 | ~1K | 必留（parser 严格） |
| `## Output Requirements` 7 条 | ~700 | 必留 |
| `languageDirective`（line 346，尾部锚） | ~600 | 必留（防语言漂移） |

总长约 **4-5K 字符 + overview 全文 + index 5K + purpose 80** ≈ 单 call 9-10K 系统 prompt，中等 KB 下用户单源 generation 一次约 25K token 输入。

### 1.2 `mergePrompt` 拆解（`AiPromptTemplates.kt:363-399`，37 行）

```
languageDirective (头)
你正在把同一 Wiki 页面的两个版本合并为一个连贯的文档... (规则 4 条)
输出要求 (4 条: 第一字符 ---/不要 preamble/caller 覆盖 fields/...)
## Existing version on disk
$existingContent
---
## Newly generated version (from $sourceFileName)
$incomingContent
---
Now output the merged file. Start with `---` on the first line.
languageDirective (尾)
```

问题：
- `languageDirective` ×2（头+尾），单次 call 浪费 ~1.2K 字符
- 4 条规则有 2 条（"caller 覆盖 fields"）是给模型解释 orchestrator 行为，模型其实不需要知道 —— 它只要"输出完整文件"即可
- `existingContent` + `incomingContent` 都可能 1-5K 字符，这是输入主因，**无法压缩**（必须给模型两份原文才能合并）
- 输出固定 1 份 body，无法在输出侧精简

### 1.3 `inspirationThreadPrompt` 拆解（`AiPromptTemplates.kt:598-710`，113 行）

```
languageDirective (头)
你是用户的灵感脉络编辑... (角色 ~500 字)
## 模式:增量更新(关键,跟从零生成的区别)  (~700 字,5%/20%/8%/2% 概率分布是设计语言,可砍)
## 输入 (知识库上下文 ~200 字, 本次新增灵感 完整内容 4K cap, 历史灵感 30 条摘要, 现有脉络)
## 输出 (完整 JSON 示例 ~800 字, 8 条硬约束 ~600 字)
languageDirective (尾)
```

问题：
- `languageDirective` ×2（头+尾），单次 ~1.2K 字符
- 4% 概率分布那段是 **设计语言而非约束**（"70% 丰富主轴 / 20% 分叉并行线 / 8% 矛盾 / 2% 主题切换"），模型不会真的去查表 —— 可砍
- JSON 示例占了大半段，但 JSON 是 LLM 真正参考的契约，不能直接砍
- **本次新增灵感 content 用 4K cap 太宽**：灵感条目本身平均 200-500 字符，4K cap 几乎一定包含全部内容。如果用户的长灵感 >4K（极端情况），现状是直接截断导致信息丢失。**正确的截断策略应该是按段落/句子切，不要按字符硬切**

---

## 2. PR 拆分（3 个独立 PR，不破坏现有业务）

| PR | 目标 | 输入侧 | 输出侧 | schema 形状 | cache 影响 | 风险 |
|----|------|-------|-------|-----------|----------|------|
| **PR2-G** | generation 阶段 system prompt -40% | 删 schemaBlock 与 What to generate 的 type→dir 重复段、删 example page 中 unused YAML 行、给 overview 加 cap 5K、删 4 条 rule 中的"caller 覆盖 fields"类内部说明 | -5%（FILE 块不再受多余 example 字段诱惑） | 不变 | 兼容：保持 `INGEST_GENERATION_V1` | 极低：example 改薄后 LLM 仍能解析 schema |
| **PR3-G** | merge 阶段 system prompt -50% | `mergePrompt` 删 `languageDirective` 尾部（保留头部即可——merge 内容是已生成的 wiki 页面，本身就是 `languageDirective` 指定语言）+ 删 2 条"caller 覆盖"类内部说明 | 不变（输出固定 1 份 body） | 不变 | 兼容 | 低：merge prompt 短对合并质量无影响，因为 existing + incoming 已经约束了输出 |
| **PR-T** | 灵感脉络 system prompt -30% + 改善 4K 截断 | 删概率分布设计语言段、`languageDirective` 尾部、4K 字符 cap 改"按段落切 + 保留头/尾各 2 段"（最多 4K） | 不变（已有 ≤ 2K 硬约束） | 不变 | 兼容 | 低 |

**理由**：

- 三个 PR 都**不动 schema 形状**、**不升 promptVersion**、**不动 cache key**——`requestAiRawOutput` 当前不写 `analysisHash`-like cache（`IngestOrchestrator.kt:746-902` 没有读 `ingest_cache` 表的 generation 路径），PR2-G 落地零 cache 风险。
- `requestAiMerge` 与 `LlmInspirationThreadWorker` 同样无 cache 命中机制，是 fire-and-forget 调用。
- 三个 PR 解耦：PR2-G 是 system prompt 内部瘦身，PR3-G 是 merge prompt 瘦身，PR-T 是 thread prompt 瘦身与截断策略改进。可以分开验收。

---

## 3. PR2-G：generationPrompt 输入侧精简

### 3.1 删 schemaBlock 与 What to generate 的 type→dir 重复

文件：`app/src/main/java/com/my/knowledge/data/ai/AiPromptTemplates.kt:181-348` + `app/src/main/java/com/my/knowledge/data/ingest/IngestOrchestrator.kt:2495-2530`（WIKI_SCHEMA）

**当前问题**：
- `schemaBlock` 把 `WIKI_SCHEMA`（1.1K 字符的 markdown 表格）注入 system prompt
- 同 prompt 下文 "What to generate" 第 1-5 条又用自然语言重写了 `source → wiki/sources/`、`entity → wiki/entities/`、`concept → wiki/concepts/` 的映射
- **同一信息两遍**

**改动**：
- 删 `schemaBlock` 中"Use this schema as the primary routing rule for page types and directories..."整段说明（`AiPromptTemplates.kt:194-203`）
- 保留 `WIKI_SCHEMA` 表格本身（line 2518-2530），它**比自然语言映射更精确**（明确 `entity → wiki/entities/` 而非 LLM 自己脑补）
- 改写"What to generate" 1-5 条：直接引用 `WIKI_SCHEMA` 的表格，不重复列映射。改为：
  ```
  1. A source summary page (path from WIKI_SCHEMA table, row "source")
  2. For academic papers, a paper page (path from WIKI_SCHEMA table, row "paper")
  3. Entity pages: directory from WIKI_SCHEMA table, row "entity"
  4. Concept pages: directory from WIKI_SCHEMA table, row "concept"
  5. For academic papers, method pages (row "method" of WIKI_SCHEMA table)
  ```
- 保留 6/7/8（index.md/overview.md/log.md 路径），这些是固定路径不能改

预估：system prompt -700 字符（删 schemaBlock 说明 + 重写 What to generate）。

### 3.2 删 example page 中的 unused YAML 行

文件：`app/src/main/java/com/my/knowledge/data/ai/AiPromptTemplates.kt:258-273`

**当前问题**：example 完整 15 行 YAML+body，但实际 parser 只读这 7 字段：`type` / `title` / `created` / `updated` / `tags` / `related` / `sources`（`WikiPageCompiler.merge:344` 的 LOCKED_FIELDS 集合 + merge 函数体 line 326-336 处理的 3 个 list 字段）。但 example 写的是 7 字段 —— 已经**没多余**。

**再读**：`WikiPageCompiler.compile` 还会写 entityType / conceptCategory 两个 semantic 字段（line 469, 512），所以 example **确实**没冗余。**这部分不动**。

唯一可砍：line 268-273 里的 "Body content goes here. Use [[wikilink]] syntax in the body for cross-references." 是重复的——line 275-276 的"Other rules"已经说了一遍。删 example 里这一行。

预估：system prompt -50 字符。

### 3.3 overview 加 cap

文件：`app/src/main/java/com/my/knowledge/data/ingest/IngestOrchestrator.kt:1261-1270`

**当前**：`overview = db.knowledgeItemDao().getByKbSourceTypeAndTitle(kbId.orEmpty(), "wiki_overview", "overview.md")?.contentMarkdown ?: ""` —— **无 cap**。

**问题**：wiki/overview.md 是累积文件，每 ingest 一次都拼接（`WikiPageCompiler.compile:113-132`）。10 次 ingest 后 overview 可能 10-30K 字符；100 次后 50K+。**整段被塞进 system prompt**。

**改动**：
- 在 `IngestOrchestrator.kt:2024-2037` 的 `buildCurrentIndex` 旁边加 `buildCurrentOverview(kbId: String?)`：
  ```kotlin
  private suspend fun buildCurrentOverview(kbId: String?): String {
      if (kbId.isNullOrBlank()) return ""
      val page = db.knowledgeItemDao().getByKbSourceTypeAndTitle(kbId, "wiki_overview", "overview.md")
          ?: return ""
      // Cap 5K — overview is a CONTEXT HINT, not a source. Stage 2 LLM
      // only needs to know "the wiki currently covers X / Y / Z" plus
      // maybe 1-2 representative paragraphs. Full overview is rebuilt
      // after this generation anyway.
      val body = stripFrontMatter(page.contentMarkdown)
      return body.take(5_000)
  }
  ```
- `IngestOrchestrator.kt:1248` 改用 `buildCurrentOverview(kbId)` 替代直接读 `contentMarkdown`
- prompt 中 `if (overview.isNotBlank()) "## Current Overview ..."` 不变，由调用方保证 ≤ 5K

预估：单 call 输入 -10-50K 字符（按 KB 大小），**这是 PR2-G 最大的收益**。

### 3.4 PR2-G 验收

- `./gradlew testDebugUnitTest --tests 'com.my.knowledge.data.ai.AiPromptTemplatesTest' --tests 'com.my.knowledge.data.ingest.*'`
- 新增 `AiPromptTemplatesTest` 用例：`generationPrompt` 长度 < 8K（按 overview 5K cap 后）
- 新增 `WikiOverviewCapTest`：构造一个 50K overview 页面，验证 `buildCurrentOverview` 截到 5K
- 端到端：3 档 fixture（短源 / 中等源 / 长源），跑 generation，对比 FILE 块数量与 entity/concept 页面内容（不期望完全一致，期望页面结构稳定）
- 真实设备跑 1 篇中等源，观察运行日志 `systemPrompt=N` 的下降幅度

---

## 4. PR3-G：mergePrompt 输入侧精简

### 4.1 删 `languageDirective` 尾部

文件：`app/src/main/java/com/my/knowledge/data/ai/AiPromptTemplates.kt:363-399`

**当前**：`languageDirective` 出现 2 次（头+尾）。

**改动**：删尾部（line 398），只保留头部。

**理由**：
- merge 任务的输入是 **已生成的中文 wiki 页面**（`existingContent` + `incomingContent` 都是 Stage 2 出来的，已经受 `generationPrompt` 强制语言）
- 输入侧的语言信号已经从 existing/incoming 两份中文内容中显式可见（"最大 token 频率"理论），LLM 在没有尾锚的情况下也不会翻成英文
- 实测保留头部 `languageDirective` 足以防漂移（这是 ARCH-7 的实验观察，PR1 在 chunk prompt 中也只用头部不用尾部）
- 风险：如果 incoming 是英文怎么办？**答：incoming 永远是 Stage 2 的输出，受 `generationPrompt` 强制中文。如果用户手动塞英文页面进 merge，那是 edge case，不在本设计稿范围**。

预估：merge 单 call 输入 -600 字符（-25%）。

### 4.2 删 2 条"caller 覆盖"类内部说明

文件：`app/src/main/java/com/my/knowledge/data/ai/AiPromptTemplates.kt:380-384`

**当前**："调用方会用 deterministic 值覆盖 `sources` / `tags` / `related` / `updated` —— 你的工作只是 body 与其他字段"

**问题**：这条规则是给 LLM 解释 **orchestrator 行为**，跟"输出完整文件"无关。LLM 实际工作时只看到"existing + incoming 2 份 markdown"，它无法理解这条规则的真正含义（"deterministic 值覆盖"是 Kotlin 代码里的 `rewriteFrontMatterList`，LLM 看不见）。

**改动**：删这条规则。

**保留**：
- 4 条核心规则（保留所有事实、消除冗余、重组章节、保留 wikilink）
- 4 条输出要求（首字符 `-`、完整文件、不要 preamble、YAML frontmatter + body）

预估：-150 字符。

### 4.3 PR3-G 验收

- `AiPromptTemplatesTest` 加用例：`mergePrompt` 长度 < 2.5K（现状约 3K + existing/incoming 不计）
- 端到端：构造一对中文合并场景（existing = 简单 A，incoming = 详细 B），跑 `requestAiMerge`，验证输出仍是中文（不退到英文），且包含 A+B 的所有事实
- 实测至少 3 对不同长度（< 1K, 1-3K, 3-5K existing/incoming）

---

## 5. PR-T：灵感脉络 prompt 精简 + 4K 截断策略改进

### 5.1 删概率分布设计语言段

文件：`app/src/main/java/com/my/knowledge/data/ai/AiPromptTemplates.kt:611-616`

**当前**：
```
  - 70% 情况下,本次新灵感会**丰富**现有主线的某一段,而非开新线;
  - 20% 情况下,新灵感会**分叉**出并行线(并行探索);
  - 8% 情况下,新灵感跟某条旧灵感**矛盾或推翻**——把旧段标记为 obsolete;
  - 2% 情况下,新灵感标志用户**主题切换**——主轴整体演化,旧主线变 background。
```

**问题**：这 4 行是**设计语言而非约束**。LLM 不会真的去查表——它按"丰富 / 分叉 / 矛盾 / 切换"的语义直觉得出 diff。留着只会占 token 不产生效果。

**改动**：删 4 行，整段"模式:增量更新"压缩为 1 句：
```
你不是从零生成脉络——existingThread 是上一次生成的脉络快照,代表用户已经认定的「主轴」。
在保持主轴稳定的前提下,接入本次新增,只描述实际发生了什么变化。
```

预估：-400 字符。

### 5.2 删 `languageDirective` 尾部

文件：`app/src/main/java/com/my/knowledge/data/ai/AiPromptTemplates.kt:709`

**改动**：删 line 709 的 `languageDirective(language)` 尾部追加。

**理由**：同 PR3-G §4.1。灵感脉络的输入包含历史灵感摘要（中文标签 + 中文标题）+ 本次新灵感（中文内容），语言信号已经显式。LLM 不会因为少了尾锚就翻英文。

预估：-600 字符。

### 5.3 4K 字符 cap 改为段落级截断

文件：`app/src/main/java/com/my/knowledge/data/ai/AiPromptTemplates.kt:633-635`

**当前**：
```kotlin
appendLine("```")
appendLine(newInspiration.content.take(4_000))
appendLine("```")
```

**问题**：直接 `take(4_000)` 会在用户长灵感的中间硬切，产生半句话 / 半段截断，LLM 拿到"前 4K"和"中间某字"会误读语义。

**改动**：改为段落级截断——保留头 3 段 + 尾 1 段，每段独立 `< 4K` 时全保留：

```kotlin
appendLine("```")
val content = newInspiration.content
val cap = 4_000
val paragraphs = content.split(Regex("\\n\\s*\\n"))
val truncated = if (content.length <= cap) content else {
    val head = paragraphs.take(3).joinToString("\n\n")
    val tail = paragraphs.takeLast(1).joinToString("\n\n")
    val headTail = "$head\n\n... [中间略] ...\n\n$tail"
    if (headTail.length <= cap) headTail
    else head.take(cap - tail.length - 30) + "\n\n... [中间略] ...\n\n" + tail
}
appendLine(truncated)
appendLine("```")
```

预估：输入侧字符数持平（仍在 ≤4K），但**对长灵感的语义保留度大幅提升**。输出端不会因截断产生错位。

### 5.4 PR-T 验收

- `AiPromptTemplatesTest` 加用例：
  - `inspirationThreadPrompt` 长度 < 4.5K（现状 5K+）
  - 4K 截断用例：1K 灵感不切、3K 灵感不切、5K 灵感切到头 3 段+尾 1 段、20K 灵感切到头 3 段+尾 1 段且中间有 `[中间略]` 标记
- 端到端：跑一次 `LlmInspirationThreadWorker`，对比脉络输出 JSON 形状（字段、长度约束）是否一致

---

## 6. 风险与回退

| 风险 | 触发条件 | 回退 |
|------|---------|------|
| PR2-G 删 schemaBlock 说明后 LLM 误用 path | LLM 把 "source → wiki/sources/" 自己脑补成 "wiki/sources/sourceTitle" 漏掉文件名 | 在 WIKI_SCHEMA 表格上方加一行明确路径示例（cost +80 字符，负向收益 < 正向） |
| PR2-G overview cap 5K 太短，LLM 看不到"整体覆盖范围" | 50+ ingest 后 LLM 输出的 overview 与前一份几乎无 diff | cap 调到 10K |
| PR3-G 删 languageDirective 尾锚后，incoming 是英文时 LLM 翻英文 | 用户手动塞英文 wiki 页面进 merge | 保留尾锚（cost +600 字符） |
| PR-T 4K 截断策略改了后 `LlmInspirationThreadWorker` 输入 hash 不变 | 灵感的 content hash 仍按整篇算，不是按 truncated 部分 | 不动 hash，truncation 只影响 prompt，不影响幂等性 |

---

## 7. 验证策略（统一）

### 7.1 单元测试

- `com.my.knowledge.data.ai.AiPromptTemplatesTest`
  - `generationPrompt` 长度 < 8K（PR2-G 后）
  - `mergePrompt` 长度 < 2.5K（PR3-G 后），且 `languageDirective` 只出现 1 次
  - `inspirationThreadPrompt` 长度 < 4.5K（PR-T 后），且 `languageDirective` 只出现 1 次
  - 4K 截断用例：1K/3K/5K/20K 灵感（PR-T 后）
- `com.my.knowledge.data.ingest.WikiOverviewCapTest`（新）：50K overview → 5K（PR2-G）

### 7.2 端到端

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest \
  --tests 'com.my.knowledge.data.ai.*' \
  --tests 'com.my.knowledge.data.ingest.*'
```

### 7.3 运行时指标

每次 generation call 在 `IngestOrchestrator.kt:1274` 打印 `systemPrompt=N 字符, userPrompt=N 字符`。
每次 merge call 在 `requestAiMerge` 旁加同样日志。
每次 thread call 在 `LlmInspirationThreadWorker.kt` 已有日志（line 82 附近）。

回归基线（PR2-G 之前）：
- generation system: ~9-10K 字符 + overview 全文（无 cap，可能 50K+）
- generation user: ~5K 字符（analysis + source 24K cap + structured）
- merge system: ~1.2K 字符
- thread system: ~5K+ 字符

PR2-G 后预期：generation system -50%（最大收益来自 overview cap，-10-50K）。
PR3-G 后预期：merge system -50%（-750 字符）。
PR-T 后预期：thread system -30%（-1.5K 字符），4K 截断对长灵感语义保留更好。

---

## 8. 关联

- 母设计稿 `design_doc/ARCH-7-analysis-llm-token-compression.md` —— 本设计稿是它的"阶段覆盖"续篇
- 母设计稿 PR1（commit `6dcd234`）—— 已合入，本续篇不重复
- 母设计稿 PR2 / PR3 —— 仍是 analysis 阶段的输出侧 / 并行化，本续篇**不**接管，仍由 ARCH-7 主体负责
- 上下文 `memory/2026-06-04-ingest-remote-llm-timeout-optimization.md` —— streaming + retry + cap 24K，与本设计正交
- 待办 `ARCH-3 / ARCH-4 / ARCH-5`（`design_doc/ARCH-3-4-5-architecture-evolution.md`）—— 长期架构演进，本设计稿属于短期成本/延迟优化

---

## 9. 决策记录

- **2026-06-05 立项**：用户在 `/office-hours` 中指出"analysis、generation 阶段 prompt 与 response 都长"——核实代码后，ARCH-7 只覆盖 analysis，generation / merge / 灵感脉络的精简机会未规划。
- **2026-06-05 落定分批**：3 个独立 PR（PR2-G / PR3-G / PR-T），全部不动 schema 形状 / 全部不升 promptVersion / 全部不动 cache key。
- **未决项**：
  - 是否在 PR2-G 中同时把 `STAGE2_SOURCE_EXCERPT_CHARS=24_000`（`IngestOrchestrator.kt:2443`）降到 16K？—— 暂不砍，source 24K 是 2026-06-04 streaming 优化的产物，砍太多可能影响"长源 FILE 块完整性"。可作 PR2-G.1 后续。
  - PR2-G 是否需要给 `languageDirective` 头部加 `length cap`？当前 600 字符已经够紧，不动。
