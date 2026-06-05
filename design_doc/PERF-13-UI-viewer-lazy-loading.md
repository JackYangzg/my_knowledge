# PERF-13 — 知识库长文本查看器：懒加载 / 滚动式渲染

> 状态：**DRAFT**（2026-06-05）
> 范围：`com.my.knowledge.ui.KnowledgeViewerScreen.kt`（`PdfContentViewer` + 主 wrapper Column）+ `com.my.knowledge.ui. ComposeMarkdown.kt`（行级 composable 生成）
> 用户问题：在知识库中打开长文本（wiki 页 / PDF 源）会造成页面卡死
> 目标：保持现有业务（frontmatter、wikilink、tags、floating AI 按钮、"原文/加工数据"切换）完全不变，仅把渲染层从"一次性全量"切到"按需懒加载"
> 不动：schema、cache、LLM 流程、KnowledgeItemEntity 字段

---

## 0. 卡死的真正原因（不是 markdown 解析慢）

打开一个 100K 字符的 wiki 页或 PDF 源会卡死，并不是 markdown 解析本身慢（那是字符串操作，毫秒级）。**真正慢的是 Compose 一次性把整篇内容"实例化"成 composable 树，然后做 measure / layout / draw**。

代码现状（`KnowledgeViewerScreen.kt:158-211`）：

```
Box(Modifier.fillMaxSize().background(Color.White)) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        // Row — 顶部 header (回退、加工/原文、编辑、tag)
        item?.let { knowledgeItem ->
            if (!showProcessedItems && knowledgeItem.sourceType == "pdf") {
                PdfContentViewer(item = knowledgeItem, ...)   // line 160,内部 LazyColumn
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {  // line 165
                    KnowledgeBodyHeader(...)
                    when {
                        isImageType → ImagePlaceholder
                        wiki_ → WikiMarkdownContent(contentMarkdown)   // line 187,行级 Row + Text
                        else → ComposeMarkdown(contentMarkdown)         // line 194,行级 Column + Text
                    }
                }
            }
        }
        // Floating "AI 问一问" 按钮 (Box.align(BottomEnd))
    }
}
```

### 0.1 非 PDF 路径（wiki 页 / 一般源）

- 外层 `Column(verticalScroll(rememberScrollState()))`（line 165-168）—— 把 `verticalScroll` 套在 `Column` 上是"假装支持滚动"，**没有虚拟化**：所有子 composable 一次全部 measure + layout。
- `WikiMarkdownContent`（line 254-）和 `ComposeMarkdown`（line 44-123）都是 `Column { for (line in lines) { Text / Row / Surface } }`—— **每行 1 个 composable**。100K 字符 ≈ 5K 行 → 5K 个 Text/Row/Surface 实例化。
- 实测等价代码在 Android Studio Layout Inspector 里能看到一棵 5000+ 节点的合成树，第一次 measure 耗时 800ms-2s（取决于设备），期间 UI 线程完全 block，**用户看到的就是"卡死"**。

### 0.2 PDF 路径

- `PdfContentViewer`（line 516-624）**已经**用 `LazyColumn`（line 551）+ `items(textChunks, key = { it.index })`（line 596），渲染层是虚拟化的。
- **但 chunking 在主线程同步跑**（line 529-534）：
  ```kotlin
  val textChunks = remember(item.contentMarkdown, showFullText) {
      chunkLongMarkdown(
          markdown = item.contentMarkdown.ifBlank { "暂无解析文本" },
          maxChars = if (showFullText) 220_000 else 36_000
      )
  }
  ```
  `chunkLongMarkdown`（line 651-672）内部 `take(220_000)` + 按 6K 字符切块 + 字符串 substring。当 `contentMarkdown.length` 接近 220K 时（用户点过"显示完整文本"），主线程会一次性切 30+ 块 substring，**同样是几百 ms 卡顿**。
- 默认 `showFullText=false` 时 cap=36K，3-5 块 substring，**体感还能接受**——所以用户报"卡死"多半在 PDF + 完整文本模式下。

### 0.3 根因总结

