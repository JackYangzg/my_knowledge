# ARCH-7 — Analysis 阶段 LLM Prompt / Response 精简

> 状态：**APPROVED**（2026-06-05，`/office-hours` 会话评审通过）
> 范围：`com.my.knowledge.data.ingest` 下 `AnalysisStage` / `IngestOrchestrator.runAnalysisTask` / `requestAiAnalysis` / `requestAiAnalysisLongSource` / `AiPromptTemplates.analysisPrompt` / `ANALYSIS_SCHEMA`
> 对应 issue：CQ（成本）/ PERF（延迟）
> 落地分批：3 个独立 PR（PR1 不动 schema 形状，cache 兼容；PR2 动 schema 形状，独立验证；PR3 延迟优化）
> 副本：仓库内 `design_doc/ARCH-7-analysis-llm-token-compression.md`

---

## 0. 背景与目标

`/office-hours` 一次会话中由用户提出：analysis 阶段请求大模型的 prompt 非常长，返回的内容也非常长，请分析如何精简优化。

测量口径以 `IngestOrchestrator.kt:1404` 与 `IngestOrchestrator.kt:1435` 的运行时日志为准（每次分析 call 都会打印 `systemPrompt=N 字符, userPrompt=N 字符, schema=N 字符` 与 `analysis 流式 JSON 返回 N 字符`）。

在当前的 `promptVersion=ingest_analysis_v1`（`PromptVersions.kt:4`）下，三类来源合起来把每次调用的输入侧撑大：

- **system prompt 里的设计元数据 / postmortem 注释**（`AiPromptTemplates.kt:119-122, 134-136, 160-180`）—— 约 1.2K 字符。这些段落是给维护者看的修复记录（"P1, fixed" / "Why entityType/conceptCategory are FREE-FORM"），对 LLM 工作没有信息增益。
- **`Current Wiki Index`**（`IngestOrchestrator.kt:2019-2032`，cap=`CURRENT_INDEX_PROMPT_CHARS=20_000`，`IngestOrchestrator.kt:2436`）—— 每个 call 塞 20K 字符，分块路径下每 chunk 重复一次，是分块路径输入侧的最大单点。
- **schema 里的死字段**（`IngestOrchestrator.kt:2548-2593`）—— `type`/`category` 是 deprecated alias，`source_refs`/`evidenceFragmentIds` 路径不传 fragment id 模型永远填错，`examples`/`limitations` schema 写了但 Orchestrator 不读。

Response 侧：中等源 3-8K 字符，长源 10-20K。Schema 上限 10 entities × 7 字段 + 8 concepts × 8 字段 + 10 relations + claims + gaps + pageRecommendations + archiveRecommendation，但 `buildStructuredAnalysisContext`（`IngestOrchestrator.kt:1288-1350`）只读 `name` / `entityType` / `conceptCategory` / `description` / `definition` 与 relations 的 `source` / `target` / `type` —— 其余字段是输出 token 的纯成本。

目标（按 PR 拆分）：

- **PR1**：输入侧 -30%，不动 schema 形状，cache 兼容。
- **PR2**：输出侧 -40-50%，动 schema 形状，独立验证消费方。
- **PR3**：分块路径 wall-clock ÷3，正确性靠 `mergeChunkAnalyses` 后去重兜住。

---

## 1. 当前状态：prompt 拼装流程

```
AnalysisStage.run
  └─ IngestOrchestrator.runAnalysisTask                  (line 575-700)
        ├─ 拉 source / parsed (内存中转 / DB 兜底, P1-B.4)
        ├─ if markdown.length > LONG_SOURCE_BUDGET_CHARS (30K, line 2469)
        │     → requestAiAnalysisLongSource              (line 1485)
        │         ├─ MarkdownSemanticChunker.split       (target ~16.5K/chunk)
        │         ├─ for chunk in chunks [串行]
        │         │     systemPrompt = buildChunkAnalysisSystemPrompt  (line 1665-1697)
        │         │       ├─ languageDirective
        │         │       ├─ "Analyze ONLY the current MAIN CHUNK..."
        │         │       ├─ Wiki Purpose
        │         │       └─ Current Wiki Index           (再次塞 20K)
        │         │     userPrompt  = buildChunkAnalysisUserPrompt
        │         │     streamJsonWithThrottledProgress(..., schemaHint=ANALYSIS_SCHEMA)
        │         │     extractChunkDigest(raw)  → 下一轮 globalDigest
        │         └─ mergeChunkAnalyses(...)              (line 1646, 按 name 去重)
        └─ else
              → requestAiAnalysis                         (line 1377-1442)
                  systemPrompt = AiPromptTemplates.analysisPrompt(
                      ..., currentIndex=buildCurrentIndex(kbId), ...)
                  userPrompt  = buildAnalysisUserMessage (line 2034, 整篇 markdown)
                  streamJsonWithThrottledProgress(..., schemaHint=ANALYSIS_SCHEMA)
```

