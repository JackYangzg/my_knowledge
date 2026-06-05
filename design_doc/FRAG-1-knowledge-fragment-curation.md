# FRAG-1 — 知识碎片整理：从"识别"到"提炼"的全链路

> 状态：**DRAFT**（2026-06-05）
> 配套视觉草案:[`FRAG-1-wireframe.html`](./FRAG-1-wireframe.html)(列表页 + 4 状态详情页 + 按钮矩阵)
> 范围：`FragmentOrganizeScreen`（`ui/SubScreens.kt:411`）+ 新模块 `FragmentChainDetailScreen` + 新组件 `FragmentGapDetector` / `DistillationEngine` + 2 个新 Worker + 1 个 schema 增量
> 用户问题："碎片整理"是 `KnowledgeComponents.kt:39-91` 占位卡片，点了进 `FragmentOrganizeScreen` 永远是空列表。需要一个**完整闭环**：自动识别 → 图形化展示缺口 → 用户补全 → 提炼 → 归档 → 标星/分享
> 用户在 office-hours 中已经画出需求路径，本文是落地拆分

---

## 0. 一句话结论

**5 块已有 infra 已经够拼 80% 的"碎片整理"流水线** —— 缺的只是把它们**串成一条**：

| 已有 | 缺什么 |
|---|---|
| `KnowledgeFragmentEntity`（`MarkdownFragmenter.kt:31-72` 在 ingest 时分片）| 没有"fragment chain"概念，缺聚合 |
| `wiki_entity` / `wiki_concept` 实体图谱（MERGE-1 收尾）| 缺"孤立节点"查询（度数<2） |
| `ThreadEvolutionRunner.detectGaps`（8 条规则）+ `gapsJson` | 缺 gap 列表的 UI 暴露 |
| `STATUS_RECOMMEND_READY` / `STATUS_NEED_REVIEW` / `STATUS_ARCHIVED` 状态码 | 缺"可提炼"的中间态（建议新加 `STATUS_DISTILL_READY`） |
| `FragmentOrganizeScreen`（`ui/SubScreens.kt:411`）| 永远空，缺真实数据源 + 4-tab 语义对齐 + 详情页 |

**推荐路径**（按 P0 → P1 → P2 分 3 个 PR 群，共 7 个 PR）：

| PR 群 | 改动 | 体感 |
|---|---|---|
| **P0 (FRAG-1.1~1.3)** | Schema 增量 + FragmentGapDetector + FragmentOrganizeScreen 接真实数据 + 4-tab 过滤器 | 打开碎片整理不再空，有内容可看 |
| **P1 (FRAG-1.4~1.5)** | 详情页"目标+缺口卡片" + 自然语言重分析 + re-import 自动 match | 用户能针对性补全 |
| **P2 (FRAG-1.6~1.7)** | 提炼 + 归档（FRAG-1.6）/ 标星 + 分享（FRAG-1.7） | 完整闭环 + 第三方分享 |

---

## 1. 现状盘点（基于实际代码,2026-06-05）

### 1.1 已有 UI 占位

`KnowledgeComponents.kt:39-91` —— `KnowledgeDigestSection` 里 `DigestCard(iconText = "▦", title = "碎片整理", desc = "18 条零散记录待归纳", onClick = onOpenFragments)`，**desc 文本是硬编码**。

`SubScreens.kt:411-499` —— `FragmentOrganizeScreen`：
- filter chips `["全部", "待归类", "可提炼", "可归档"]`（**注意：和用户原话"全部、待完善、可提炼、归档"有 2 处不一致**：`待归类` 应改 `待完善`，`可归档` 应改 `归档`）
- 顶部 3 个 `StatCard(fragments.size, "待整理")` / `(0, "已推荐")` / `(0, "可归档")` —— **全 0**
- 数据源 `KnowledgeManager.fragments`（`KnowledgeManager.kt:60`）—— `mutableStateListOf<KnowledgeFragmentData>()`，**永为空**

`Models.kt:33-39` —— `FragmentItem(title, type, summary, action, confidence)` 是 UI 占位 model，没有"chain"概念。

### 1.2 已有 schema / DAO

`KnowledgeFragmentEntity`（`data/db/entity/KnowledgeAssetEntities.kt:11-39`）：
- `id / itemId / knowledgeBaseId / content / summary / tagsJson / sourceRef / sourceManifestId / startOffset / endOffset / createdAt / sourceId / parsedContentId / knowledgeItemId / orderIndex / heading / tokenCount / embeddingId`
- **缺** `chainId` 字段（无法跨 fragment 聚合）
- **缺** `gapType` 字段（无法标识"该 fragment 是某 gap 的关键证据"）

`KnowledgeFragmentDao`（`data/db/dao/KnowledgeFragmentDao.kt`）：
- `observeByItem(itemId)` / `getRecentByBase(kbId, limit)` —— 已有，够用

`KnowledgeItemEntity`（`data/db/entity/KnowledgeItemEntity.kt:18-58`）：
- 状态码：`STATUS_DRAFT / UNFILED / PROCESSING / PROCESSED / RECOMMEND_READY / ARCHIVED / NEED_REVIEW / FAILED / DELETED`
- **缺** `STATUS_DISTILL_READY`（"可提炼"专用态，避免和 `RECOMMEND_READY` 语义重叠）
- **缺** `starredAt: Long?`（标星/推荐）

### 1.3 已有演化 / 缺口逻辑

`ThreadEvolutionRunner`（`THREAD-1` 已盘点）：
- `detectGaps(items)` 8 条规则（`ThreadEvolutionRunner.kt:348-376`,详见 §1.6）→ `gapsJson`
- `generateSuggestions(gaps, items)` → `nextSuggestionsJson`（gap → 建议 1:1 映射）
- **当前 UI 端不消费**这两个字段 —— `InspirationScreen` 只展示 `description / coreQuestion / mainlines / relations`

### 1.4 已有实体图谱查询能力

- `wiki_entity` / `wiki_concept` 页存在（`IngestOrchestrator.kt:812-854`）
- frontmatter `related: [[A]] [[B]]` 是数组，`WikiPageCompiler.merge` 已是数组 union
- **缺** DAO 端的"按度数查孤立 entity"查询
  - 需要 `SELECT t.id, t.title, COUNT(r.id) as degree FROM knowledge_item t LEFT JOIN ...` 这种 query
  - 当前 DAO 都是按 `kbId / sourceType / title` 查，**没有按 graph 度数查**

### 1.5 已有 LLM / Worker 基建