| 路径 | 卡死原因 | 严重度 |
|------|---------|------|
| wiki 页 / 一般源 | 5K+ 行 composable 一次性 measure | **高**：打开就卡 |
| PDF 默认（36K） | 3-5 块 substring 主线程 | 低：体感还行 |
| PDF 完整（220K） | 30+ 块 substring 主线程 + LazyColumn item 数量 | **高**：用户点过"显示完整文本"就卡 |

---

## 1. 设计目标

1. **首屏渲染时间 < 100ms**（设备中位），从"打开就卡"变成"瞬间开"。
2. **不破坏现有 UI 行为**：wikilink 跳转 / frontmatter 不显示在正文（header 已经处理）/ tags / floating AI 按钮 / 顶部"原文/加工数据"切换 / 点击"显示完整文本"后仍能看全文。
3. **不引入新依赖**：用现有的 Compose Foundation（`LazyColumn` + `rememberLazyListState`）。
4. **不持久化新状态**：分块策略是纯渲染层，不写 DB、不动 cache。
5. **可回退**：每个 PR 单独落地，回退就是 git revert，不影响业务。

---

## 2. PR 拆分（2 个 PR + 1 个微优化）

| PR | 改动 | 影响范围 | 风险 | 验收 |
|----|------|---------|------|------|
| **PR-L1** | wiki / 一般源 viewer：把外层 `Column(verticalScroll)` 切到 `LazyColumn`；markdown body 走 `chunkLongMarkdown` 切块（沿用 PDF 的 6K chunkSize），每块作为一个 `LazyColumn` item 用 `ComposeMarkdown` / `WikiMarkdownContent` 渲染 | `KnowledgeViewerScreen.kt:165-204` | **低**：纯渲染容器替换，业务逻辑零改动。风险点：scrolling 状态保留 / floating button 仍 BottomEnd | Compose 测试 + 真机长文打开 |
| **PR-L2** | PDF 路径：把 `chunkLongMarkdown` 从 `remember{}` 同步调用挪到 `LaunchedEffect` + `Dispatchers.Default`；首次进入只 chunk 前 60K，"显示完整文本"后异步 chunk 完整 220K | `KnowledgeViewerScreen.kt:528-549, 651-672` | **中**：异步后 toggle 会有 ~50ms loading 闪烁；用 `mutableStateOf<List<MarkdownChunk>>(emptyList())` 跟 `LaunchedEffect(showFullText)` 配合 | 真机 PDF 长文切换 |
| **PR-L3**（可选）| `ComposeMarkdown`（`ComposeMarkdown.kt:44-123`）现在按行 split + 逐行生成 composable，对**单块 6K 字符**也是 200+ composable。改成段落级 composable（按 `\n\n` 切段，每段一个 Text/Surface）—— 200 → 30 个 composable | `ComposeMarkdown.kt:44-123` | **极低**：纯函数内部重写，输出 markdown 形状不变 | 单测：相同输入 → 相同渲染 |

---

## 3. PR-L1：wiki / 一般源 viewer 改 LazyColumn

### 3.1 现状