`analysisPrompt` 模板本体（`AiPromptTemplates.kt:89-191`）拼出 4 段：
1. `languageDirective`（line 98）—— 600 字，必须留
2. 字段规则 + 示例（line 100-158）—— 3.5K 字，是真东西
3. **postmortem 注释**（line 119-122, 134-136, 160-180）—— 1.2K 字，待砍
4. `Wiki Purpose` + `Current Wiki Index`（line 189-190）—— 80 字 + 最多 20K 字

---

## 2. 目标态：3 个 PR 的边界

| PR | 动作集合 | 输入侧变化 | 输出侧变化 | schema 形状 | cache 影响 |
|----|---------|-----------|-----------|------------|-----------|
| **PR1** | 1+2+3（删 postmortem 注释 + 删 deprecated schema 字段 + 分块路径 system/index 去重） | system -25%，分块 -50% | -5%（LLM 不再发空 alias 行） | 微调（去掉 3 个 deprecated 字段） | 兼容：保留 `entityType` / `conceptCategory` / `evidence`，promptVersion 不升号 |
| **PR2** | 4+5（缩 counts + 删未用字段 + Index 改 5K cap） | 短路径不变，分块 -30% | -40-50% | 改：上限 1-5/1-5/3-5，删 `aliases` / `pageRecommendations` / `archiveRecommendation` | **不兼容**：必须升 `promptVersion=ingest_analysis_v2`，旧 cache 失效 |
| **PR3** | 6（分块路径并行化） | 不变 | 不变 | 不变 | 兼容 |

理由：PR1 不动 schema 形状、promptVersion 不变 → 已有的 `analysisHash` 缓存（`IngestOrchestrator.kt:693`）继续命中，不需要强制重导入。PR2 改 schema 形状必须独立验证消费方（`WikiPageCompiler` / `KnowledgeRepositoryImpl.rebuildGraphForBase` / `buildStructuredAnalysisContext` 是否真不读被删字段），所以隔离。PR3 跟正确性解耦，并发后靠 `mergeChunkAnalyses`（line 1646）的 case-insensitive name 去重兜住。

---

## 3. PR1 详细设计（输入侧，不动 schema 形状）

### 3.1 删 postmortem 注释

文件：`app/src/main/java/com/my/knowledge/data/ai/AiPromptTemplates.kt`

**删除**：
- `AiPromptTemplates.kt:120-123`（"Hard extraction rule (P1, fixed): 哪怕来源只有一段话..." 整段）
- `AiPromptTemplates.kt:135-137`（"Hard extraction rule (P1, fixed): 即使来源没有..." 整段）
- `AiPromptTemplates.kt:161-181`（"Why entityType / conceptCategory are FREE-FORM, not enums" + "Anti-empty-array guard (P1, fixed)" 两段）

**保留并压缩为一行**：
- 把 "至少 1 entity / 1 concept" 的硬规则压成单行英文：`- HARD: at least 1 entity AND at least 1 concept, even for short sources. Empty arrays break the downstream graph.`
- 放在 `entities` / `concepts` 字段规则的收尾，不再嵌 P1 修复史。

**保留**：
- 字段规则 + 示例（line 100-158）
- 5 条 Hard rules（line 182-187）
- `languageDirective` / `Wiki Purpose` / `Current Wiki Index`

预估：system prompt -25%（每 call ~1.2K 字符）。

### 3.2 删 schema 里的 deprecated 字段

文件：`app/src/main/java/com/my/knowledge/data/ingest/IngestOrchestrator.kt:2548-2593`

**删除 schema 中的字段**：
- entities.`type`（line 2557，"DEPRECATED alias"）
- concepts.`category`（line 2572，"DEPRECATED alias"）
- entities.`source_refs`（line 2562，模型填不对）
- concepts.`source_refs`（line 2580，同上）
- relations.`evidenceFragmentIds`（line 2584，同上）
- claims.`evidenceFragmentIds`（line 2585，同上）