- `AiGateway`（`data/ai/AiGateway.kt`）—— 已支持 reasoning.effort（ARCH-8）
- `LlmInspirationThreadWorker`（THREAD-1 复用）—— 单独 worker，失败 fallback
- `ArchiveRecommendWorker`（`worker/ArchiveRecommendWorker.kt`）—— 改 status 到 ARCHIVED
- WorkManager 调度 + 唯一性约束（REPLACE） + retry 都已在用

### 1.6 已有 `gapsJson` 存储（schema 差异要点）

`ThreadEvolutionRunner.detectGaps`（`ThreadEvolutionRunner.kt:348-376`）返回 `List<String>`（**8 条纯中文短句**），存进 `KnowledgeThreadEntities.kt:14 gapsJson: String`（`["str","str",...]` JSON 数组）。

**8 条规则原文**（按 file line 顺序）：

| # | 触发条件 | 字符串原文 | 隐含 priority |
|---|---------|-----------|---------------|
| 1 | KB 空 | "知识库尚无已整理的知识条目" | HIGH |
| 2 | 无 wiki 页 | "还没有任何 wiki 页面，需要先完成知识加工" | HIGH |
| 3 | 无 synthesis 页 | "缺少 index / overview / log 合成页，无法形成主线" | **HIGH** |
| 4 | 主线为空 | "未能识别到主线（标签聚类为空），建议补充更明确的标签" | MEDIUM |
| 5 | 无关系 | "知识之间没有形成显式引用或同主题关联" | **HIGH** |
| 6 | 缺标签 | "超过半数知识缺少标签" | MEDIUM |
| 7 | 缺摘要 | "部分知识缺少摘要，建议补充" | LOW |
| 8 | 低置信度 | "存在低置信度知识，需要人工复核" | **HIGH** |

**问题**：FRAG-1 的 `KnowledgeFragmentGapEntity` 设计是**结构化表**（`gapType / priority / description / suggestion`），但 `gapsJson` 是**字符串列表**。两者需要**解析映射**：
- 字符串 → `GapType` 枚举（substring match，例如 contains("合成页") → MISSING_SYNTHESIS）
- priority 隐式派生（上表 4 列）
- description 直接用 detectGaps 原文
- suggestion 从 `ThreadEvolutionRunner.kt:378 generateSuggestions` 1:1 取

**v1 不破坏老 `gapsJson` 字段**：FRAG-1.1 migration 时一次性 backfill：扫 `knowledge_thread` 表 → 解析 `gapsJson` → 写 `knowledge_fragment_gap` + 写 `knowledge_fragment_chain`。老 `gapsJson` 字段保留（不再写入），UI 端只读结构化表。

---

## 2. 问题陈述（用户原话还原）

> 现在碎片整理功能是未实现的，该功能本质上是期望**自动识别**导入知识中存在的问题和碎片化信息，尤其是构建实体图谱后**关联比较少的知识**，在这个模块中能够给用户看到，并且告诉用户碎片化知识**缺了什么**，最好是**一个图形化方式**，告诉用户基于这些碎片知识要得到**什么结果或者目标**，但是还缺相关证据或者数据等内容，需要用户去补充，如果用户重新导入了相关信息，**补全了**，则该知识链条就可以告诉用户，以及**可以归档**了，用户同意后归档，如果用户觉得还缺什么，则用户通过**自然语言告诉应用，重新分析，补充潜在的缺口**，并等待后续知识导入。对于**逻辑链条完整**的知识链条，则变成**可提炼**的知识链条，用户处理后，则利用大模型进行**总结提炼**形成严谨的文字化表述，对于已归档的知识链条，用户可以**标星**，成为**推荐**的知识链条，并且可以**转换成图片**分享到第三方应用。里面的管理为 **全部、待完善、可提炼、归档**。

**4 个用户级操作闭环**：

```
[识别]                  [补全]                [提炼]              [分享]
待完善 ──自然语言──> 重新分析 ──自动──> 可提炼 ──LLM──> 归档 ──标星──> 推荐
                                  ↗                                          ↘
                                  用户重新导入相关源                            图片分享
```

---

## 3. Premises（设计前提，认可后再继续）

| # | 前提 | 理由 |
|---|---|---|
| **P1** | **碎片定义 = `孤立 entity/concept` ∪ `STATUS_NEED_REVIEW` ∪ `对应 thread gapsJson 非空`** | 用户原话"构建实体图谱后关联比较少的知识"+"碎片化信息"。三者并集覆盖"实体孤岛"、"加工失败"、"脉络缺口"3 类问题 |
| **P2** | **可提炼判定 = `gapsJson 为空` ∧ `chain 内 entity 平均度数 ≥ 2` ∧ `至少 1 个 wiki_index/overview 页`** | 三个条件 AND：脉络完整（无 gap）+ 实体连接够（≥2 度）+ 有汇总页（用户能"看到"过全貌） |
| **P3** | **新加状态码 `STATUS_DISTILL_READY`** | `RECOMMEND_READY` 已经表示"待归档"，语义已满。新加"可提炼"专用态避免重叠 |
| **P4** | **KnowledgeItemEntity 加 `starredAt: Long?` + `KnowledgeFragmentEntity 加 chainId`** | 不破坏 schema（NOT NULL DEFAULT NULL），向后兼容老库（migration 加列 + 默认值） |
| **P5** | **目标卡内容来源 = `KnowledgeThreadEntity.description` + `coreQuestion` + `confidence` + entity/source 计数** | 已有 thread 字段，0 新加字段；description 是 thread 自动维护的"主题概述" |
| **P6** | **缺口卡内容来源 = `ThreadEvolutionRunner.detectGaps` 的 8 条规则** × chain 范围 | 8 条规则已经够覆盖"缺对比/缺场景/缺数据/缺应用/缺关系/缺总结/缺引用/缺时间线"，不新加 LLM 规则 |
| **P7** | **自然语言重分析 = "文本 + 当前 gap 列表" 一起喂给 LLM，输出新 gap 列表** | 不重启 detectGaps（rule-based 不需要重跑），只让 LLM 评判"用户说的新信息能不能 cover 现有 gap" |
| **P8** | **提炼产物 = 1 篇新 `wiki_synthesis` 页（不是 `wiki_source`，也不合并到现有 wiki 页）** | 现有 `wiki_source` 已用于"来源摘要页"（`WikiPageCompiler.kt:52` 用作 ingest 后的源页），新加 `sourceType = "wiki_synthesis"` 专用于"知识链综合"页（v1 收尾时需在 `WikiPageCompiler` 加白名单或绕过编译路径） |
| **P9** | **图形化方式 = 目标+缺口卡片（v1）** | 用户已选 D1=A；v2 再升级到真实 entity 图谱 |
| **P10** | **图片分享 = Compose `View.drawToBitmap` → 缓存到 cacheDir → `Intent.ACTION_SEND` 走系统分享** | 不引入新依赖，标准 Android 路径 |
| **P11** | **chain ↔ thread 1:1 映射（v1 简化）：`chainId = threadId`** | 1 KB 通常 1 thread（`ThreadEvolutionRunner.kt:75` 限定"只读 wiki_* + inspiration + text"），v1 不做"多 thread 合成 1 chain"或"1 thread 拆多 chain"。chain 直接复用 thread 主键 |
| **P12** | **自然语言重分析 = user-asserted,非 LLM-verified** | 用户说"我补了 X"时 LLM 只能判断"用户主张",无法验证实际知识是否真的覆盖 gap。`KnowledgeFragmentGapEntity.resolvedByUserText: String?` 记录原话;UI 端标"用户声称已补,尚未经新 ingest 验证" badge |
| **P13** | **re-import 自动 resolve = best-effort，不替代人工确认** | ingest 完成后走 `NewItemGapMatchWorker`（LLM 判定"新 item 是否能 cover 现有 gap"），命中则 `resolvedByItemId`；不命中不动。新 chain 的 gap 仍由 `ThreadEvolutionRunner.detectGaps` 兜底 |