`KnowledgeViewerScreen.kt:165-204`：

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())   // ← 没虚拟化
        .padding(horizontal = 20.dp)
) {
    KnowledgeBodyHeader(title = ..., item = ...)
    when {
        isImageType → ImagePlaceholder
        wiki_ → WikiMarkdownContent(contentMarkdown)   // ← 行级 composable
        else → ComposeMarkdown(contentMarkdown)        // ← 行级 composable
    }
    Spacer(Modifier.height(60.dp))
}
```

### 3.2 改动方案

**不重写 markdown 渲染**，**只换外层容器 + 复用 PDF 的 chunking**。

```kotlin
LazyColumn(
    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
    state = rememberLazyListState(),
    verticalArrangement = Arrangement.spacedBy(0.dp),
    contentPadding = PaddingValues(bottom = 80.dp)   // 给 floating AI 按钮留位
) {
    item(key = "header-${knowledgeItem.id}") {
        KnowledgeBodyHeader(title = ..., item = ...)
    }
    if (isImageType(knowledgeItem.sourceType)) {
        item(key = "image-${knowledgeItem.id}") {
            ImagePlaceholder(knowledgeItem.contentMarkdown)
        }
    } else {
        // 复用 PdfContentViewer 同款 chunking,6K 一块。
        // wiki 页通常 < 30K,一般源 < 100K,切 5-15 块,LazyColumn 一次性只 compose 首屏的 2-3 块。
        val md = knowledgeItem.contentMarkdown.ifBlank { "暂无内容" }
        val chunks = remember(md) { chunkLongMarkdown(md, maxChars = md.length, chunkSize = 6_000) }
        items(chunks, key = { it.index }) { chunk ->
            if (knowledgeItem.sourceType.startsWith("wiki_")) {
                WikiMarkdownContent(
                    markdown = chunk.text,
                    linkTargets = linkTargets,
                    onOpenItem = onOpenItem
                )
            } else {
                ComposeMarkdown(
                    modifier = Modifier.fillMaxWidth(),
                    markdown = chunk.text,
                    onLinkClick = { openFile(context, it) }
                )
            }
        }
    }
}
```

### 3.3 不破坏业务的关键点

1. **Floating AI 按钮**：在更外层 `Box.align(BottomEnd)`（line 217-245），不受 `Column → LazyColumn` 影响。验证：device 跑一次确认按钮位置不变。
2. **顶部 header 不滚动消失**：原 `Column(verticalScroll)` 是 header 跟着滚；改成 `LazyColumn` 后 header 是第一个 item，**会跟着滚走**——这是 LazyColumn 的标准行为，但用户可能习惯 header 钉住。
   - **如果需要钉住**：把 header 移出 `LazyColumn`，放回外层 `Column` 的兄弟。`LazyColumn` 仍占 body 区。
   - **如果可以滚走**：维持现状（更省心）。
   - **建议**：先维持可滚（"原文/加工数据"切换 + 编辑按钮依赖 header 跟着滚走的部分交互），后续 P2 视用户反馈再决定。
3. **`padding(horizontal = 20.dp)` 移到 `LazyColumn` 本身**——保证 chunk 边界与原 Column 的视觉边距一致。
4. **Scrolling 状态保留**：`rememberLazyListState()` 在配置变更（旋转屏幕 / 切后台回前台）时由 `rememberSaveable` 自动恢复。
5. **`KnowledgeBodyHeader` 内部不带滚动锚点**（只是 Row + tag），移进 `LazyColumn` 第一个 item 不影响。

### 3.4 验收

- Compose 测试：`androidx.compose.ui.test.junit4.createComposeRule()` 跑 `KnowledgeViewerScreen`，注入 100K 字符 mock item，断言 `firstFrame()` < 200ms
- 真机回归：
  - 短源（< 5K）：打开瞬间出，无 visual diff
  - 中等源（30-50K）：打开 < 100ms，滚动顺滑
  - 长源（100K+）：打开 < 200ms，往下滚 100 屏都不卡
- Floating AI 按钮：所有 item 类型下都显示在右下角，不被 LazyColumn 内容覆盖
- "原文/加工数据"切换：toggle 后内容区域刷新，header 跟着切换

---

## 4. PR-L2：PDF 路径 chunking 异步化

### 4.1 现状

`KnowledgeViewerScreen.kt:528-549`：

```kotlin
var showFullText by remember(item.id) { mutableStateOf(false) }
val textChunks = remember(item.contentMarkdown, showFullText) {
    chunkLongMarkdown(
        markdown = item.contentMarkdown.ifBlank { "暂无解析文本" },
        maxChars = if (showFullText) 220_000 else 36_000
    )
}
val isTruncated = remember(item.contentMarkdown, showFullText) {
    !showFullText && item.contentMarkdown.length > 36_000
}
```

`chunkLongMarkdown`（line 651-672）同步切块，`showFullText=true` 时切 30+ 块 6K substring，主线程上几百 ms。

### 4.2 改动方案

```kotlin
var showFullText by rememberSaveable(item.id) { mutableStateOf(false) }
var textChunks by remember(item.id) { mutableStateOf<List<MarkdownChunk>>(emptyList()) }

