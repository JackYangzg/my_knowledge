# MERGE-1 — 实体/概念合并：防止加工时"消失"

> 状态：**DRAFT**（2026-06-05）
> 范围：`IngestOrchestrator` 的 wiki 页面合并逻辑（line 812-854 + 2029-2102）、`WikiPageCompiler.merge` body 处理、`KnowledgeItemDao` 的 `OnConflictStrategy.REPLACE`
> 用户问题：**新知识加工生成的实体/概念合并到知识库时会出现"消失"**——同一实体在新 ingest 后内容被覆盖/丢失
> 用户要求"确保逻辑的合理性"——本文档是方案,先写后实施

---

## 0. 结论先行

**消失的 4 个具体原因**（按发生频率排序）：

| # | 根因 | 代码位置 | 频率 |
|---|---|---|---|
| **C1** | **body 段被整段替换**,不是 union | `wikiCompiler.merge(existing, draft, title)` 对 entity/concept 页直接覆盖 body | **高** —— 每次 ingest 都触发 |
| **C2** | DAO 用 `OnConflictStrategy.REPLACE` 写 wiki 页 | `KnowledgeItemDao.kt:92-95` | **高** —— 每次 ingest 都触发 |
| **C3** | 跨源 LLM 命名不一致("Apple" vs "Apple Inc.") | `WikiPageCompiler.kt:152-188` 信任 LLM 给的 `entity.name` | **中** —— 取决于 LLM 行为 |
| **C4** | LLM 漏识别 —— 已存在实体在新源未被识别,新源对它的描述无处合并 | `IngestOrchestrator` 入口只处理"LLM 这次给出的 entities" | **中** |

**4 个对应 PR,推荐先做 C1 + C2**（一次性解决 80% 的"消失"感）,C3 + C4 是体感优化、留作 P2。

---

## 1. 当前合并流程（基于实际代码）

### 1.1 ingest 写 wiki 页面的链路

`IngestOrchestrator.kt:812-854`：

```kotlin
val writtenItems = withWikiPageWriteLocks(kbId, pageDrafts) {
    val items = pageDrafts.mapIndexed { index, draft ->
        val existingPage = db.knowledgeItemDao()
            .getByKbSourceTypeAndTitle(kbId, draft.sourceType, draft.title)   // (1)
        val mergedMarkdown = mergeWikiPageMarkdown(                            // (2)
            existingMarkdown = existingPage?.contentMarkdown.orEmpty(),
            draft = draft,
        )
        // ... 进度更新 ...
        val item = KnowledgeItemEntity(
            id = existingPage?.id ?: UUID.randomUUID().toString(),            // (3) 复用 id
            // ... 字段填入 mergedMarkdown ...
        )
        db.knowledgeItemDao().insert(item)                                     // (4) REPLACE
        item
    }
    items
}
```

### 1.2 `mergeWikiPageMarkdown` 的分支（`IngestOrchestrator.kt:2029-2040`）

```kotlin
private fun mergeWikiPageMarkdown(existingMarkdown: String, draft: WikiPageDraft): String {
    val isListingPage = draft.sourceType == "wiki_index" || draft.sourceType == "wiki_overview"
    return if (isListingPage) {
        mergeListingPage(existing, incoming, pageTitle)   // ← section-aware union
    } else {
        wikiCompiler.merge(existingMarkdown, draft.markdown, draft.title)   // ← 这里!
    }
}
```

- **`wiki_index` / `wiki_overview`** 走 `mergeListingPage`（line 2062-2102）—— **section-aware union**,保留历史 bullet + 新增 bullet,**新内容追加**。这部分做得对。
- **`wiki_entity` / `wiki_concept` / `wiki_source` / `wiki_log`** 走 `wikiCompiler.merge(existing, draft, title)` —— **整段 body 替换**（除了 frontmatter 数组 union）。这就是 C1 的源头。

### 1.3 DAO 写入策略（`KnowledgeItemDao.kt:92-95`）

```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun insert(item: KnowledgeItemEntity)
```

`REPLACE` 策略在 SQLite 中是 `DELETE` 旧行 + `INSERT` 新行。这会触发：
- 与 `knowledge_item` 有 FK 关系的表（`knowledge_fragment` / `analysis_result` / `ask_citation` / `review_item`）的外键引用要么变 NULL,要么被 CASCADE 删除（取决于 schema 定义）
- `knowledge_item_fts` 触发器若定义在 DELETE 上,可能短暂无索引

### 1.4 LLM 命名不一致的根因

`WikiPageCompiler.kt:29-30`：