> **如果上面 13 条有不同意的地方，先 push back 再继续**。

---

## 4. 3 个候选路径（Approach A/B/C）

### Approach A — 卡片驱动 v1（推荐）

**核心思路**：复用一切已有，把"碎片整理"当成一个**新的 UI 视图层 + 1 个 LLM 提炼 endpoint**，不动核心 schema（仅增量列）。

**改动量**：7 个 PR（FRAG-1.1 ~ FRAG-1.7），每个 PR 单独可发布。

**Pros**：
- 复用 100% 的 ingest / thread / entity graph 基建
- 7 个 PR 全部独立可回退
- 用户能"看到东西"的体感在 FRAG-1.2 就达成（不需要等提炼功能）
- 提炼 / 标星 / 分享都是 v2 才上，先验证"识别 + 补全"主路径

**Cons**：
- v1 看不到 entity 之间的关系（全靠文字描述"5 entity"）
- 提炼时新加的 `wiki_source` 页可能和现有 page 结构有重叠（"这页和 entity 页有什么不同"？）

**Reuses**：
- `ThreadEvolutionRunner.detectGaps`（THREAD-1）
- `KnowledgeFragmentEntity` schema（仅加 `chainId` 列）
- `KnowledgeItemEntity` 状态码体系（仅加 `STATUS_DISTILL_READY` + `starredAt`）
- `FragmentOrganizeScreen` UI 骨架（仅改 filter chips 文案 + 数据源）
- `ArchiveRecommendWorker`（归档流程照抄）
- `LlmInspirationThreadWorker`（提炼 worker 仿照）
- `AiGateway`（ARCH-8 已有 reasoning.effort 注入）

### Approach B — 图谱驱动（一次性到位）

**核心思路**：把 entity/concept 节点 + 关系边 + 缺口节点用 force-directed layout 画成一张图，用户能直接看到"哪些 entity 关联了 / 哪些没关联"。

**改动量**：1 个大 PR（FRAG-1）or 拆 2-3 个子 PR，但整体 ~4-6 周。

**Pros**：
- 视觉上最"图形化"，符合用户"理想"诉求
- 能直接看到 entity 网络结构
- 缺口节点用红色虚线占位，视觉冲击强

**Cons**：
- Force-directed layout 在 Android 上要写自定义 Canvas（没有现成库），pinch/zoom/pan 交互 + 性能都是坑
- 现有 entity/concept 页 schema 没支持"快速查度数"（需要加索引 + DAO）
- v1 阶段就上 6 周工作量，阻塞用户"先验证生命周期"的诉求
- 大图在小屏（手机）上信息密度低，可能需要"双视图"（列表+图）

**Reuses**：和 A 一样，外加一个**新加的 graph view 组件**。

### Approach C — 列表+顶部横条

**核心思路**：详情页是 entity 列表（按度数排序），顶部一个 `完成度进度条 0-100%`，最简单。

**改动量**：3-4 个 PR，1 周能出 v1。

**Pros**：
- 极简
- 复用 entity 列表 UI（`KnowledgeLogScreen` 已有的 row 组件）

**Cons**：
- 没有"目标卡"，用户看不到"chain 想证明什么"
- 没有"缺口卡"，gap 列表纯文本埋在 row 里
- 视觉效果弱，可能让用户觉得"就这？"

**Reuses**：和 A 一样。

### Approach 对比矩阵

| 维度 | A（卡片）| B（图谱）| C（列表+横条）|
|---|---|---|---|
| 实现周期 | 3-4 周 | 6-8 周 | 1-2 周 |
| 复用已有 | 100% | 95% | 100% |
| 满足"图形化"诉求 | ⭐（卡片算图形化）| ⭐⭐⭐（真图）| （横条）|
| 满足"目标展示" | ⭐⭐⭐（专门目标卡）| ⭐⭐⭐（图根节点）| （隐藏在 description）|
| 满足"缺口展示" | ⭐⭐⭐（缺口卡）| ⭐⭐⭐（红虚线节点）| （隐藏在进度条）|
| 可回退 | ⭐⭐⭐（7 PR 独立）| （1 大 PR）| ⭐⭐（3-4 PR）|
| 性能 | ⭐⭐⭐（卡片）| （force layout）| ⭐⭐⭐（列表）|
| v2 升级路径 | 加图谱视图 | — | 加图谱视图 |

**RECOMMENDATION**: **Approach A**（卡片驱动 v1）—— 用户在 D1 已经选 A,7 PR 全独立可发布,FRAG-1.2 就有"看到内容"的体感,先验证主路径再上视觉强化。

---

## 5. Approach A 详细设计（7 个 PR 拆分）

### FRAG-1.1 — Schema 增量

**改动**（P3/P8/P11/P12/P13 全部对齐）：

- `KnowledgeItemEntity` 加字段 + 新状态常量：
  ```kotlin
  val starredAt: Long? = null,    // 标星时间,null = 未标星（用于 distill 产物 wiki_synthesis）
  ```
  + 新增 `STATUS_DISTILL_READY = "DISTILL_READY"`（写入 distill 产物,避免和 `RECOMMEND_READY` 语义重叠）