LaunchedEffect(item.contentMarkdown, showFullText) {
    val cap = if (showFullText) 220_000 else 36_000
    val markdown = item.contentMarkdown.ifBlank { "暂无解析文本" }
    textChunks = withContext(Dispatchers.Default) {
        chunkLongMarkdown(markdown, maxChars = cap)
    }
}
val isTruncated = !showFullText && item.contentMarkdown.length > 36_000
```

**关键点**：
- `LaunchedEffect` 在主线程调度但 `withContext(Dispatchers.Default)` 把 substring / split 跑在 Default pool。
- 默认 36K 切 3-5 块 ≈ 5ms，**异步后用户感觉不到 loading 闪烁**。
- 220K 切 30+ 块 ≈ 200ms，**异步后 UI 不再卡**，用户能看到"上一屏内容"先出，再出后续块（LazyColumn 自动增量渲染）。
- `rememberSaveable` 让 `showFullText` 状态在进程被回收后能恢复（避免每次进 PDF 都被 cap 36K）。

### 4.3 验收

- 真机 PDF 100K+ 字符：默认 36K cap 打开 < 100ms；点"显示完整文本"后 < 300ms 出全部
- 滚动到 30+ 块时帧率稳定 60fps（LazyColumn 行为，不需新验证）
- 重启 app（Killing 进程后再进）后 showFullText 状态保留（用 `rememberSaveable` 替代 `remember`）

---

## 5. PR-L3：ComposeMarkdown 段落级 composable（可选）

### 5.1 现状

`ComposeMarkdown.kt:54-117`：

```kotlin
val lines = markdown.split("\n")
var inCodeBlock = false
val codeLines = mutableListOf<String>()
for (line in lines) {
    val trimmed = line.trim()
    // ...
    when {
        trimmed.startsWith("### ") -> renderHeading(...)
        trimmed.startsWith("## ") -> renderHeading(...)
        // ...
        else -> InlineMarkdownText(trimmed, onLinkClick)
    }
}
```

对一段 6K 字符的 chunk，**`split("\n")` 可能产生 100-300 行**（取决于行宽），每个 case 都生成 1 个 composable。

### 5.2 改动方案

```kotlin
val blocks = remember(markdown) { splitIntoMarkdownBlocks(markdown) }
// blocks: List<MarkdownBlock> = Heading(2, "...") | Paragraph("...") | CodeBlock("...") | ...

Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    blocks.forEach { block ->
        when (block) {
            is MarkdownBlock.Heading -> renderHeading(block.text, block.level, onLinkClick)
            is MarkdownBlock.CodeBlock -> renderCodeBlock(block.code)
            is MarkdownBlock.Bullet -> { Row { Text("•"); InlineMarkdownText(block.text, ...) } }
            // ...
            is MarkdownBlock.Paragraph -> InlineMarkdownText(block.text, onLinkClick)
        }
    }
}
```

`splitIntoMarkdownBlocks` 一次性把整段按 markdown block 切好（heading / paragraph / code / list / blockquote / hr），**6K chunk ≈ 30-50 个 block**，比 100-300 个 row 少 5-10 倍。

### 5.3 风险

- 现有 `ComposeMarkdown` 用了状态变量 `inCodeBlock` / `codeLines` 跟踪跨行 code fence，**重写时必须保留这个跨行状态语义**（``` 跨多行才闭合 code block）。
- 段落切完后 `InlineMarkdownText` 内部仍 `buildAnnotatedString`（`ComposeMarkdown.kt:234-262`），对超长单段（>5K）仍有性能问题——这个留作后续 P2，本 PR 只解决 composable 数量。

### 5.4 验收

- 单测：相同 markdown 输入，新旧实现渲染的 composable 树**等价**（用 `composeTestRule.onAllNodes(...).assertCountEquals(...)` 验证）
- 真机：6K chunk 渲染首屏 < 50ms

