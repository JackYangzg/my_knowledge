# ARCH-3 / ARCH-4 / ARCH-5 — 长期架构演进设计

> 状态：设计稿（待评审）
> 适用范围：本里程碑不落地，仅供下一轮迭代对齐方向
> 对应评审项：ARCH-3（领域模型边界）、ARCH-4（事件总线 vs 直接调用）、ARCH-5（KMP 化路径）

---

## 0. 背景与目标

当前工程已经完成单 Android 端 MVVM + Compose + Room + WorkManager 的闭环，业务代码集中在 `data/`、`domain/`、`ui/`、`worker/` 四个顶层包下。`KnowledgeRepositoryImpl` 承担 18 个 DAO 的协调，跨过 P0~P2 共 19 项评审后，单仓单端可维护性已经达标。

接下来的三个 ARCH 评审项本质上是同一类问题：**为下一轮规模扩张预留结构**：
- **ARCH-3**：当 `KnowledgeItem` 不再是单一聚合，而要承载「Wiki 页」与「外部来源」两种语义时，领域模型如何分层。
- **ARCH-4**：当 UI 不再直连 ViewModel，而是要监听「跨页签的写入」「跨后台任务的阶段完成」时，事件通路如何选型。
- **ARCH-5**：当 iOS / Desktop / Web 任意一端要求复用核心领域逻辑时，模块切分如何不重写。

本文档不在本里程碑实现任何代码变更，但给出**当前状态评估**、**目标态描述**、**本里程碑可落地的最小进展**三段。

---

## 1. ARCH-3：领域模型边界

### 1.1 当前状态

`domain/model/` 下当前只有「聚合根」：

```
domain/model/
  KnowledgeItem           (聚合根, 18 列)
  KnowledgeBase
  KnowledgeGraph
  KnowledgeThread
  ParsedContent
  AnalysisResult
  SourceDocument
  ... (共 ~15 个数据类)
```

`KnowledgeItem` 同时承担两种语义：
- 作为「知识库条目」被检索、引用、打标签
- 作为「外部来源的加工产物」反向追溯到 `SourceDocument`

后果：
1. `KnowledgeItemDao` 的查询要按「baseId / sourceId / hasEmbedding / status」4 个维度同时打 tag，单表超过 30 列。
2. `KnowledgeRepositoryImpl` 的 `getItemWithRelations(itemId)` 需要 4 表 join，无法用 Room 自动生成，必须手写 `@Query`。
3. UI 层把 `KnowledgeItem` 直接渲染成「Wiki 页」或「来源卡片」是同一个 Composable，靠 `if (sourceId != null)` 分支，调用方被迫理解两个语义。

### 1.2 目标态：sealed `KnowledgeEntry`

```kotlin
sealed interface KnowledgeEntry {
    val id: String
    val baseId: String
    val updatedAt: Long

    /** 内部 Wiki 页：手工录入 / AI 整理后的纯条目 */
    data class WikiPage(
        override val id: String,
        override val baseId: String,
        val title: String,
        val markdown: String,
        val tags: List<String>,
        override val updatedAt: Long,
    ) : KnowledgeEntry

    /** 外部来源加工产物：可溯源到 SourceDocument */
    data class SourceItem(
        override val id: String,
        override val baseId: String,
        val sourceId: String,
        val title: String,
        val summary: String,
        val fragments: List<KnowledgeFragment>,
        override val updatedAt: Long,
    ) : KnowledgeEntry
}
```

仓库层只暴露 `KnowledgeEntry`；UI 层用 `when` 分发，类型系统强制两种语义不能混用。

存储层方案对比：

| 方案 | 优点 | 缺点 |
|------|------|------|
| 单表 + `kind` 列 | 迁移零成本 | 大量 `kind = ?` 过滤，无法利用 SQLite 类型系统 |
| **双表 + 共享主键**（推荐） | 每张表 8~12 列，索引纯净；FK 关系清晰 | 需要迁移脚本（v11→v12 或 v12→v13） |
| 多态 JSON（Room TypeConverter） | 表结构最简 | 失去关系查询能力 |