- `KnowledgeFragmentEntity` 加字段：
  ```kotlin
  val chainId: String? = null,    // 关联到 fragment chain;v1 简化:chainId == threadId
  ```

- 新表 `knowledge_fragment_chain`（P3 单一 status 源,item 上不再重复状态;P11 chainId=threadId 1:1）：
  ```kotlin
  @Entity(
      tableName = "knowledge_fragment_chain",
      indices = [
          Index(value = ["knowledgeBaseId", "status"]),
          Index(value = ["threadId"], unique = true),  // 1:1 约束
      ],
  )
  data class KnowledgeFragmentChainEntity(
      @PrimaryKey val id: String,             // v1: == threadId
      val knowledgeBaseId: String,
      val threadId: String,                    // P11 非空,与 thread 1:1
      val title: String,
      val goalSummary: String,                 // 缓存 thread.description 快照
      val confidence: Float,
      val entityCount: Int,
      val sourceCount: Int,
      val gapCount: Int,
      val status: String,                      // = LifecycleStatus.name(NEED_REVIEW / DISTILL_READY / RECOMMEND_READY / ARCHIVED)
      val distilledItemId: String? = null,     // 关联到 distill 产物 wiki_synthesis item
      val createdAt: Long,
      val updatedAt: Long,
  )
  ```

- 新表 `knowledge_fragment_gap`（8 GapType 与 detectGaps 1:1 对齐,见 §5.2）：
  ```kotlin
  @Entity(
      tableName = "knowledge_fragment_gap",
      indices = [
          Index(value = ["chainId", "resolved"]),
          Index(value = ["gapType"]),
      ],
  )
  data class KnowledgeFragmentGapEntity(
      @PrimaryKey val id: String,
      val chainId: String,                     // 关联到 fragment chain
      val gapType: String,                     // = GapType.name(8 种,见 §5.2)
      val priority: String,                    // HIGH / MEDIUM / LOW
      val description: String,                 // 来自 detectGaps 原文
      val suggestion: String,                  // 来自 generateSuggestions 1:1
      val resolved: Boolean = false,
      val resolvedByItemId: String? = null,    // P13:re-import 自动 match 命中时记录
      val resolvedByUserText: String? = null,  // P12:自然语言 user-asserted 时记录原话
      val resolvedAt: Long? = null,
      val createdAt: Long,
  )
  ```

- `AppDatabase` version → 12（v11 → v12）,**migration 步骤**：
  1. `ALTER TABLE knowledge_item ADD COLUMN starredAt INTEGER DEFAULT NULL`
  2. `ALTER TABLE knowledge_fragment ADD COLUMN chainId TEXT DEFAULT NULL`
  3. `CREATE TABLE knowledge_fragment_chain (...)` + 2 个 indices
  4. `CREATE TABLE knowledge_fragment_gap (...)` + 2 个 indices
  5. **backfill**（解决 gapsJson 解析需求,详见 §1.6 末尾）：扫 `knowledge_thread` 表 →
     - 对每个 thread 写一条 `knowledge_fragment_chain`（`id = thread.id`,`status` 由 `gapsJson` 推导:非空 → `NEED_REVIEW`,空 → `DISTILL_READY`）
     - 解析 `gapsJson` 字符串列表,substring match → `GapType` 枚举（见 §5.2 mapping 表）,写 `knowledge_fragment_gap`
     - 老 `gapsJson` 字段保留,UI 端只读结构化表

**验收**：
- `AppDatabaseMigrationTest` 加 v11 → v12 回归（必须能跑通 + 老 `gapsJson` 数据不丢）
- 单测:DAO `getChainsByKb(kbId, status)` / `getGapsByChain(chainId, resolved)` 正确返回
- backfill 单测:mock 1 个 thread + 3 条 gap 字符串 → 断言 1 个 chain + 3 个 gap 行写入,`gapType` 枚举映射正确
- 老 `gapsJson` 字段可读性保留（确保降级路径不破）

### FRAG-1.2 — FragmentGapDetector（核心算法）

**新文件** `domain/fragment/FragmentGapDetector.kt`：

```kotlin
class FragmentGapDetector(
    private val db: AppDatabase,
    private val aiGateway: AiGateway,
) {
    data class Chain(
        val id: String,
        val title: String,
        val goal: String,            // = thread.description
        val coreQuestion: String?,
        val confidence: Float,
        val entityCount: Int,
        val sourceCount: Int,
        val gaps: List<Gap>,
        val status: LifecycleStatus,
        val starredAt: Long?,
        val threadId: String,
    )

    data class Gap(
        val id: String,
        val type: Type,
        val priority: Priority,
        val description: String,
        val suggestion: String,
        val resolved: Boolean,
    ) {
        // 8 GapType 与 detectGaps 8 条规则 1:1 对齐（ThreadEvolutionRunner.kt:348-376,详见 §1.6）
        enum class Type {
            KB_EMPTY,           // 规则 1: "知识库尚无已整理的知识条目"
            NO_WIKI_PAGES,      // 规则 2: "还没有任何 wiki 页面"
            MISSING_SYNTHESIS,  // 规则 3: "缺少 index / overview / log 合成页"
            NO_MAINLINE,        // 规则 4: "未能识别到主线（标签聚类为空）"
            NO_RELATIONS,       // 规则 5: "知识之间没有形成显式引用或同主题关联"
            MISSING_TAGS,       // 规则 6: "超过半数知识缺少标签"
            MISSING_SUMMARY,    // 规则 7: "部分知识缺少摘要"
            LOW_CONFIDENCE,     // 规则 8: "存在低置信度知识"
        }
        enum class Priority { HIGH, MEDIUM, LOW }
    }

    enum class LifecycleStatus {
        NEED_REVIEW,     // 待完善：有 gap
        DISTILL_READY,   // 可提炼：gap 全空 + 度数阈值 + 有 overview
        RECOMMEND_READY, // 提炼完成后等待归档
        ARCHIVED,        // 归档
    }

    /** 主入口：给定 kbId 返回所有 chain */
    suspend fun detectByKb(kbId: String): List<Chain> { ... }

    /** 子查询：chain 内 entity 平均度数 */
    private suspend fun averageEntityDegree(threadId: String): Double { ... }

    /** 子查询：chain 范围是否含 wiki_index/overview */
    private suspend fun hasSynthesisPage(threadId: String): Boolean { ... }

    /** 子查询：thread 的 gapsJson 解析为 Gap 列表 */
    private suspend fun extractGapsFromThread(threadId: String): List<Gap> { ... }
}
```

