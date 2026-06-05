# THREAD-1 — 灵感脉络：基于新文档的联合演化

> 状态：**DRAFT**（2026-06-05）
> 范围：thread 演化的**全链路**（schema、runner、worker、调度、UI），重点是回答"现在还差什么"
> 用户问题：知识库新文档入库时,要基于旧脉络 + 新文档做联合加工,从碎片化灵感里找主线、识缺陷、给建议；演化与 ingest 必须隔离
> 用户在 office-hours 中说"先整出方案,我们然后再实施"——本文档是方案,不是实施指令

---

## 0. 一句话结论

**核心机制已经存在**：`ThreadEvolutionRunner`（426 行）+ `ThreadEvolutionWorker` + `LlmInspirationThreadWorker` + `KnowledgeThreadEntity/Log` 表 + `RebuildDebouncer` 隔离,已经在跑。新文档入库时通过 `scheduleThreadUpdate(kbId)` → debouncer → runner 的链路触发,**与 ingest 完全解耦**（失败不传播、唯一工作替换、可手动重跑）。

**真正的 gap 是 3 个,且都不阻塞"现在能不能用"**：

| Gap | 触发体感 | 是否现在做 |
|---|---|---|
| G1. 全量重算,旧脉络没作为"先验"输入 | KB 大时单次 4-5s(从代码注释可见)；新文档只是触发器,不是"增量信号" | **不做**——等用户报体感再做 |
| G2. 旧脉络 `existing` 的 `description` / `coreQuestion` / `gaps` 没有被复用 | 每次重建相当于从零起,description 频繁"主题尚未凝聚" | **可选 PR-E1** |
| G3. 没有"重新演化"按钮,UI 看不到演化历史 | 用户感知不到演化发生过 | **可选 PR-E2**(UI 增量) |

**用户三个原始诉求的状态**：
- "新文档入库时,基于旧脉络 + 新文档联合加工" → **已实现**(G1/G2 限定为优化项)
- "识别缺陷、给建议" → **已实现**（`gapsJson` + `nextSuggestionsJson` + log）
- "thread 演化与 ingest 隔离" → **已实现**（独立 worker + RebuildDebouncer + 失败 retry 不阻塞）

---

## 1. 现状盘点（基于实际代码,2026-06-05）

### 1.1 数据层

`KnowledgeThreadEntity`（`KnowledgeThreadEntities.kt:6-20`）：
- `id` / `knowledgeBaseId` / `description` / `coreQuestion` / `mainlineJson` / `relationsJson` / `gapsJson` / `nextSuggestionsJson` / `inputHash` / `version` / `createdAt` / `updatedAt`
- **缺**：没有"上次演化基于哪些 item ids"的列表,无法精确判断"哪些是新文档"

`KnowledgeThreadLogEntity`（line 22-32）：`triggerType` / `triggerId` / `beforeHash` / `afterHash` / `summary` —— 演化历史已留痕。

DAO：`getByKb` / `getById` / `insert(REPLACE)` / `update` / `observeByThread`。

### 1.2 演化逻辑（核心）

`ThreadEvolutionRunner.runEvolution(db, repository, kbId)`（`ThreadEvolutionRunner.kt:63-146`）：

1. 跳过 unfiled KB
2. **只读 wiki_* + inspiration + text 三类 item**（line 75,带 `deletedAt` 过滤）
3. 算 `inputHash`（line 84）—— 与上次一致则短路返回
4. 5 个内部函数产生 5 段输出：
   - `buildDescription(baseName, items)` —— 主题行 + 4 类统计
   - `extractCoreQuestion(items)` —— 优先读 frontmatter `thesis` 字段,fallback 到标题高频词
   - `extractMainlines(items)` —— 合成页 chain（index/overview/log）+ 标签 top-3 聚类
   - `extractRelations(items)` —— **O(n²) → O(n) 桶优化**（line 289-341,显式注释) + wikilink 边 + tag 桶 + word 桶,top-30
   - `detectGaps(...)` —— 8 条规则,score-driven
   - `generateSuggestions(gaps, items)` —— gap → 建议 1:1 映射
5. 写 thread（version+1）+ log（带 beforeHash/afterHash + summary）
6. **不**触发布局变更；P0-1 注释明确说：graph rebuild 由 `RebuildDebouncer` 单独调度,不再二次重建

### 1.3 调度与隔离