### 1.3 本里程碑可落地的最小进展

不动领域模型，但**为 1.2 的双表方案做探针**：
- 在 `KnowledgeItemEntity` 顶部加 1 行注释：`// TODO(ARCH-3): migrate to dual-table WikiPage/SourceItem`
- 在 `KnowledgeRepository` 接口的 `getItem` 签名加 `@Deprecated("prefer getWikiPage/getSourceItem")` 注解
- `ARCH-3 ticket` 写到下一轮 `03-改进路线图.md` 的 P3 阶段，附 schema 迁移草案（CREATE TABLE wiki_page ... ; CREATE TABLE source_item ... ; INSERT ... SELECT FROM knowledge_item WHERE source_id IS/NOT NULL ; DROP TABLE knowledge_item）

---

## 2. ARCH-4：事件总线 vs 直接调用

### 2.1 当前状态

工程内目前是**纯直接调用**：
- `ViewModel` 调 `repository.xxx()`
- `Worker.doWork` 调 `repository.xxx()`、`scheduler.signal()`
- `IngestOrchestrator` 调 `IngestScheduler.claimNext()`

`IngestScheduler` 通过 4 lane 协程 + 轮询 DB 实现「跨 Worker 的写入完成通知」，本质上是**数据库作为事件总线**。

### 2.2 问题域

需要事件通路的真实场景（当前不存在但即将出现）：

| 场景 | 当前做法 | 痛点 |
|------|---------|------|
| Wiki 编辑器保存后, Library 页签需要刷新 | Library 每次 `LaunchedEffect` 重新查 DB | 编辑器保存和 Library 呈现有 1~2 秒延迟 |
| 灵感脉络生成完成, 主页 Tag 流需要追加 | 主页不感知, 只能下次进入重新加载 | 用户感知不到后台产物 |
| 删除一个 Item, 关联 Graph 节点要重算 | `deleteItem()` 内同步 `rebuildGraphForBaseAffected` | 调用栈长 8 层, 单测 mock 困难 |

### 2.3 方案对比

**A. SharedFlow + Repository 持有**：
- 仓库层在 `MutableSharedFlow<KnowledgeEvent>` 上 `tryEmit`
- ViewModel 用 `repository.events.filter{...}.collect{...}`
- 优点：单进程内零依赖、零序列化、与现有协程模型天然契合
- 缺点：跨进程（Worker 进程）不感知；只能 in-memory

**B. Room outbox 表**：
- 写入时 `INSERT INTO event_outbox(kind, payload_json, created_at)`，订阅方轮询
- 优点：跨进程、跨重启、测试可重放
- 缺点：每条写入多一次事务；payload 序列化成本

**C. WorkManager 完成回调（ListenableWorker.Result.success() + WorkContinuation）**：
- 只对「任务完成 → 触发下一个任务」这一种事件合适
- 不适合「Wiki 保存 → Library 刷新」这种 UI 事件

**推荐组合**：
- UI 域事件（Wiki 保存、脉络完成）走 **A（SharedFlow）**
- 后台任务编排（解析→分析→生成）走 **C（WorkContinuation）** + 现有的 DB 轮询
- 跨进程可靠性事件（罕见）走 **B（outbox）**，本期不实现

### 2.4 本里程碑可落地的最小进展

不动事件通路，但**为 2.3 的 A 方案预留入口**：
- 在 `KnowledgeRepository` 接口最末尾加注释 `// ARCH-4 ticket: events: SharedFlow<KnowledgeEvent>`（仅注释）
- `IngestOrchestratorApi` 接口已存在（CQ-10），作为「后台域」的直接调用入口；本里程碑继续保留

---

## 3. ARCH-5：KMP 化路径

### 3.1 当前状态

工程根目录 `build.gradle.kts` 只有 `com.android.application` 插件，未启用 Kotlin Multiplatform。代码组织上：