**触发时机**：
- `ingest` 完成 → 现有 `scheduleThreadUpdate(kbId)` 已跑，**追加** `scheduleFragmentDetection(kbId)`（fire-and-forget）
- 用户主动"重新分析"按钮（FRAG-1.4 引入）→ 同步走 `detectByKb(kbId)`

**验收**：
- 单测：mock KB 含 5 entity（3 个孤立 + 2 个互联），断言 chain 列表正确
- 单测：gap 优先级排序（HIGH 在前）
- 真机：导入 1 个 KB 含 5 条笔记 → 打开碎片整理 tab → 看到 N 个 chain，3 个标"待完善"+ 1 个标"可提炼"

### FRAG-1.3 — FragmentOrganizeScreen 接真实数据 + 4-tab 重命名

**改动**：
- `FragmentOrganizeScreen` 改成 `viewModel.observeChains(kbId)` 流式拉数据（不再读 `KnowledgeManager.fragments`）
- filter chips 文案对齐用户原话：`["全部", "待归类"→"待完善", "可提炼", "可归档"→"归档"]`
- 4 个 tab 对应 4 个 SQL 查询：
  - **全部** = `SELECT * FROM knowledge_fragment_chain WHERE kbId=?`
  - **待完善** = `WHERE status='NEED_REVIEW'`
  - **可提炼** = `WHERE status='DISTILL_READY'`
  - **归档** = `WHERE status IN ('RECOMMEND_READY', 'ARCHIVED')`
- 顶部 3 个 `StatCard` 改为真实统计（待整理/已推荐/可归档）
- `KnowledgeComponents.kt:85` 的硬编码 `"18 条零散记录待归纳"` 改为 `kbId` 下的 chain 总数
- 加 `onOpenChainDetail(chainId)` 导航回调 → 跳 `FragmentChainDetailScreen`（FRAG-1.4）

**验收**：
- 真机：导入 1 个 KB 后打开碎片整理，看到 N 个 chain
- 4 个 tab 切换正确，filter 命中行数对得上 StatCard
- 状态码 ↔ 4 tab 的映射在 spec 里有明确表格

### FRAG-1.4 — FragmentChainDetailScreen + 目标/缺口卡片

**新文件** `ui/FragmentChainDetailScreen.kt`：

```kotlin
@Composable
fun FragmentChainDetailScreen(
    chainId: String,
    onBack: () -> Unit,
    onOpenEntity: (entityId: String) -> Unit,
    onOpenSource: (sourceId: String) -> Unit,
    onReanalyze: suspend (text: String) -> Unit,
    onDistill: suspend () -> Unit,
    onArchive: suspend () -> Unit,
    onStar: suspend () -> Unit,
    onShareImage: suspend () -> Unit,
)
```

**布局**（v1 卡片驱动）：
```
┌─────────────────────────────────────┐
│ ← 碎片整理                          │
├─────────────────────────────────────┤
│ 📌 目标                             │  ← 目标卡
│ 证明『XXX 在 YYY 场景下有效』         │
│ confidence: 0.72 · 5 entity · 3 源   │
│ coreQuestion: ...                   │
├─────────────────────────────────────┤
│ 完整度 [████████░░] 80% · 4/5 entity│  ← 横条（轻量可视化）
├─────────────────────────────────────┤
│ ⚠ 缺口 (3)                          │  ← 缺口标题
│ ┌─────────────────────────────────┐ │
│ │ ⚠ 缺对比数据 [高]               │ │  ← 缺口卡（QuietCell 风格）
│ │   建议补充：A/B 测试结果         │ │
│ │   自然语言: [告诉我...]           │ │
│ └─────────────────────────────────┘ │
│ ┌─────────────────────────────────┐ │
│ │ ⚠ 缺应用场景 [中]               │ │
│ │   建议补充：真实案例              │ │
│ │   自然语言: [告诉我...]           │ │
│ └─────────────────────────────────┘ │
├─────────────────────────────────────┤
│ [📥 重新分析] [⭐ 标星] [📤 分享图片] │  ← 底部操作栏（按 status 显示）
│ [✨ 开始提炼] 或 [📦 归档]          │
└─────────────────────────────────────┘
```

**技术细节**：
- "自然语言"输入 = 一个 inline `TextField`，回车后调 `onReanalyze(text)` → FRAG-1.5
- "开始提炼"按钮 = 只在 `DISTILL_READY` 状态显示
- "归档"按钮 = 只在 `RECOMMEND_READY` 状态显示
- "标星"按钮 = 在 `ARCHIVED` 状态显示（已标星显示"取消标星"）
- "分享图片"按钮 = 在 `ARCHIVED` 状态显示

**验收**：
- 4 种状态（LifecycleStatus）下，详情页的按钮组合正确
- 单测：`FragmentChainDetailScreen` 的状态 → 按钮可见性映射

### FRAG-1.5 — 自然语言重分析 + re-import 自动 match（worker）

**新文件** `worker/NaturalLanguageGapReanalysisWorker.kt`：

```kotlin
class NaturalLanguageGapReanalysisWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val chainId = inputData.getString("chainId") ?: return Result.failure()
        val userText = inputData.getString("userText") ?: ""
        val newGaps = gapDetector.reanalyzeWithText(chainId, userText)
        // 写 knowledge_fragment_gap 表（resolvedByItemId=null,resolvedAt=null）
        // 触发条件：用户说"这条我补了 X 方面的证据" → 旧 gap 标 resolved=true
        //            用户说"我还差 Y 方面的证据" → 旧 gap 保留 + 加新 gap
        db.fragmentGapDao().updateChain(chainId, newGaps)
        return Result.success()
    }
}
```

**FragmentGapDetector 新增**：
```kotlin
suspend fun reanalyzeWithText(chainId: String, userText: String): List<Gap> {
    val existingGaps = extractGapsFromThread(chainId)
    val prompt = buildReanalyzePrompt(existingGaps, userText)
    val llmOutput = aiGateway.complete(prompt, reasoningEffort = MEDIUM)
    return parseGapUpdates(llmOutput, existingGaps)  // 输出可能是 "resolve X" / "add Y" / "no change"
}
```