- `ProcessingTaskScheduler.scheduleThreadUpdate(kbId)`（line 76-97）：
  - 优先走 `RebuildDebouncer`（fire-and-forget,IO dispatcher）
  - fallback 到 WorkManager `enqueueUniqueWork("thread_update_<kbId>", REPLACE, …)`
- `LlmInspirationThreadWorker` 走单独链路 `scheduleLlmThreadUpdate(kbId, newItemId, triggerType)` —— 用于灵感 KB 增量 LLM 更新,**失败 fallback 到 `ThreadEvolutionWorker` 程序化脉络**（`ProcessingTaskScheduler.kt:99-103` 注释）
- `ThreadEvolutionWorker.doWork()`（`ThreadEvolutionWorker.kt:32-44`）：try/catch 包 `runEvolution`,失败 `Result.retry()`,**不抛回 WorkManager 的 failure 链**
- `ThreadViewModel.triggerManualEvolution()`（`ThreadViewModel.kt:69-78`）：UI 手动触发,注释明确说"之前 in-line 路径只更新 graph + drop log row,造成'知识主线'空 + 假 toast"——已修

### 1.4 隔离设计的具体体现

| 隔离维度 | 现状 |
|---|---|
| 触发解耦 | ingest 完成 → `scheduleThreadUpdate(kbId)` 只发任务,不直接调 runner |
| 失败隔离 | runner 抛异常 → worker `Result.retry()` → WorkManager backoff,**不**回滚 ingest |
| 唯一性 | `enqueueUniqueWork("thread_update_<kbId>", REPLACE, …)` —— 同时只跑一份 |
| 缓存独立 | `inputHash` 只看 items 内容（id/title/tags/links）,不交叉 ingest cache |
| Prompt 版本独立 | `THREAD_EVOLUTION_V1`（`PromptVersions.kt:6`）与 `INGEST_*` 完全分离 |
| 重跑入口 | `ThreadViewModel.triggerManualEvolution()` + 进程被杀后由 WorkManager 恢复 |

---

## 2. 真正的 Gap（与原始诉求逐条对账）

### 2.1 "旧脉络 + 新文档联合加工" — 部分实现,有 2 个优化点

**当前行为**：`runEvolution` 把 KB 里**所有**符合条件的 item 全部重算（line 75 全量读）,`existing` 仅用于 `inputHash` 短路和 version+1。**旧脉络的 5 段输出（description / coreQuestion / mainlines / relations / gaps）完全丢弃**。

**理论上的"联合加工"应该是**：
- 输入：`existing.mainlineJson` + `existing.relationsJson` + `existing.gapsJson` + `[new item contents]`
- 输出：合并/修订/补充后的新脉络,而不是从零生成

**为什么不优先做**：
- 收益不确定：合并比全量重算省多少？取决于"新文档占比"——单次 ingest 后基本是 1/N（KB 大了才有意义）
- 风险确定：合并的 LLM 提示词比"全量重算"难调,容易丢主线
- 代码注释（line 81-83）显示作者已经踩过坑：之前把 `existing.mainlineJson` 塞进 hash,导致失败后永远命中 cache 写不出新 thread

**结论**：保留为"可选优化",等用户报"KB 1K+ item 演化太慢"时再上,做 LLM-增量 + 兜底全量的双轨。

### 2.2 "识别缺陷、给建议" — 已实现,无需改动

`gapsJson`（line 105）+ `nextSuggestionsJson`（line 106）已是 1:1 映射,`detectGaps` 8 条规则,`generateSuggestions` gap→建议字典。

**可选 PR-E1（推荐做）**：把 `existing.description` / `existing.coreQuestion` / `existing.gaps` 作为**先验**传给 `runEvolution`,在新一轮计算时做"保留 - 修订 - 补充"三档决策。具体:
- `description`：`existing.description` 长度 > 50 → 保留,只在主线变化超过 30% 时重写
- `coreQuestion`：frontmatter `thesis` 命中 → 保留；否则 fallback 到 existing（不重新算高频词）
- `gaps`：`existing.gaps` 中"已修复"的（如"synthesis 缺失"在新一轮已有 index.md）剔除,新发现的追加
- `relations` / `mainlines`：仍全量重算（增量合并复杂度不值）

**风险**：要加 `existing.description`/`coreQuestion`/`gaps` → "仍然成立吗" 的判定逻辑,大约 30-50 行,出 bug 的面在"什么算已修复"。