| 包 | Android 耦合度 | 复用价值 |
|----|--------------|---------|
| `domain/model/`, `domain/repository/` | **0**（纯 Kotlin） | **高** |
| `data/repository/KnowledgeRepositoryImpl.kt` | 5（18 个 Room DAO, WorkManager 入参） | 低 |
| `data/ai/AiGateway.kt` | 3（OkHttp, JSON 解析） | 中 |
| `data/ingest/*.kt` | 4（File I/O, Intent extras） | 中 |
| `ui/`, `viewmodel/`, `worker/` | 10（AndroidX, Compose, WorkManager） | 0 |

KMP 化的目标是 `domain/` 整个包 + `data/repository/` 接口层 100% 进入 `commonMain`，**实现层留在 `androidMain`**。

### 3.2 目标态：模块拆分

```
my_knowledge_core/       (KMP module, commonMain)
  domain/model/
  domain/repository/
  domain/usecase/        (新建, 把 repository 编排上提)

my_knowledge/            (Android module)
  data/repository/       (实现, 调 Room)
  data/ai/
  data/ingest/
  ui/
  viewmodel/
  worker/
```

依赖图：
```
my_knowledge (androidMain)
  → my_knowledge_core (commonMain)
  → androidx.compose
  → androidx.room
```

`my_knowledge_core` 不依赖 AndroidX、Room、WorkManager；只依赖：
- `kotlinx-coroutines-core`（不依赖 `-android`）
- `kotlinx-serialization-json`（用于 DTO 在 commonMain 内序列化）

### 3.3 迁移成本估算

| 改动 | 工作量 | 风险 |
|------|-------|------|
| 新建 `my_knowledge_core` 模块, 把 `domain/` 整包迁入 | 1 天 | 低（无 Android 依赖） |
| `KnowledgeRepositoryImpl` 拆成「接口 in core / 实现 in android」 | 2 天 | 中（要解决 `Context` 依赖, 需新建 `AppGraph` 入口） |
| `AiGateway` 切到 `commonMain` + `androidMain` 双实现 | 2 天 | 中（OkHttp 调用 Ktor client 即可, 但流式响应要重写） |
| `IngestFileParser` 切到 `commonMain` | 1 天 | 低（纯 File I/O, KMP 已有 `okio` 跨端） |
| iOS 端验证（Xcode 工程 + 最小 Demo） | 3 天 | 中（需装 KMP 工具链） |

合计：**9 人天**。本期不投入。

### 3.4 本里程碑可落地的最小进展

- 在 `app/build.gradle.kts` 顶部加注释：`// ARCH-5 ticket: extract my_knowledge_core module (KMP, commonMain)`，不改依赖
- `domain/repository/KnowledgeRepository` 的所有方法签名保持「零 Android 类型」——这已经是当前事实，CI 加一条 lint 规则（detekt）禁止 `domain/` 引入 `androidx.*` 或 `android.*` 包

---

## 4. 评审结论

| 评审项 | 当前可维护性 | 目标态成本 | 本里程碑动作 |
|--------|------------|-----------|------------|
| ARCH-3 | 7/10（单聚合根能撑住当前 200~500 items 量级） | 5 人天（含 schema 迁移） | 注释 + Deprecated 注解 + 路线图条目 |
| ARCH-4 | 8/10（DB 轮询 + 任务编排满足现状） | 2 人天（SharedFlow 接入） | 注释 + 路线图条目 |
| ARCH-5 | 8/10（domain/ 已经可零成本迁出） | 9 人天（含 iOS 验证） | 注释 + detekt 规则禁止 domain/ 引入 AndroidX |

**总投入**：0 行业务代码变更 + ~10 行注释 + 1 条 detekt 规则 + 1 个 roadmap 章节。

---

## 5. 下一轮入口

进入 P3 阶段时，建议按以下顺序：

1. **ARCH-3 优先**（schema 迁移最重, 越晚做越疼）
2. **ARCH-4 次之**（依赖 ARCH-3 的 sealed `KnowledgeEntry`, 事件 payload 要带新类型）
3. **ARCH-5 最后**（依赖前两者的稳定接口边界）