**Prompt 模板**（新加到 `AiPromptTemplates`）：
```
你是知识图谱分析师。基于以下已有缺口列表和用户的自然语言补充，
判断每个 gap 是"已解决"、"仍存在"还是"新发现"，并给出新发现 gap 的描述和建议。

[现有 gaps]
1. {gap1.description} 建议: {gap1.suggestion}
2. {gap2.description} 建议: {gap2.suggestion}

[用户自然语言]
{userText}

[输出 JSON]
{
  "updates": [
    {"gapId": "1", "action": "resolved" | "still_open"},
    {"gapId": "2", "action": "resolved" | "still_open"},
    {"newGap": {"type": "...", "priority": "HIGH|MEDIUM|LOW", "description": "...", "suggestion": "..."}}
  ]
}
```

**验收**：
- 单测：mock LLM 输出 → 断言 Gap 列表更新正确
- 真机：用户输入"我已经看了 5 个案例" → 原本"缺应用场景"gap 标 `resolved=true, resolvedByUserText=用户原话`
- 重要约束（P12）：详情页 UI 端在 `resolvedByUserText != null` 时显式标 ⚠️"用户声称已补,尚未经新 ingest 验证" badge —— LLM 不能验证用户主张

**re-import 自动 match（NewItemGapMatchWorker）** —— P13 best-effort,与自然语言互为补全入口：

**新文件** `worker/NewItemGapMatchWorker.kt`：
```kotlin
class NewItemGapMatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val newItemId = inputData.getString("itemId") ?: return Result.failure()
        val item = db.knowledgeItemDao().getById(newItemId) ?: return Result.failure()
        val kbId = item.knowledgeBaseId

        // 找 KB 下所有未 resolve 的 chain
        val openChains = db.fragmentChainDao().getOpenByKb(kbId)  // status=NEED_REVIEW 的 chain
        if (openChains.isEmpty()) return Result.success()  // 没有 gap 要处理

        for (chain in openChains) {
            val openGaps = db.fragmentGapDao().getByChain(chain.id, resolved = false)
            if (openGaps.isEmpty()) continue
            val prompt = buildMatchPrompt(openGaps, item)
            val matchResult = aiGateway.complete(prompt, reasoningEffort = LOW).let { parseMatches(it) }
            // 命中:gap.resolved=true + resolvedByItemId=newItemId + resolvedAt=now
            for (matchedGapId in matchResult.matchedGapIds) {
                db.fragmentGapDao().markResolved(matchedGapId, newItemId, userText = null)
            }
        }
        // 触发条件:
        // - ingest 完成(RELIAB-1 chain) → 追加 scheduleNewItemMatch(itemId)
        // - 用户主动"重新分析"按钮
        return Result.success()
    }
}
```

**FragmentGapDetector 新增** `buildMatchPrompt`：
```
你是知识图谱分析师。判断"新导入条目"的内容能否覆盖以下已有缺口（仅返回能覆盖的 gapId）：

[新条目标题/摘要/标签]
${item.title} / ${item.summary} / ${item.tags}

[未 resolve 的 gaps]
1. ${gap1.description} (id=1)
2. ${gap2.description} (id=2)

[输出 JSON]
{"matchedGapIds": [1]}  // 只填能 100% 覆盖的;不确定就空
```

**验收**：
- 单测:mock LLM 输出 `{"matchedGapIds":[2]}` → 断言 gap #2 标 `resolved=true, resolvedByItemId=newItemId`
- 集成:fixture KB 1 个 NEED_REVIEW chain + 3 gaps → 导入 1 条新 item 内容覆盖 1 gap → 触发 `scheduleNewItemMatch` → 1 个 gap 标 resolved,chain status 保持 NEED_REVIEW（如还有未 resolve）
- 边界:LLM 输出非 JSON → fallback "no match"（不动 gap,避免误判）

### FRAG-1.6 — 提炼 / 归档

**新文件** `domain/fragment/DistillationEngine.kt`：

```kotlin
class DistillationEngine(
    private val db: AppDatabase,
    private val aiGateway: AiGateway,
) {
    /** 把 chain 内所有 entity/concept + 源页 → 合成一篇 wiki_synthesis（P8:不用 wiki_source 避免和源页命名冲突） */
    suspend fun distill(chainId: String): String {
        val chain = chainDao.get(chainId)
        val allPages = pageDao.getByKbAndTypes(chain.kbId, listOf("wiki_entity", "wiki_concept", "wiki_source"))
        val relatedPages = allPages.filter { it.belongsToChain(chainId) }  // 简单实现：按 thread.mainlineJson 筛
        val prompt = buildDistillPrompt(chain, relatedPages)
        return aiGateway.complete(prompt, reasoningEffort = HIGH)  // 高档思考强度
    }
}
```

**新 Worker** `worker/DistillationWorker.kt`：
- 入参 `chainId`
- 步骤 1：调 `DistillationEngine.distill(chainId)` → markdown
- 步骤 2：写新 `knowledge_item` 行（`sourceType = "wiki_synthesis"`,`status = STATUS_RECOMMEND_READY`）
- 步骤 3：更新 `knowledge_fragment_chain.status = 'RECOMMEND_READY'`
- 步骤 4：失败 retry，与 ingest 隔离（仿 RELIAB-1 模式）

**归档**（FRAG-1.6 收尾）：
- 复用 `ArchiveRecommendWorker`（已存在），加一行 `KnowledgeFragmentChainEntity.status = 'ARCHIVED'`
- 详情页"归档"按钮 = 只在 `RECOMMEND_READY` 状态显示 → 弹确认 dialog → 调 `ArchiveRecommendWorker`
- 列表展示：归档 tab 内按 `updatedAt DESC` 排序（标星排序在 FRAG-1.7 引入 `starredAt` 后再加）

**Prompt 模板**（`AiPromptTemplates` 新加 `distillationPrompt`）：
```
你是知识汇编者。以下是围绕一个核心问题"${coreQuestion}"收集的 entity、concept、源页内容。
请基于这些材料，撰写一篇结构严谨、引用清晰的综合性文章。文章应：
- 围绕核心问题展开，不发散
- 引用具体的 entity 名称（用 [[entity名]] wikilink 格式）
- 标注每个论点的来源（用 [[Source X]] 格式）
- 篇幅 1500-3000 字
- 段落用 ## 二级标题组织

[材料]
${materials}

[输出 markdown]
```

**验收**：
- 单测：mock LLM 输出 → 断言 wiki_synthesis 写入 + chain 状态更新
- 真机：可提炼 chain 点"开始提炼" → 30s 内出新 wiki_synthesis 页 + 详情页状态变"可归档"
- 真机：可归档 chain 点"归档" → 详情页状态变"已归档" + 列表"归档" tab 出现

### FRAG-1.7 — 标星 / 分享图片（最后一棒）