**保留 fallback**（重要）：`parseAiAnalysisJson` 与 `buildStructuredAnalysisContext` 里 `entityType.ifBlank { type }`、`conceptCategory.ifBlank { category }` 的回退逻辑**不动**——防御老 LLM 输出 / 老 cache。

**promptVersion**：保持 `INGEST_ANALYSIS_V1`（`PromptVersions.kt:4`），不升号。`analysisHash`（`IngestOrchestrator.kt:693`）继续命中。

预估：schema block -30%（每 call ~450 字符），output -5%（LLM 不再发空 alias 行）。

### 3.3 分块路径 system + index 去重

文件：`app/src/main/java/com/my/knowledge/data/ingest/IngestOrchestrator.kt:1665-1697, 1558-1640`

**改动**：
- 当前 `buildChunkAnalysisSystemPrompt`（line 1665）在每 chunk 重新拼 system（含 Wiki Purpose + Current Wiki Index）
- 顺带清理：`buildChunkAnalysisSystemPrompt` line 1702 处硬编码的 `index.take(40_000)` 是死代码——上游 `buildCurrentIndex`（line 2031）已经按 `CURRENT_INDEX_PROMPT_CHARS=20_000` cap 过，PR1 把常量改成 5_000 后这行 40_000 也跟着失去意义，直接删
- 改为：chunk 1 用完整 system prompt；chunk 2..N 改用 ~8 行的短 prefix：
  ```
  Continue the same analysis. Maintain consistent entity / concept names with
  the prior digest and wiki index. Output JSON ONLY — same schema as chunk 1.
  
  ## Wiki Purpose
  <purpose 80 字>
  
  ## Current Wiki Index (compact, see digest for cross-chunk continuity)
  <index.take(5_000)>
  
  ## Prior chunks digest
  <globalDigest 来自 extractChunkDigest, line 1619>
  ```
- 把 `Current Wiki Index` 的 cap 从 `CURRENT_INDEX_PROMPT_CHARS=20_000` 降到 5_000（`IngestOrchestrator.kt:2436` 的常量分两档：`CURRENT_INDEX_PROMPT_CHARS_FIRST=5_000` / `CURRENT_INDEX_PROMPT_CHARS_REST=2_000`）

预估：分块路径输入 -45%（口径：3 段 markdown ~50K 源。原 3 × (5K system + 20K index + 16.5K user + 1.5K schema) = 3 × 43K = 129K。PR1 后：chunk 1 = 3.5K system + 5K index + 16.5K user + 1K schema = 26K；chunk 2/3 = 2K prefix + 2K index + 0.5K digest + 16.5K user + 1K schema = 22K。总 26K + 2 × 22K = 70K，**-46%**）。

### 3.4 PR1 验收

- `./gradlew testDebugUnitTest --tests 'com.my.knowledge.data.ai.*' --tests 'com.my.knowledge.data.ingest.*'`
- 回归：在 3 个不同长度档（< 5K, 10-20K, 30-50K markdown）的 fixture 上跑端到端 ingest，对比 entities/concepts/relations 数量与样本质量（不期望完全一致，期望分布相似）
- 运行时日志：观察 `systemPrompt=N` 与 `schema=N` 的下降幅度
- 真实设备跑 1 篇文章（已有 promptVersion=v1 的 cache），确认 cache 命中、analysis 行为不变

---

## 4. PR2 详细设计（输出侧，schema 形状变）

### 4.1 缩 counts + 删未用字段

文件：`app/src/main/java/com/my/knowledge/data/ai/AiPromptTemplates.kt`, `app/src/main/java/com/my/knowledge/data/ingest/IngestOrchestrator.kt:2548-2593`

**改 schema 上限**：
- entities: `1-10` → `1-5`
- concepts: `1-8` → `1-5`
- relations: `3-10` → `3-5`

**删 schema 字段**（审计后）：
- entities.`aliases`（line 2558）—— `buildStructuredAnalysisContext` 不读
- concepts.`examples`（line 2578）—— 同上
- concepts.`limitations`（line 2579）—— 同上
- 整个 `pageRecommendations` 数组（line 2587）—— Stage 2 用 entities/concepts 重排
- 整个 `archiveRecommendation` 对象（line 2588）—— 单 KB 场景下有默认值