```kotlin
val entities = parseNamedObjects(analysis.entitiesJson, fallbackType = "entity")
val concepts = parseNamedObjects(analysis.conceptsJson, fallbackType = "concept")
```

`entity.name` 直接来自 LLM 输出,没有规范化。LLM 对同一实体可能在不同 source 给出：
- "Apple" / "Apple Inc." / "苹果公司" / "Apple 公司" —— 4 个不同 entity page
- 用户视角看,其中一个好像"消失了"(其实是被另一个同义条目覆盖)

### 1.5 已有好习惯

- **id 复用**（line 829）:`existingPage?.id ?: UUID.randomUUID()` —— 同一 (kbId, sourceType, title) 复用 id,**如果**用 `INSERT OR UPDATE` 就能避免 DELETE；当前 REPLACE 等价于"先删 id=X 的行,再插 id=X 的新行"
- **`withWikiPageWriteLocks(kbId, ...)`**（line 812）: 串行化同 KB 的 wiki 写,避免并发覆盖
- **frontmatter 数组 union**（`WikiPageCompiler.merge`）—— `related` / `sources` / `tags` 是 case-insensitive 数组并集,只是 body 没 union
- **section-aware listing merge** —— 已经在 `mergeListingPage` 实现了,可以复用模式

---

## 2. PR 拆分

| PR | 修复 | 风险 | 推荐度 |
|---|---|---|---|
| **PR-M1**（必做）| `wikiCompiler.merge` 对 entity/concept body 改成 section-aware union,和 `mergeListingPage` 同套路 | 中：要重新设计 body 段去重/合并策略 | ⭐️ 必做,影响最大 |
| **PR-M2**（必做）| DAO 加 `@Insert(onConflict = OnConflictStrategy.IGNORE)` 配合 `UPDATE` 显式覆盖,代替 `REPLACE`；保护 FK 引用 | 低：纯 DAO 改动 | ⭐️ 必做,正确性 |
| **PR-M3**（可选）| Entity canonical name 解析：alias 表 + 编辑距离归并；LLM 给的 name 先查 alias,再决定是 upsert 还是新建 | 中：alias 表维护成本 | ⭐️ 等用户报"重名条目多"再做 |
| **PR-M4**（可选）| ingest 时,对**已存在**的 entity name,prompt 强制 LLM 重提"对老实体的描述"并 union 进现有页 | 高：影响 LLM prompt 设计 + token | ⭐️ 等 KB 长大、用户希望"老实体越来越详细"再做 |

---

## 3. PR-M1：entity/concept body 段级 union

### 3.1 目标

把 `wikiCompiler.merge` 对 entity/concept body 的行为从"整段替换"改成"按 `## 段落` 分段 union"。

### 3.2 现状 body 结构（来自 `WikiPageCompiler.compile()` 看 body 模板）

entity 页 body 典型结构：
```markdown
# 实体名

## 概述
description

## 关联概念
- [[A]] [[B]] ...

## 来源
- [[Source X]]

## 出现位置
- [[Source X]] 第 N 段
```

concept 页 body 类似。

### 3.3 改动方案

**复用 `mergeListingPage` 的 `parseSections` / `sectionOrder` / `normalizeBulletKey` 模式**，扩展到 entity/concept 页。

新文件（或内联在 `IngestOrchestrator`）:

```kotlin
private fun mergeEntityPageMarkdown(
    existing: String,
    incoming: String,
    pageTitle: String,
): String {
    if (existing.isBlank()) return incoming
    if (incoming.isBlank()) return existing

    // frontmatter: union (沿用 wikiCompiler.merge 的行为)
    var merged = wikiCompiler.merge(existing, incoming, pageTitle)

    // body: section-aware union
    val (exFm, exBody) = splitFrontMatter(existing)
    val (_, inBody) = splitFrontMatter(incoming)
    val exSections = parseSections(exBody)
    val inSections = parseSections(inBody)

    // 保留顺序：历史段优先,新段追加
    val sectionOrder = LinkedHashMap<String, Unit>()
    exSections.keys.forEach { sectionOrder[it] = Unit }
    inSections.keys.forEach { sectionOrder[it] = Unit }

    val rebuiltBody = StringBuilder()
    for (title in sectionOrder.keys) {
        val exBullets = exSections[title].orEmpty()
        val inBullets = inSections[title].orEmpty()
        when {
            // bullet-list 段：union 去重
            exBullets.isNotEmpty() || inBullets.isNotEmpty() -> {
                val combined = LinkedHashMap<String, String>()
                for (b in exBullets) combined[normalizeBulletKey(b)] = b
                for (b in inBullets) combined.putIfAbsent(normalizeBulletKey(b), b)
                val bullets = combined.values.filter { it.isNotBlank() }
                if (bullets.isNotEmpty()) {
                    rebuiltBody.append("## ").append(title).append('\n')
                    bullets.forEach { b -> rebuiltBody.append(b).append('\n') }
                    rebuiltBody.append('\n')
                }
            }
            // 段落段("## 概述")：保留历史 + 新增追加
            else -> {
                val exContent = exSections[title]?.joinToString("\n").orEmpty()
                val inContent = inSections[title]?.joinToString("\n").orEmpty()
                if (exContent.isNotBlank() || inContent.isNotBlank()) {
                    rebuiltBody.append("## ").append(title).append('\n')
                    if (exContent.isNotBlank()) {
                        rebuiltBody.append(exContent.trim()).append('\n')
                    }
                    if (inContent.isNotBlank() && inContent.trim() != exContent.trim()) {
                        rebuiltBody.append('\n')
                        rebuiltBody.append(inContent.trim()).append('\n')
                    }
                    rebuiltBody.append('\n')
                }
            }
        }
    }
    val fm = splitFrontMatter(merged).frontMatter
    return if (fm != null) {
        fm.trimEnd('\n') + "\n\n" + rebuiltBody.toString().trimEnd('\n') + "\n"
    } else {
        rebuiltBody.toString().trimEnd('\n') + "\n"
    }
}
```

`mergeWikiPageMarkdown` 改为：

```kotlin
private fun mergeWikiPageMarkdown(existingMarkdown: String, draft: WikiPageDraft): String {
    return when (draft.sourceType) {
        "wiki_index", "wiki_overview" -> mergeListingPage(existingMarkdown, draft.markdown, draft.title)
        "wiki_entity", "wiki_concept" -> mergeEntityPageMarkdown(existingMarkdown, draft.markdown, draft.title)
        else -> wikiCompiler.merge(existingMarkdown, draft.markdown, draft.title)   // wiki_source / wiki_log
    }
}
```

### 3.4 行为变化（同一实体 X 被 source1 + source2 ingest）

| 场景 | 现状 (整段替换) | 改后 (段级 union) |
|---|---|---|
| `## 概述` 段:source1="X 是 A", source2="X 是 B" | source1 写的"X 是 A"被 source2 覆盖 | 两段并存:`X 是 A` + 空行 + `X 是 B` |
| `## 关联概念` 段:source1 含 A/B, source2 含 B/C | source2 的 C/B 列表,丢了 A | A + B + C |
| `## 出现位置` 段:source1 第 1 段, source2 第 2 段 | source2 的位置,丢了 source1 的 | 1 段 + 2 段 |
| LLM 漏识别 X:source2 不返回 X | 现有 X 页不动,source2 关于 X 的描述**丢失** | 仍然丢失 —— 这是 C4 范畴,需 PR-M4 |

### 3.5 风险

- **"段落段"如何决定用并排 vs 替换**：
  - bullet list 段 → union（去重）
  - 自由文本段（"## 概述"）→ 难以判定"新版本是否覆盖旧版本",保守做法是**追加**（用户能看出"source1 说 A, source2 说 B"）
  - 来源段（"## 来源"）→ union
- **段名漂移**：source1 用"## 关联概念",source2 用"## 相关概念" → 两个不同段,会重复
  - 缓解:加 `SECTION_ALIASES = mapOf("相关概念" to "关联概念", "出现的段落" to "出现位置", …)`
- **frontmatter 重复 union**：`wikiCompiler.merge` 先做了一次,后面 `splitFrontMatter` 再做一次,要去重

### 3.6 验收

- 单测：相同 `## 概述` 内容输入 → 输出 body 等价（去重不引入回归）
- 集成：mock 一条 entity ingest 两次,断言第二次的 `## 关联概念` 包含两次的 wikilink
- 真机：找一个 KB,source1 含 A/B,source2 含 C/D,view 实体页应同时含 A/B/C/D

---

## 4. PR-M2：DAO 改 IGNORE + UPDATE

### 4.1 目标

把 `@Insert(onConflict = REPLACE)` 改为"先按 id 查,存在则 UPDATE 不存在则 INSERT",避免 SQLite REPLACE 触发的 DELETE+INSERT 级联问题。

### 4.2 改动

`KnowledgeItemDao.kt` 加方法：