**改动**：

- 标星（仅在 ARCHIVED 状态可见）：
  - 详情页 toggle 按钮 → `db.knowledgeItemDao().update(chainItemId, starredAt = now)` 写入 distill 产物 wiki_synthesis item
  - 已标星 → 按钮显示"取消标星" → `starredAt = null`
  - 列表展示：归档 tab 内按 `starredAt IS NOT NULL DESC, updatedAt DESC` 排序（标星在前）
  - 含义对齐 P4:标星 = 个人偏好信号,影响排序,不是社交信号

- 图片分享（仅在 ARCHIVED 状态可见）：
  - 详情页"分享图片"按钮 → `LocalContext.current` 拿到根 `ComposeView` → `view.drawToBitmap(Bitmap.Config.ARGB_8888)` → 写到 `cacheDir/share-{chainId}.png` → `Intent(Intent.ACTION_SEND).setType("image/png").putExtra(EXTRA_STREAM, Uri)` → `startActivity(Intent.createChooser(...))`
  - **v1 简化**：只截详情页（不截整屏），用 `Modifier.onGloballyPositioned` 拿到 bounds 后 `drawToBitmap`
  - 截图内容：标题 + 目标卡 + 完整度 + 标星图标（让接收方一眼看出是已归档的"成品"）
  - **可选增强**：截图前注入"分享卡片样式"（去掉操作按钮、放大字号）—— 单独 sub-PR,本期不做

**验收**：
- 单测:`FragmentChainDetailScreen` ARCHIVED 状态 → 标星 + 分享按钮可见；NEED_REVIEW / DISTILL_READY / RECOMMEND_READY → 不可见
- 真机:归档后点"标星" → 列表排序变化 + 详情页按钮变"取消标星"
- 真机:归档后点"分享图片" → 系统选择器弹出 → 选"微信" → 收到 1 张 PNG（标题 + 目标卡 + 完整度 + ⭐）

---

## 6. 验证策略

### 6.1 单测（P0~P1 阶段必跑）

- `KnowledgeFragmentChainDaoTest`：4 个 SQL 查询（kbId / status / threadId 1:1 / 排序）
- `KnowledgeFragmentGapDaoTest`：查询（chainId / resolved / gapType 过滤）+ `markResolved(itemId, userText)` 写回
- `FragmentGapDetectorTest`：
  - 5 entity（3 孤立 + 2 互联）→ chain 列表正确
  - gap 优先级排序 + 8 GapType 枚举映射（detectGaps 字符串 → enum）
  - `hasSynthesisPage` 命中 wiki_index 页
- `DistillationEngineTest`：mock AiGateway → 断言 wiki_synthesis 写入 + chain 状态更新
- `NaturalLanguageGapReanalysisWorkerTest`：mock LLM 输出 resolved / still_open / newGap 三种 action
- `NewItemGapMatchWorkerTest`：mock LLM 输出 `{"matchedGapIds":[1,3]}` → 断言对应 gap 标 `resolved=true, resolvedByItemId=...`
- `FragmentChainDetailScreenTest`（Compose Test）：
  - 4 种 LifecycleStatus → 按钮可见性（NEED_REVIEW:重新分析; DISTILL_READY:开始提炼; RECOMMEND_READY:归档; ARCHIVED:标星+分享）
  - 点击"自然语言"输入 → 触发 onReanalyze 回调
  - FRAG-1.7 单独验证:ARCHIVED 状态标星 toggle + 分享 Intent 唤起

### 6.2 集成

- `AppDatabaseMigrationTest` 加 v11 → v12（不丢老数据;`gapsJson` 字段保留）
- backfill 单测:mock 1 个老 thread + `gapsJson=["...合成页..."]` → 断言 1 个 NEED_REVIEW chain + 1 个 MISSING_SYNTHESIS gap 行写入
- 端到端 ingest → 1 个 KB → 打开碎片整理 → 看到 chain → 详情页 → 自然语言重分析 → gap 更新 → re-import 新 item 自动 match → 提炼 → 归档 → 标星 → 分享

### 6.3 真机回归

- 3 档 KB fixture：5 entity / 50 entity / 500 entity
- 测项：
  - 打开 FragmentOrganizeScreen 耗时（< 200ms）
  - 4 tab 切换流畅度（60fps）
  - 详情页加载耗时（< 200ms）
  - 自然语言重分析 LLM 调用耗时（< 10s）
  - 提炼 LLM 调用耗时（< 90s，HIGH 档；MEDIUM 档 30s 内）
  - 分享图片生成耗时（< 500ms）
  - 归档后 chain 在"归档" tab 顺序（标星在前）

---

## 7. 风险与回退

| 风险 | 触发条件 | 缓解 | 回退 |
|---|---|---|---|
| 1.1 schema migration 失败 | 老 v11 KB 升 v12 | 用 `Migration(11, 12)` 加列（不重建） | `fallbackToDestructiveMigration()` 临时绕开 |
| 1.1 新表缺索引 / 500+ entity KB 查询慢 | chain 列表 + gap 列表全表扫描 | migration 同步建 `(knowledgeBaseId, status)` + `(chainId, resolved)` 索引（schema 内已声明） | DAO 端 `@Query` 加 `LIMIT 200` + 后台分页 |
| 1.2 detectByKb 在大 KB 上慢 | 500+ entity 全表扫描 | 加索引 `(knowledgeBaseId, status, starredAt)` | 限制单次扫描 limit + 异步分批 |
| 1.2 gap 数量爆炸 | detectGaps 8 条规则全命中 | UI 端按 priority 排序 + top 3 显示 | 详情页加"展开全部"折叠 |
| 1.4 详情页 4 tab 状态映射错 | 4 状态 ↔ 4 tab 文案对不上 | 在 `Models.kt` 顶部加表格常量 + 单测 | UI 端 fallback 到 `STATUS_*` 枚举名 |
| 1.5 LLM 重分析输出格式错 | LLM 返回非 JSON | prompt 加 `strict JSON output` + 解析失败 fallback "no change" | 退化为纯规则 detectGaps（不动） |
| 1.6 提炼 LLM 失败 | 网络 / rate limit | 仿 RELIAB-1 retry 3 次 + backoff | chain 状态保持 DISTILL_READY，下次手动重试 |
| 1.6 分享图片截屏失败 | Compose 还没布局完就截图 | 加 `LaunchedEffect` 等待 first frame 后再截 | 退化为分享文本（链标题 + 描述） |
| 1.6 distill 重复 | 用户连续点 2 次"开始提炼" | Worker 入口查 status（`DISTILL_READY` 才跑） | chainDao 设状态锁（status != DISTILL_READY 直接 return） |
| 1.6 wiki_synthesis 与现有 wiki 页面混淆 | 用户看到 distill 产物但不知道是 chain 综合 | `sourceType = "wiki_synthesis"` 单独区分；UI 端在 viewer 顶部加 "📚 知识链综合" badge | 改回合并到现有 wiki 页（v2 再讨论） |