### 2.3 "演化与 ingest 隔离" — 已实现,无改动

见 §1.4 表。如果要"加一层防御",可以:
- 给 `ThreadEvolutionWorker` 加 try/catch 把所有 Throwable 转成 Result.retry(),避免 CancellationException 之外的东西冒到 WorkManager
- 当前实现（line 39-43）已经这么做了

---

## 3. PR 拆分

| PR | 改动 | 风险 | 推荐度 |
|---|---|---|---|
| **不写 PR-0**：现状已覆盖 | — | — | — |
| **PR-E1**（可选）| `ThreadEvolutionRunner` 把 `existing.description` / `coreQuestion` / `gaps` 作为先验传入,新计算时做"保留 - 修订 - 补充"三档决策 | 中：要写"什么算已修复"的判定 | ⭐️ 体感有差异时再做 |
| **PR-E2**（可选）| UI 增量：`ThreadViewModel.threadLogs` 已经在流式暴露,`InspirationScreen` 加上"演化历史"展开面板,展示 N 次 `KnowledgeThreadLogEntity` | 低：纯 UI | ⭐️ 推荐——可观察性立竿见影 |
| **PR-E3**（可选）| 手动重新演化按钮：UI 触发 `triggerManualEvolution()`,带"演化中..."spinner | 低：API 已存在 | ⭐️ 推荐——补齐 P0-1 注释里"用户感知不到"那一档 |

> **推荐组合**（如果用户拍板"要加东西"）：**E2 + E3**（纯 UI,不动核心逻辑,1 个 PR 一起做）。E1 留作 KB 长大后再处理。

---

## 4. PR-E1 设计（如果要做）

### 4.1 改动点

只动 `ThreadEvolutionRunner.kt`,不新增表 / 不改 worker / 不改 scheduler。

### 4.2 函数签名升级

```kotlin
private fun buildDescription(
    baseName: String,
    items: List<KnowledgeItemEntity>,
    previous: KnowledgeThreadEntity? = null,   // ← 新增
): String {
    if (items.isEmpty()) return "知识库「$baseName」尚无已整理的知识条目"

    // E1: 保留旧 description 当新主线变化 < 30% 时
    if (previous != null && shouldKeepDescription(previous, items)) {
        return previous.description
    }
    // 走原逻辑
    val tagSet = ...
}
```

### 4.3 判定函数

```kotlin
private fun shouldKeepDescription(
    previous: KnowledgeThreadEntity,
    items: List<KnowledgeItemEntity>,
): Boolean {
    if (previous.description.length < 30) return false
    val previousMainlines = parseStringList(previous.mainlineJson)
    val newMainlines = extractMainlines(items)   // 跑一遍,但只算 mainline
    if (previousMainlines.isEmpty() || newMainlines.isEmpty()) return false
    val overlap = previousMainlines.intersect(newMainlines.toSet()).size
    return overlap.toDouble() / previousMainlines.size >= 0.7
}
```

### 4.4 同样套路套到 `extractCoreQuestion` / `detectGaps`

- `extractCoreQuestion`：`existing.coreQuestion` 长度 > 10 且新 frontmatter `thesis` 没变 → 保留
- `detectGaps`：旧 gaps 中"已被本轮修复"的（如"synthesis 缺失" 但 `wiki_index` 数 > 0）剔除；新发现的追加

### 4.5 风险

- **"什么算已修复"易错**：尤其是 gaps 规则多（8 条），要每条单独判定
- **description 保留可能漏改**：如果用户改了 KB 名字,`buildDescription(baseName, items)` 里硬编码的 `baseName` 不会同步
- **回退**：用 promptVersion 还是 git revert？建议 promptVersion 升级到 `THREAD_EVOLUTION_V2`,老用户继续走 V1（cache 命中短路）

### 4.6 验收

- 单测：相同 inputs → E0 输出 == E1 输出（避免回归）
- 集成：mock 一个 `existing` 带 description,断言 `buildDescription` 返回相同 description 当主线条没变
- 真机：1 个 KB ingest 10 条新 item,看 description 变化幅度

---

## 5. PR-E2 + PR-E3 UI 增量（推荐组合）

### 5.1 E2 演化历史

`ThreadViewModel.threadLogs`（line 26-33）已经在流式暴露 `KnowledgeThreadLogEntity` 列表,UI 端 (`InspirationScreen.kt:297` 拿到 `thread`) 加上"查看演化历史"展开项即可。