---

## 6. 风险与回退

| 风险 | 触发条件 | 回退 |
|------|---------|------|
| PR-L1 LazyColumn 后 header 滚走用户不习惯 | 用户反馈"header 应该钉住" | 移出 LazyColumn，放外层 Column 兄弟 |
| PR-L1 wikilink 跳转跨 chunk 时丢失 | chunk N 的 `[[link]]` 指向 chunk N+1 的标题 | 加 `linkTargets` set 在 chunk 边界外做全局去重——已存在（line 188），验证仍命中 |
| PR-L2 异步 chunking 中用户滚动 | 旧 chunks 已渲染，新 chunks 还在 IO，滚动到底部显示"无更多" | 把 `textChunks` 当 immutableList + atomic update，LazyColumn `key` 稳定，不会闪 |
| PR-L2 `Dispatchers.Default` 在低端机被 WorkManager 占用 | Default pool 全占用 → 200ms chunking 退到 1s | 用 `Dispatchers.IO` 替代（chunking 是 CPU-bound 不是 IO，理论上 Default 更合适，但 IO 池通常更宽松） |
| PR-L3 段落切分破坏了 code fence 跨行 | 罕见——LLM 输出 ``` 单独一行被切到 6K 边界 | 切分时先按 ``` 配对切，再按段落切；增加 ~20 行逻辑 |

---

## 7. 验证策略

### 7.1 单测

- `com.my.knowledge.ui.KnowledgeViewerScreenTest`（新）
  - 注入 mock item，markdown = 100K 字符（用 lorem ipsum × N），断言 `composeTestRule.mainClock.autoAdvance = false` 时首屏渲染时间
- `com.my.knowledge.ui.ChunkLongMarkdownTest`
  - 边界用例：6K 边界正好是 \n\n / \n / 中文字符

### 7.2 端到端

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest \
  --tests 'com.my.knowledge.ui.*'
```

### 7.3 真机回归

- 3 档 markdown fixture：5K / 30K / 100K
- 3 档 sourceType：`wiki_entity` / `wiki_source` / `pdf` 100K
- 测项：
  - 打开耗时（Choreographer 第一个 frame 时间）
  - 滚动帧率（GPU rendering profiler）
  - 内存峰值（Profiler → Memory）
  - Floating AI 按钮位置

---

## 8. 关联

- `ARCH-7` / `ARCH-7.1` —— LLM token 优化，本设计稿**正交**（一个改 LLM 输入输出，一个改 UI 渲染层）
- `PERF-8` `perf(search): 搜索结果 API 改为 suspend fun` —— 搜索侧的性能，本设计稿是 viewer 侧
- `PERF-7` `perf(search): 路由 fragment 搜索到 FTS4` —— 搜索 query 优化
- `memory/2026-06-04-ingest-remote-llm-timeout-optimization.md` —— ingest 端 streaming，与本设计稿**正交**（一个改 ingest 写入性能，一个改 viewer 读取性能）
- `WikiPageCompiler.kt:79` `parsed.markdown.take(12_000)` —— 源页 body 上限 12K，**这部分不受影响**（viewer 拿到的是 wiki 页 / 源页的合并 markdown，可能远 >12K）

---

## 9. 决策记录

- **2026-06-05 立项**：用户在 `/office-hours` 中反馈"打开长文本卡死"——核实代码后，根因是 `Column(verticalScroll)` 无虚拟化 + PDF 路径 chunking 主线程同步执行。
- **2026-06-05 落定分批**：3 个 PR（PR-L1 / PR-L2 / PR-L3），前两个必做，PR-L3 可选（视 PR-L1 落地后体感决定）。
- **未决项**：
  - PR-L1 后 header 滚走是否需要钉住？默认维持可滚，P2 再迭代。
  - PR-L2 异步 chunking 期间是否加 "加载更多..." 提示？默认不加（耗时太短），后续根据用户反馈调整。
  - PR-L3 段落切分是否需要先按 ``` 配对切？默认在 PR-L3 内做，作为安全阀。