---

## 8. 关联

- **MERGE-1** — entity/concept 合并是"碎片整理"的输入数据源，合并质量直接影响 detectGaps 准确度
- **THREAD-1** — `ThreadEvolutionRunner.detectGaps` 是缺口卡内容的唯一来源，THREAD-1 PR-E1（保留 description 先验）会让"目标卡"内容更稳定
- **ARCH-7 / ARCH-7.1** — 提炼 LLM 调用要走 ARCH-7.1 PR-T 的精简路径，避免 token 暴涨
- **ARCH-8** — 提炼用 `reasoningEffort = HIGH`，与 SettingsScreen 5 档下拉联动
- **RELIAB-1** — `DistillationWorker` / `NaturalLanguageGapReanalysisWorker` 走 IngestRuntime 协程，不受黑屏影响
- **PERF-13** — FragmentChainDetailScreen 用 LazyColumn 渲染缺口列表（同 wiki viewer 套路）
- **FRAG-1 → FRAG-2 (future)** — 真实 entity 图谱视图可作为 FRAG-1 收尾后的 v2，单独 doc

---

## 9. 决策记录

- **2026-06-05 立项**：用户在 office-hours 报"碎片整理未实现"
- **2026-06-05 落定方向**：
  - **采用 Approach A（卡片驱动 v1）**——用户 D1 选 A,7 PR 全独立可回退
  - **加状态码 `STATUS_DISTILL_READY`**——避免 `RECOMMEND_READY` 语义重叠
  - **加 schema 列 `starredAt`(在 item 上,标星 distill 产物) + `chainId`(在 fragment 上)**——NOT NULL DEFAULT NULL,向后兼容
  - **chain ↔ thread 1:1 映射(P11)**——v1 简化,chain.id == thread.id,unique index 约束
  - **status 单一源在 chain(P3)**——item 上不重复状态码,4 个 LifecycleStatus 驱动 4 tab
  - **distill 产物用 `wiki_synthesis`(P8)**——避免和现有 `wiki_source` 源页命名冲突
  - **gapsJson 老字段保留 + backfill(P3 派生)**——v1 migration 解析老字符串 → 写结构化表,UI 端只读新表
  - **8 GapType 枚举与 detectGaps 8 条规则 1:1 对齐**——substring match 解析
  - **自然语言重分析 user-asserted(P12)**——LLM 不能验证,UI 标 ⚠️"用户声称已补,尚未经新 ingest 验证"
  - **re-import 自动 match best-effort(P13)**——新 NewItemGapMatchWorker,失败不动
  - **FRAG-1.6 / 1.7 拆分**——提炼+归档(1.6) 和 标星+分享(1.7) 独立可发布
  - **图片分享走系统 Intent.ACTION_SEND**——不引入新依赖
  - **不引入 force-directed layout**——v2 再上
- **未决项**：
  - 提炼 LLM 的 `reasoningEffort` 默认档:建议 `HIGH`(用户已选),可考虑在设置页让用户选
  - 标星是否要"取消标星"按钮:v1 加上(toggle 风格)
  - 归档后是否能"撤销":v1 不做(用户同意后归档是单向),v2 可加"最近归档"二级页
  - 自然语言重分析的 LLM 思考强度:建议 `MEDIUM`(轻量判断,不深推理),v2 可调
  - 真实图谱视图(Approach B)何时启动:建议等 v1 7 个 PR 全跑通 + 用户反馈后再立项 FRAG-2

---

## 10. The Assignment（用户下一步具体动作）

> **不是"go build it"——是 1 个具体动作**：

1. **现在做**：在 `dev` 机器上手动跑 1 个真实 KB ingest（含 3-5 条 markdown 笔记），观察：
   - 现有 `FragmentOrganizeScreen` 是否真的空？（`KnowledgeManager.fragments`）
   - 现有 thread 是否有 `gapsJson` 非空的情况？（用 `adb shell sqlite3` 看 `knowledge_thread` 表）
   - 现有 entity 图谱中"孤立 entity"占多少比例？
2. **带着数据反馈回来**：告诉 office-hours（或者直接进 FRAG-1.1）：
   - 现有 thread 中 gap 命中率（"多少 thread 有非空 gapsJson"）
   - 现有 entity 中孤立比例（"< 2 度数的 entity 占总 entity 的百分比"）
3. **基于真实数据决定**：是否要"先优化 detectGaps 规则"再上 v1 UI？还是按本文 7 PR 直接推？

> 这 3 步是为 7 PR 找"接地气的数据基线"，避免上来就建 schema 后发现"根本没有 gap"或"所有 entity 都孤立"。

---

## 11. What I noticed about how you think

观察到的几个 signal（Builder mode 视角）：

- **你把"图形化方式"放在需求里**——而不是"列表显示"。说明你想要的不是"加一个 tab 显示数据"，是"用户能直观感受到知识网络的形状"。这种产品直觉比具体实现选型更重要。
- **你用了"知识链条"这个隐喻**（不是"知识集"或"知识图"）——链条暗示**有方向、有起点终点、有完整度**。这直接决定了状态机是"待完善 → 可提炼 → 归档"的串行，而不是"分类 + 标签"的网状。
- **你把"自然语言重分析"和"重新导入"作为 2 个补全入口**——说明你理解"用户既是生产者又是消费者"。重新导入是被动的（等用户想起补资料），自然语言是主动的（用户已经知道缺什么）。这个区分很细，决定了 worker 怎么设计。
- **你最后提到"管理为 全部、待完善、可提炼、归档"**——你已经在心里画了 4 个 tab，但 4 tab ≠ 4 状态码（细节上 `可归档` vs `归档` 应该是 2 个不同 tab），这种"高维对齐但低维有缝"是正常的产品需求精度。已在 §1.1 标注 + FRAG-1.3 修正。
- **"标星 = 推荐"**——你没说"标星后全用户可见"（你是单机 app），所以标星是**个人偏好信号**（影响排序），不是**社交信号**（影响他人看到）。这个区分让"标星"是排序权重，不是独立状态。