每个 log row 展示：
- 时间（`createdAt` 转 yyyy-MM-dd HH:mm）
- `summary`（如"脉络自动更新：3 条知识,1 条主线,5 条关联,2 个缺口"）
- 可选 diff：`afterHash` vs `beforeHash` 不等 → 显示"+N 主线 / -M 关联"

### 5.2 E3 手动重新演化

UI 按钮（如 InspirationScreen 顶栏右侧）→ `viewModel.triggerManualEvolution()` → 后端走 `scheduleThreadUpdate` → 防重（演化中按钮 disable + 进度条）。

`ThreadViewModel.triggerManualEvolution()` 已经有（line 69-78），但没暴露"演化中"信号。建议加 `val evolving: StateFlow<Boolean>`，worker 进入时 emit true，结束时 emit false。

### 5.3 风险

- 演化中用户切后台 → `viewModelScope` 取消 → `evolving` 卡 true。加 `WorkManager.getWorkInfosForUniqueWork` 监听器兜底。
- 没有实际隔离风险,纯 UI。

---

## 6. 验证策略（不实施,仅规划）

### 6.1 已有覆盖（无需重测）

- `InspirationThreadPromptTest`（5 个 case）：`AiPromptTemplates.inspirationThreadPrompt` 的 prompt 拼接
- `ThreadEvolutionRunner` 的 O(n²)→O(n) 优化已有注释说明（line 288-298）
- `RebuildDebouncer` 隔离路径由 P0-1 commit 验证

### 6.2 新增测试（如做 E1）

- `ThreadEvolutionRunnerTest`：
  - `existing.description` 保留场景（主线条 overlap ≥ 70%）
  - `existing.coreQuestion` 保留场景（frontmatter thesis 未变）
  - gaps 修复场景（`wiki_index` 出现后"synthesis 缺失"被剔除）

### 6.3 端到端

- 走真机：在 InspirationScreen 加 logcat tag,观察 `runEvolution` 进/出 + `evolving` state 切换

---

## 7. 风险与回退

| 风险 | 触发 | 回退 |
|---|---|---|
| E1 保留旧 description 导致 KB 改名不更新 | 用户改 KB 名后 description 仍带旧名 | 在 `shouldKeepDescription` 里加 `baseName` 匹配判定,不一致 → 强制重算 |
| E1 gaps 误判"已修复" | 用户删了 index.md 但还有 synthesis 类页 → gap 误删 | `detectGaps` 仍然跑全量,只在最后做 diff,不直接复用 |
| E2 演化历史 UI 加载 N 条 log 卡 | log 表无 LIMIT,KB 频繁演化时变长 | log 表加 `LIMIT 50` 流式分页,旧 log 转 archived |
| E3 手动按钮被狂点 | 用户手快 → N 个 WorkManager job | `enqueueUniqueWork` 已经是 `REPLACE`,自动合并 |

---

## 8. 关联

- **ARCH-7 / ARCH-7.1** —— LLM token 优化：E1 如果引入 LLM 增量,要走 ARCH-7.1 PR-T 的精简路径
- **PERF-13** —— UI 懒加载：E2 的演化历史面板用同一个 viewer 渲染 thread markdown
- **`ThreadEvolutionRunner.kt:289`** —— O(n²)→O(n) 桶优化的注释明确提到"4-5s 卡顿"是 P0-1 之前的问题,**当前已修**,E1 不需要再碰这块

---

## 9. 决策记录

- **2026-06-05 立项**：用户在 office-hours 提"灵感脉络没有根据新文档更新"。摸清代码后发现核心机制已存在(ARCH-3/4/5 + P0-1 + LlmInspirationThreadWorker 多轮迭代),不是从零设计。
- **2026-06-05 落定方向**：
  - 不做 PR-0（现状已覆盖用户原始诉求）
  - 推荐 PR-E2 + PR-E3（纯 UI,可观察性 + 手动触发,1 PR 完成）
  - PR-E1（增量先验）保留为"KB 长大后再做"
- **未决项**：
  - 用户是否认可"现状已覆盖"？如不认可,指出现体感痛点（演化慢 / 不更新 / 没建议）再排期
  - E2/E3 是否要做？纯 UI 工作量小（约 200 行）,但要先确认用户能接受"等下个迭代"
  - E1 是否要做？等 KB 规模 / 演化频率数据再决定,目前没有迫切证据