**前提（PR2 开工前必做）**：
- audit 至少这 4 个文件： `WikiPageCompiler` / `KnowledgeRepositoryImpl.rebuildGraphForBase` / `buildStructuredAnalysisContext` / **`IngestJsonValidator`**（`IngestJsonValidator.kt:147, 174, 213, 245` 当前还读 `archiveRecommendation`，必须先确认它读到的字段是 fallback 还是主路径，否则删 schema 后校验逻辑会破）
- 若是 audit 发现某个被删字段真被消费方读，把它从删除列表移到「保留并标 deprecated」

**promptVersion**：升 `INGEST_ANALYSIS_V2`（`PromptVersions.kt:4`）。理由：cache 必须失效（`IngestOrchestrator.kt:693` 的 `analysisHash` 会因 schema 形状变而改变，但显式升号更安全，避免"哈希碰撞导致旧 LLM 输出被新 schema 误读"）。

**对用户的实际影响**：
- **FRESH 导入**（首次导入某文档）：`INGEST_ANALYSIS_V2` bump 是 no-op，没旧 cache。`analysisHash` 正常计算。
- **RE 导入**（同一文档重导入，cache 命中场景）：v1 cache 主动失效，强制重跑 LLM 调用。**这是 schema 形状变的代价**，用户会看到"重导入变慢一次"。

预估：output -40-50%（中等源从 3-8K → 1.5-4K 字符）。

### 4.2 Current Wiki Index 改 5K cap

`IngestOrchestrator.kt:2436` 的 `CURRENT_INDEX_PROMPT_CHARS=20_000` 改为 `5_000`。理由：Index 在 PR1 已经按 chunk 区分（PR1 把后续 chunk 限制到 2K），短路径 5K 也够用（150 页 × 33 字/页）。

风险：若 Index 真在帮去重命名（"Sarah" → "Sarah Smith"），砍 4 倍 cap 会回归。验证方法：回归样本里手动核对实体名是否一致；若有回归，把 cap 调到 10K。

### 4.3 PR2 验收

- `./gradlew testDebugUnitTest --tests 'com.my.knowledge.data.ai.*' --tests 'com.my.knowledge.data.ingest.*'`
- 跑 3 档 fixture（同 PR1），对比 entities/concepts/relations **数量分布**（新上限 1-5 / 1-5 / 3-5）
- 跑一次完整 ingest 流程，验证 Stage 2（`GenerationStage`）产出的 wiki 页与 PR1 行为一致
- 验证 `analysisHash` 不再命中旧 cache（这正是升 `INGEST_ANALYSIS_V2` 的目的）

---

## 5. PR3 详细设计（分块路径并行化）

### 5.1 现状

`IngestOrchestrator.kt:1558-1640`：分块路径串行执行，chunk N+1 等 chunk N 的 LLM 返回（`globalDigest = extractChunkDigest(raw)`，line 1619）。

### 5.2 改动

**改串行为并发**：用 `coroutineScope { chunks.map { async { ... } }.awaitAll() }` 把 N 段并发发出去。Concurrency cap = `IngestScheduler` 的分析 lane 上限（`IngestScheduler.kt:60` 声明 `parallelism: Int = 4`，`:66` `coerceIn(1, 4)`），默认 4-lane，自然限流。

**代价**：
- 跨 chunk 命名一致性下降：模型看不到前 chunk 的 `globalDigest`，可能"John Smith" 在 chunk 1 提一次、"John S." 在 chunk 2 又提一次
- 兜底：`mergeChunkAnalyses`（line 1646 起的函数体，实现在 `~line 1756+`）的 `case-insensitive name` 去重已经够；tags / claims / gaps 合并逻辑也对顺序无依赖
- **注意**：`extractChunkDigest`（line 1619 调用的版本）只取**最新**一段的 `summary`，不是拼接所有前序 chunk 的摘要。PR1 §3.3 中"Prior chunks digest"实际只携带最近一段的 summary —— 跨 chunk 命名一致性比想象弱，PR3 的代价比 §5.2 列的更高，需要在 §5.3 验收里加一道"跨 chunk 同名异指"的手工抽样。

**checkpoint 调整**：当前 `LongSourceCheckpoint`（`IngestOrchestrator.kt:1622-1635`）按 `chunk.index` 顺序写。并发下写顺序不确定 → 改为"接受乱序写但用 `completedThrough` 单调递增"：每次写前先读 store → 取 max → 写。注意两个并发 coroutine 调 `store.save(checkpointFile, ...)` 时**仍然会 race** —— 需要 compare-and-swap：写前先 `get`，对比 `completedThrough` 没被其它 writer 推高再 `save`。否则两个 chunk 同时完成时，先写者会被后写者覆盖、`completedThrough` 倒退。