```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertOrIgnore(item: KnowledgeItemEntity): Long   // 返回 rowId, -1 表示冲突
```

`IngestOrchestrator.kt:828-850` 改写为：

```kotlin
val newId = existingPage?.id ?: UUID.randomUUID().toString()
val item = KnowledgeItemEntity(
    id = newId,
    // ... fields ...
    createdAt = existingPage?.createdAt ?: now,   // 保留原 createdAt
    updatedAt = now,
)
val rowId = db.knowledgeItemDao().insertOrIgnore(item)
if (rowId == -1L) {
    // 已存在 → UPDATE
    db.knowledgeItemDao().update(item)
}
item
```

### 4.3 行为变化

| 场景 | 现状 (REPLACE) | 改后 (IGNORE+UPDATE) |
|---|---|---|
| 同一 (kbId, sourceType, title) 二次 ingest | DELETE 旧行 + INSERT 新行 → FK 引用短暂断裂,触发器可能漏更新 | UPDATE 现有行 → FK 不变,触发器正常 |
| `knowledge_item_fts` 索引 | REPLACE 触发 DELETE + INSERT 两次 FTS 同步 | UPDATE 触发一次 FTS 同步 |
| `KnowledgeFragmentEntity.fragmentId` FK | 若 FK 是 `ON DELETE CASCADE` → 关联 fragment 删了又重建(浪费) | FK 不动,fragment 关联稳定 |
| 磁盘写放大 | DELETE + INSERT = 2 次写 | UPDATE = 1 次写 |

### 4.4 风险

- **代码改动小**：纯 DAO + 1 个 call site 改写
- **不破坏业务**：REPLACE 与 IGNORE+UPDATE 的**可观察**区别是 FK 行为,如果业务依赖 REPLACE 的 DELETE 行为（不太可能）会出问题

### 4.5 验收

- 单测：调 `insertOrIgnore` 两次,断言第二次 rowId = -1,数据被 `update` 覆盖
- 集成：ingest 同一 source 两次,断言 `KnowledgeFragmentEntity` 行数不变（不被删了重建）
- 真机：看 `dumpsys dbinfo` 的 `knowledge_item` 表大小,二次 ingest 后应略增,不是 2 倍增

---

## 5. PR-M3：canonical name alias 表（C3 修复）

### 5.1 目标

把"Apple" / "Apple Inc." / "苹果公司" 归并到 1 个 entity。

### 5.2 改动

**新表** `entity_alias`：

```kotlin
@Entity(tableName = "entity_alias")
data class EntityAliasEntity(
    @PrimaryKey val canonicalName: String,   // "Apple"
    val aliases: String,                       // JSON: ["Apple Inc.", "苹果公司", "Apple 公司"]
    val knowledgeBaseId: String,
    val updatedAt: Long
)
```

`WikiPageCompiler.compile()` 改：

```kotlin
fun resolveCanonicalName(name: String, kbId: String): String {
    val aliases = db.entityAliasDao().getByKb(kbId)
    return aliases.firstOrNull { it.matches(name) }?.canonicalName ?: name
}
// compile() 入口对每个 entity.name / concept.name 跑一次
```

**alias 怎么来**：
- 方案 A：人工 —— 设置页让用户手动"合并为..."
- 方案 B：LLM 自动 —— 在 `runGenerationTask` 末尾加一个 LLM 步骤,问"这批 entity names 是否有别名,合并建议..."
- 方案 C：启发式 —— edit distance ≤ 2 自动合并（高风险,误合并多）

### 5.3 风险

- **alias 表本身需要维护**：用户合并两次相同的实体要 idempotent
- **跨 KB 不共享**：每个 KB 独立 alias 表
- **回退成本高**：alias 错了,可能要回滚 N 个 entity 名字 → 现有 wikilink 全部失效

### 5.4 推荐度

- ⭐️ **等用户报"重名条目多"再做**
- 当前 4 个 PR 中性价比最低

---

## 6. PR-M4：强制 LLM 提"老实体的新描述"（C4 修复）

### 6.1 目标

ingest 新源时,把 KB 里**已存在**的 entity names 喂给 LLM,要求它在新源里重新提一次这些实体的描述,然后 union 进现有页。

### 6.2 改动

`IngestOrchestrator.runGenerationTask` 入口加：

```kotlin
val existingEntityNames = db.knowledgeItemDao()
    .getEntityNamesByKb(kbId)   // SELECT title FROM knowledge_item WHERE kbId=? AND sourceType='wiki_entity'
val promptWithAnchors = generationPrompt + """

[强制回顾] 以下实体已存在于知识库,即使本批新源没有显式提到,
也请基于全文重新生成一段对它们的描述(写"无新内容"也行):
${existingEntityNames.joinToString("\n") { "- $it" }}
"""
```

回写时,新 prompt 让 LLM 输出 `{existing_name: new_description}` map,代码侧 union 到现有 entity 页的 `## 概述` 段。

### 6.3 风险

- **token 增多**：KB 大了 entity names 列表能上千,即使只取名字也耗 token
- **prompt 污染**：强制 LLM 提"无新内容"会让它乱写,需要校验
- **可观测性差**：用户不知道这次 ingest 是不是"老实体被更新了"

### 6.4 推荐度

- ⭐️ **等 KB 长大 + 用户希望"老实体越来越详细"再做**
- 当前 4 个 PR 中投入产出比最低

---

## 7. 验证策略

### 7.1 手动回归（最直观）

1. 准备 KB-A,只有 1 个源 → 实体 X 描述为"A"
2. 导入源 2,LLM 给 X 描述为"B"
3. **看 X 页**：
   - 现状：`## 概述` 是"B",A 丢了
   - M1 改后：`## 概述` 含 A + B 两段
4. 导入源 3,LLM 给 X **不**返回 X
5. **看 X 页**：
   - 现状：A + B 保留（M1 已经做对）
   - C4 仍存在 —— M4 修复

### 7.2 设备覆盖

- 不需要多设备,纯逻辑回归 + 数据库回归

### 7.3 自动测试

- `WikiPageCompilerMergeTest`（新）：覆盖 5 种 merge 场景
  - 同段同内容 → 输出唯一
  - 同段不同内容 → 并存
  - 不同段名 → 都保留
  - 段名别名 → 合并
  - frontmatter 数组 → union
- `KnowledgeItemDaoTest`：INSERT OR IGNORE + UPDATE 行为

---

## 8. 风险与回退

| 风险 | 触发 | 回退 |
|---|---|---|
| M1 段名别名漏配 | source2 用"## 相关概念" → 重复段 | 加 `SECTION_ALIASES` 表,允许运行时扩展 |
| M1 free-text 段("## 概述")追加后变臃肿 | 用户看到 "A \n\n B \n\n A 改 B" 重复内容 | 加 dedup:同段内 hash 等价就不重复追加 |
| M2 INSERT OR IGNORE 漏掉 race condition | 多个 ingest 进程并发 | 已用 `withWikiPageWriteLocks` 串行化,影响小 |
| M2 UPDATE 时 `createdAt` 被新值覆盖 | call site 要保留 `existingPage?.createdAt` | 单测覆盖 |
| M3 alias 误合并 | "腾讯" 和 "腾讯云" 被合并 | 加"合并前预览"UI 让用户确认 |
| M4 LLM 写"无新内容"刷屏 | LLM 偷懒 | prompt 加"如果原文中确实没有,请写[N/A],不要写无新内容" |

---

## 9. 关联

- **RELIAB-1** — 合并操作要走 IngestRuntime 协程,不受黑屏影响
- **ARCH-7 / ARCH-7.1** — M4 如果做,要先精简 prompt 输入侧,避免 token 暴涨
- **THREAD-1** — entity/concept 合并是脉络演化的输入,合并质量影响脉络
- **`WikiPageCompiler.kt:193-205`** — `compileEntityAndConceptPages` 注释提到"buggy ingest path that hard-coded `entitiesJson = "[]"`" —— 这条历史 bug 正是 C1 的祖先

---

## 10. 决策记录

- **2026-06-05 立项**：用户在 office-hours 报"实体/概念消失"
- **2026-06-05 落定方向**：
  - **不**做 alias 表（M3）或 LLM 强制回顾（M4）——投入大,体感未必好
  - **必做** M1（段级 union,影响最大）+ M2（DAO IGNORE+UPDATE,正确性）
  - M3 / M4 留作 P2,等用户具体体感痛点再上
- **未决项**：
  - M1 的"## 概述"段是"追加"还是"取最新"？建议追加（保守,数据零丢失）；UI 端可考虑折叠"历史描述"
  - M1 SECTION_ALIASES 是 hardcode 还是建表？建议 hardcode（KISS,改起来方便）
  - M2 是改 `KnowledgeItemDao.insert` 一个方法,还是新增 `insertOrIgnore` 不动 `insert`？建议新增,保持向后兼容
  - M1 + M2 是分开 PR 还是合一？建议分开（M1 算法改动,M2 DAO 改动,独立验收）