预估：60K 源（3 段），4-lane concurrency cap，wall-clock 从 ~3× per-call 降到 ~1× per-call（按 4 lane 全部被占住计算）。

### 5.3 PR3 验收

- 跑 60K+ 字符长源 fixture，测 wall-clock
- 跑 3 个不同长度的 fixture，验证 `mergeChunkAnalyses` 合并后 entities/concepts 数量与串行版本**在去重后**一致
- 验证 checkpoint 兼容性：并发下产生的乱序 checkpoint 能在重启后正确恢复

---

## 6. 风险与回退

| 风险 | 触发条件 | 回退 |
|------|---------|------|
| PR1 删 postmortem 后空数组回归 | 短源（< 1 段话）entities/concepts 比例 < 50% | 把 1 行 HARD 规则改成 2 行，更明确 |
| PR1 分块去重后命名一致性下降 | chunk 2/3 出现与 chunk 1 同义不同名的实体 | 把 digest cap 从一行扩到 3-5 行 |
| PR2 删字段导致 Stage 2 wiki 页内容缺失 | audit 时漏读 | audit 阶段加一道 "grep `optString(\"aliases\")` 全工程" 防御 |
| PR2 promptVersion=v2 cache 失效触发重导入 | 用户重导入时无感 | 接受——schema 形状变了，旧输出语义上不可信 |
| PR3 并发下 LLM provider rate limit | 远端 429 | 已有限流（`PERF-11` 把 LLM 调用限到 2 并发），自然兜住 |
| PR3 checkpoint 乱序写导致重启丢段 | 并发下 IO 错乱 | `completedThrough` 改为单调递增，retry 跳过已写段 |

---

## 7. 验证策略（统一）

### 7.1 单元测试

- `com.my.knowledge.data.ai.AiPromptTemplatesTest` —— 测 `analysisPrompt` 长度 < 阈值、deprecated 字段不再出现
- `com.my.knowledge.data.ingest.ParseAiAnalysisJsonTest` —— 加 `parseAiAnalysisJson` 在 PR2 字段删除后的回归用例
- `com.my.knowledge.data.ingest.IngestCheckpointTest` —— PR3 乱序 checkpoint 恢复

### 7.2 端到端

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --tests 'com.my.knowledge.data.ai.*' --tests 'com.my.knowledge.data.ingest.*'
```

### 7.3 运行时指标

每次分析 call 在 `IngestOrchestrator.kt:1404, 1435` 的日志已经打印了 prompt / response 长度。回归基线（PR1 之前）：
- system prompt: ~5-25K 字符（含 20K index）
- user prompt: 整篇 markdown（短路径 ≤ 30K，chunk 路径 ~16.5K/段）
- schema: ~1.5K 字符
- response: 3-8K 字符（中等源）

PR1 后预期：system -25%，schema -30%，分块路径整体输入 -46%。
PR2 后预期：output -40-50%。
PR3 后预期：分块路径 wall-clock ÷3（4-lane cap）。

---

## 8. 关联

- 上下文 `memory/2026-06-04-ingest-remote-llm-timeout-optimization.md` —— 已经把分析 call 从 50K 截断降到 30K、改流式；本设计稿是它的延续
- 上下文 `memory/2026-06-04-ingest-background-timeout.md` —— wake lock / wifi lock 解决 socket abort，与本设计正交
- 上下文 commit `12fe9f2 fix(ingest): cache hit also requires matching analysis promptVersion (CQ-12 / ARCH-6)` —— PR1 必须保持 `INGEST_ANALYSIS_V1` 不变以保证 cache 命中；PR2 显式升 `INGEST_ANALYSIS_V2`
- 待办 `ARCH-3 / ARCH-4 / ARCH-5`（`design_doc/ARCH-3-4-5-architecture-evolution.md`）—— 长期架构演进，本设计稿属于短期成本/延迟优化

---

## 9. 决策记录

- **2026-06-05 开工**：`/office-hours` 会话中由用户提出 "analysis 阶段 prompt 与 response 都长，请分析如何精简优化"
- **2026-06-05 落定分批**：3 个 PR；PR1 不动 schema 形状 / promptVersion 不变以保 cache 兼容；PR2 升 `INGEST_ANALYSIS_V2` 主动失效旧 cache
- **未决项**：PR3 的 checkpoint 乱序写细节需要 PR1 合入后再细化
