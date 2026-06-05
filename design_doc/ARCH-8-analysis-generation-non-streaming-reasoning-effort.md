# Design: ARCH-8 — Analysis/Generation 非流式 + Reasoning Effort

> 状态：**APPROVED**（2026-06-05 `/office-hours` 评审通过）
> 范围：`com.my.knowledge.data.ai.AiGateway` / `ModelConfig` / `com.my.knowledge.data.ingest.IngestOrchestrator` 中 analysis + generation 调用路径
> 对应 issue：CQ(成本) / PERF(延迟)
> 落地分批：单 PR
> 副本(源稿)：`~/.gstack/projects/JackYangzg-my_knowledge/yangzhiguo-main-design-20260605-180920.md`
> 参考：https://platform.minimaxi.com/docs/api-reference/responses-input-tokens

---

## 0. 背景与目标

`/office-hours` 会话中由用户提出两个改动（MiniMax-M2.7 在会话中撤回，默认 model name 仍为 `MiniMax-M3`）：

1. **analysis 和 generation 阶段不再使用流式输出**——单请求响应即可，不需要 per-token 进度推送。`SseProgressThrottler` + `streamJsonWithThrottledProgress` 这套通路在 analysis/generation 路径下不再需要。
2. **请求模型时增加 reasoning effort 控制，模型设置中可配 `reasoning.effort` 字段**——按 MiniMax `/v1/responses` API（参考 https://platform.minimaxi.com/docs/api-reference/responses-input-tokens），`reasoning.effort` 是思考强度档位，枚举值 `none` / `minimal` / `low` / `medium` / `high`，不传则采用 adaptive 模式（由模型自动判断）。

两个改动一起看，coherent 的故事是：M3 是 reasoning model（先思考再回答），单请求响应下流式没价值；reasoning effort 给用户在 UI 层提供档位选择（low 节省成本、high 提升质量），按 KB 或按导入场景可调。

**测量口径**：ARCH-7 设计稿 §7.3 提到的 `IngestOrchestrator.kt:1404, 1435` 的运行时日志会从"流式 JSON 返回 N 字符"变成"非流式 JSON 返回 N 字符"，并新增 `reasoningEffort=medium` 字段。

---

## 1. 当前状态（as-is）

### 1.1 模型请求路径

`AiGateway.kt:524-542` 的 `streamSseOnce` 是所有 LLM 调用的请求构造点：

```kotlin
val requestBody = buildJsonObject {
    put("model", JsonPrimitive(config.modelName))
    put("messages", buildJsonArray { ... })
    put("max_tokens", JsonPrimitive(8192))
    put("temperature", JsonPrimitive(temperature))
    put("stream", JsonPrimitive(true))   // ← 硬编码 true
}
val url = URL("${config.baseUrl.trimEnd('/')}/chat/completions")  // ← line 544
```

- 请求体里**没有 `reasoning` 字段**（按 MiniMax 文档，正确字段是嵌套 `reasoning: { effort: "..." }`）
- API endpoint 是 `/v1/chat/completions`（line 544），不是 MiniMax 文档示例中的 `/v1/responses`
- **API endpoint 是否需要从 `/chat/completions` 迁移到 `/v1/responses`**——见 §8 Open Questions

### 1.2 流式 + 节流通路

- `AiGateway.kt:517-596` 的 `streamSseOnce`：底层 SSE 读循环，写到 `onDelta` 回调
- `AiGateway.kt:309` 的 `streamJsonObserved`：`streamSseOnce` 的 JSON 包装，带 retry + onChunk
- `AiGateway.kt:162` 的 `completeStreamObserved`：`streamSseOnce` 的文本包装，带 retry + onChunk
- `IngestOrchestrator.kt:1087` 的 `collectWithThrottledProgress` + `:1140` 的 `streamJsonWithThrottledProgress` + `:1190` 的 `streamTextWithThrottledProgress`：orchestrator 层的 throttled progress 包装
- `SseProgressThrottler.kt`：独立 128 行 throttler 类（SupervisorJob + signals Flow + everyN/sample 双重门控）

### 1.3 ModelConfig

- `ui/KnowledgeManager.kt:93` 用 SharedPreferences 存 `KEY_MODEL_NAME`，默认 `"MiniMax-M3"`
- `ui/Models.kt:104` 重复硬编码默认 `"MiniMax-M3"`
- `ui/SettingsScreen.kt:76, 217` 模型名是自由文本输入框
- `ModelConfig` 类本身（`AiGateway.kt:518` 接收作为参数）字段是 `baseUrl` / `apiKey` / `modelName`——**没有 reasoning effort 字段**

### 1.4 涉及 analysis/generation 的 stage 调用点

- `AnalysisStage` → `IngestOrchestrator.runAnalysisTask`（`IngestOrchestrator.kt:575-700`）：调 `requestAiAnalysis`（line 1377-1442）和 `requestAiAnalysisLongSource`（line 1485-），都用 `streamJsonWithThrottledProgress`
- `GenerationStage` → `IngestOrchestrator.runGenerationTask` → `requestAiRawOutput`（`IngestOrchestrator.kt:1239-`）：用 `streamTextWithThrottledProgress`

### 1.5 其他 LLM stage

`LlmInspirationThreadWorker` / `SummaryWorker` / `TagWorker` / `ThreadEvolutionWorker` / `ArchiveRecommendWorker` 也调 LLM，但**本设计稿不动这些**——保留流式 + 节流通路给它们用。

### 1.6 2026-06-04 流式修复历史

`memory/2026-06-04-ingest-remote-llm-timeout-optimization.md` 第 17-21 行明确：

> "Switched ingest Stage 1 analysis and long-source chunk analysis to streaming JSON with throttled progress logs. Switched Stage 2 generation to streaming text with throttled progress logs."

流式是当时修 "Generation waited for the full non-streaming response before reporting useful token progress" 的 fix。本设计稿是它的"deliberate 简化"——理由是 M3 是 reasoning model，单请求响应下进度推送的边际价值低于复杂度成本。

---

## 2. 目标态（to-be）

### 2.1 请求体变化

`AiGateway.kt` 新增两个非流式方法（命名对齐 OpenAI 风格 `chat/completions`）：

- `chatJsonOnce(config, systemPrompt, userMessage, temperature, reasoningEffort)`：非流式 JSON 模式，返回完整 JSON 字符串
- `chatTextOnce(config, systemPrompt, userMessage, temperature, reasoningEffort)`：非流式文本模式，返回完整文本字符串

两者都把 `stream: false` 写入请求体，并加嵌套字段：

```json
{
  "model": "MiniMax-M3",
  "messages": [...],
  "max_tokens": 8192,
  "temperature": 0.3,
  "stream": false,
  "reasoning": {
    "effort": "medium"
  }
}
```

`streamSseOnce` 保留不动，给其他 LLM stage 用。

**endpoint 决策**：见 §8 Open Questions #2——是否从 `/v1/chat/completions` 迁移到 `/v1/responses`。本设计稿假设 `reasoning.effort` 字段在两个 endpoint 都支持（OpenAI 兼容 API 的常见模式），优先保留 `/v1/chat/completions`。

### 2.2 ModelConfig 变化

`ModelConfig` 加一个枚举字段：

```kotlin
enum class ReasoningEffort(val apiValue: String) {
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

data class ModelConfig(
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,                              // 默认 "MiniMax-M3"（用户已确认不改 M2.7）
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,  // 新增
)
```

`KnowledgeManager.kt:93` / `Models.kt:104` / `SettingsScreen.kt:76` 三处默认值同步更新。

`SettingsScreen.kt:217` 的"模型名称"输入框下面加一个"思考强度 (reasoning_effort)"下拉选择，默认 `medium`，选项 5 个枚举值。

### 2.3 analysis/generation 路径变化

`IngestOrchestrator.kt` 的分析/生成函数：

- `requestAiAnalysis` (line 1377-1442)：从 `streamJsonWithThrottledProgress` 换成 `ai.chatJsonObserved` 包装
- `requestAiAnalysisLongSource` (line 1485-)：同上，分块路径也用非流式
- `requestAiRawOutput` (line 1239-)：从 `streamTextWithThrottledProgress` 换成 `ai.chatTextObserved` 包装

`SseProgressThrottler` 和三个 throttled progress wrapper 在 analysis/generation 路径下被绕过——**但不删除**，因为其他 LLM stage 还在用（见 §5 Approach C 的论证）。

### 2.4 运行时日志变化

`IngestOrchestrator.kt:1404, 1435` 的 `appendLog` 文案：

- "诊断:开始请求流式 JSON，systemPrompt=N 字符, userPrompt=N 字符, schema=N 字符, readTimeout=AI_READ_TIMEOUT_MS"
- → "诊断:开始请求非流式 JSON，systemPrompt=N 字符, userPrompt=N 字符, schema=N 字符, reasoningEffort=medium, readTimeout=AI_READ_TIMEOUT_MS"

- "诊断:流式 JSON 请求完成，累计接收 N 字符，清洗后 N 字符"
- → "诊断:非流式 JSON 请求完成，返回 N 字符（reasoningEffort=medium）"

---

## 3. Premises

1. **M3 是 reasoning model，先思考再回答**——M2.7 撤回后，scope 唯一动机是这个。reasoning effort 是思考强度档位控制，默认 `medium`（中庸选择）。
2. **analysis/generation 单请求响应足够短，不需要 per-token 进度**——基于 2026-06-04 修复后的现状 + M3 是 reasoning model 的前提。
3. **其他 LLM stage（inspiration/summary/tag/threadEvolution/archiveRecommend）保留流式**——用户 directive 只点名 analysis + generation。
4. **`reasoning.effort` 字段按 MiniMax `/v1/responses` API 规范**（参考 https://platform.minimaxi.com/docs/api-reference/responses-input-tokens）——嵌套对象 `reasoning: { effort: "..." }`，枚举值 `none` / `minimal` / `low` / `medium` / `high`，不传则 adaptive。**注意与 OpenAI 官方 `reasoning_effort` 字段名不同**（OpenAI 是顶层字段 + 3 档 `low`/`medium`/`high`；MiniMax 是嵌套字段 + 5 档 `none`/`minimal`/`low`/`medium`/`high`）。
5. **SseProgressThrottler 不删除**——其他 stage 还在用；ARCH-8 完成后 throttler 的"实际用户"从 3 个 stage 降到 0 个，**这是 ARCH-9 的预演信号**。
6. **ARCH-7 已合入的 `promptVersion=ingest_analysis_v1` cache 不动**——本次改动不影响 prompt 形状，cache 命中场景不回归。
7. **`reasoning.effort` 在 `/v1/chat/completions` 和 `/v1/responses` 都支持**——本设计稿假设如此（OpenAI 兼容 API 常见模式），需要开工前用一次试请求验证。

## 4. Approaches Considered

### Approach A：退 SSE plumbing 全部

删 `SseProgressThrottler.kt`、删 `streamSseOnce`、删 `streamJsonObserved` 和 `completeStreamObserved`、删三个 throttled progress wrapper。**所有** LLM 调用都改非流式。

- Effort: M（1-2 天）
- Risk: Med
- Pros: 最简单；代码量减少 ~150 行
- Cons: 其他 LLM stage 失去进度反馈；用户 directive 只点名 analysis+generation，**超 scope**

### Approach B：Per-stage stream flag

加一个 `streaming: Boolean` 参数到 `IngestOrchestrator` 的 request 方法。`runAnalysisTask` / `runGenerationTask` 传 `false`，其他调用传 `true`。

- Effort: S（半天）
- Risk: Low
- Pros: 最小改动；不破坏其他 stage
- Cons: `streamSseOnce` 里仍 `stream: true`，需要条件化；SseProgressThrottler 需要"disabled"模式
- Cons: 流式 + 非流式两条路径共存，code smell

### Approach C：新增非流式 variant，analysis/generation 切到非流式（推荐）

`AiGateway` 加 `chatJsonObserved` / `chatTextObserved` 两个非流式方法（counterpart to `streamJsonObserved` / `completeStreamObserved`）。`IngestOrchestrator` 的 `runAnalysisTask` / `runGenerationTask` / `requestAiAnalysis*` / `requestAiRawOutput` 改调非流式变体。`SseProgressThrottler` 和三个 throttled progress wrapper 保留（其他 stage 还在用）。

- Effort: S-M（1 天）
- Risk: Low
- Pros: 不破坏其他 LLM stage；命名对称（`stream` vs `chat` 是 OpenAI 风格）；throttler 复用
- Cons: `streamSseOnce` 仍被其他 stage 间接使用，不强求删

**RECOMMENDATION**: Approach C 因为 (1) 不破坏其他 LLM stage (2) 命名对齐 OpenAI 接口 (3) 改动局限在 analysis/generation 路径，符合用户 directive 的精确范围。

## 5. Recommended Approach

采纳 Approach C：新增非流式 variant，analysis/generation 切路径。

### 5.1 实施清单

1. **新增枚举 `ReasoningEffort` + ModelConfig 加 `reasoningEffort: ReasoningEffort`**
   - 文件：`app/src/main/java/com/my/knowledge/data/ai/AiGateway.kt`
   - 同步：`ui/KnowledgeManager.kt:93`、`ui/Models.kt:104`、`ui/SettingsScreen.kt:76`
   - UI：`ui/SettingsScreen.kt:217` 下方新增"思考强度 (reasoning_effort)"下拉，默认 `MEDIUM`

2. **AiGateway 加 `chatJsonObserved` / `chatTextObserved`**
   - 文件：`app/src/main/java/com/my/knowledge/data/ai/AiGateway.kt`
   - 结构对称于 `streamJsonObserved`（line 309）/ `completeStreamObserved`（line 162）：
     - 请求体：复用 body 构造，但 `stream: false` + `reasoning: { effort: config.reasoningEffort.apiValue }`
     - 响应：一次性 `connection.inputStream.bufferedReader().readText()`，复用 `parseChatResponse` + `cleanModelOutput` 解析
     - 错误处理：复用 `classifyHttpError` + `isRetryableHttpStatus` 路径
     - retry：复用 `INGEST_AI_REMOTE_ATTEMPTS` 上限

3. **IngestOrchestrator 切路径**
   - `requestAiAnalysis`（line 1377-1442）：改调 `ai.chatJsonObserved(...)`，传 `reasoningEffort = config.reasoningEffort`
   - `requestAiAnalysisLongSource`（line 1485-）：同上，分块循环里也用非流式
   - `requestAiRawOutput`（line 1239-）：改调 `ai.chatTextObserved(...)`

4. **runtime log 文案更新**
   - `IngestOrchestrator.kt:1404, 1435` 的 `appendLog` 文案从"流式"改成"非流式"，加 `reasoningEffort=medium` 字段

5. **不动**：`SseProgressThrottler.kt`、三个 throttled progress wrapper、`streamSseOnce`、`streamJsonObserved`、`completeStreamObserved`——其他 LLM stage 仍用

### 5.2 文件改动清单

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/my/knowledge/data/ai/AiGateway.kt` | +`enum ReasoningEffort`、+`chatJsonObserved`、+`chatTextObserved`、`ModelConfig` 加 `reasoningEffort` |
| `app/src/main/java/com/my/knowledge/data/ingest/IngestOrchestrator.kt` | `requestAiAnalysis` / `requestAiAnalysisLongSource` / `requestAiRawOutput` 改调非流式；runtime log 文案更新 |
| `app/src/main/java/com/my/knowledge/ui/KnowledgeManager.kt` | 读 SharedPreferences 时给 `ModelConfig` 传 `reasoningEffort` |
| `app/src/main/java/com/my/knowledge/ui/Models.kt` | `ModelConfig` 默认值同步 |
| `app/src/main/java/com/my/knowledge/ui/SettingsScreen.kt` | 加"思考强度"下拉，默认 `MEDIUM` |

## 6. Verification

### 6.1 单元测试

- `data/ai/AiGatewayChatTest`（新增）—— `chatJsonObserved` / `chatTextObserved` 行为测试：响应解析、错误分类、retry 路径、`reasoning.effort` 字段写入
- `data/ai/ReasoningEffortTest`（新增）—— 枚举 `apiValue` 正确映射（`ReasoningEffort.MEDIUM.apiValue == "medium"`）
- `data/ai/AiPromptTemplatesTest`（已有）—— 不动
- `data/ingest/IngestOrchestrator*Test`（已有）—— 加 case：analysis/generation 调用走非流式，运行时 log 文案正确

### 6.2 端到端

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew testDebugUnitTest --tests 'com.my.knowledge.data.ai.*' --tests 'com.my.knowledge.data.ingest.*'
```

### 6.3 运行时验证

- **audit 阶段先验证 `reasoning.effort` 字段被 provider 接受**——用一次小流量试请求（不真消费 response，只看 200 vs 4xx）
- 在真机上 ingest 一篇 < 5K 字符文档，看运行时 log 是不是"非流式 JSON 请求完成，返回 N 字符（reasoningEffort=medium）"
- 在真机上 ingest 一篇 30-50K 字符长源（分块路径），验证分块是否仍正常合并
- 验证 `promptVersion=ingest_analysis_v1` 的 cache 在分析阶段仍命中（ARCH-7 §1.4 提到的 `analysisHash` 不动）
- 在 SettingsScreen 切到 `reasoningEffort=HIGH`，再 ingest 同一文档，对比 entities/concepts 数量与质量

### 6.4 regression 检查

- `LlmInspirationThreadWorker` / `SummaryWorker` / `TagWorker` / `ThreadEvolutionWorker` / `ArchiveRecommendWorker` 行为不变
- `SseProgressThrottler` 在非 analysis/generation 路径下仍工作

## 7. Risks & Mitigations

| 风险 | 触发条件 | 缓解 |
|------|---------|------|
| `reasoning.effort` 字段被 provider 拒绝 | MiniMax 实际 API 与文档不一致 | audit 阶段先做"小流量 1 次试请求"；错误响应走现有 `classifyHttpError` 路径，retry 不会无限循环 |
| 30-60s 分析任务无 progress，UI 看起来像卡死 | analysis 路径下用户看不到进度 | ARCH-7 设计稿 §7.3 的运行时 log 是诊断手段；UI 层可以在 Worker 状态显示"分析中..."文案兜底 |
| `reasoning.effort=high` 导致单次调用成本上升 | 用户默认选了 high 档 | UI 层加档位说明（high 适合复杂文档、medium 适合一般、low 适合短源）；按 KB 单独配置留作后续 |
| 流式通路被其他 stage 间接依赖，本改动意外打断 | 删错位置 | Approach C 不删任何东西；只新增非流式 variant + 改 IngestOrchestrator 的两处调用 |
| `/v1/chat/completions` 不支持 `reasoning.effort` 字段 | OpenAI 兼容实现差异 | 备选：endpoint 迁移到 `/v1/responses`（见 §8 Open Questions #2） |
| provider 不支持 `reasoning.effort`，默默忽略 | provider 兼容 OpenAI 风格但忽略未知字段 | 验收时人工核对响应质量；如被忽略，response quality 不变（不影响 correctness） |

## 8. Open Questions

1. **`reasoning.effort` 默认值**——用户先前说"默认值配置为 4096"是按数字 cap 语境；新字段是枚举值。**默认 `medium` 是否合理**？还是 `low`（节省成本）或 `high`（最高质量）？需要开工前确认。
2. **API endpoint 是否迁移 `/v1/chat/completions` → `/v1/responses`**——MiniMax 文档示例是 `/v1/responses/input_tokens`，但实际 generation endpoint 不一定相同。当前代码用 `/v1/chat/completions`（line 544）。两个 endpoint 都试请求一次，看哪个支持 `reasoning.effort` 字段。
3. **`reasoning.effort` 是否 per-KB 可配**——vs 全局默认。per-KB 配置可让用户对「深度阅读」KB 配 high、「随手摘录」KB 配 low。ARCH-8 暂定全局默认，per-KB 留作后续。
4. **SseProgressThrottler 在 architecture 维度**——本次保留；如果未来其他 stage 也切非流式，throttler 整体价值下降，需要 ARCH-9 评估是否退。

## 9. Decision Log

- **2026-06-05 开工**：`/office-hours` 会话中由用户提出 streaming off + reasoning 控制两个改动
- **2026-06-05 撤回 MiniMax-M2.7**：默认 model name 仍为 `MiniMax-M3`（字符串配置，不改 selector）
- **2026-06-05 修正动因理解**：思考预算不是"隐藏思考"而是"思考过程输出长度上限"；流式不必要因为是单请求
- **2026-06-05 落定 Approach C**：新增非流式 variant，analysis/generation 切路径，其他 stage 不动
- **2026-06-05 修正字段名**：用户指向 MiniMax 文档（https://platform.minimaxi.com/docs/api-reference/responses-input-tokens），确认正确字段是嵌套 `reasoning.effort`（不是顶层 `reasoning_effort` 也不是 `reasoning_budget`），枚举值 `none` / `minimal` / `low` / `medium` / `high`，默认 `MEDIUM`
- **2026-06-05 修正枚举值**：从 OpenAI 3 档 `low`/`medium`/`high` 修正为 MiniMax 5 档 `none`/`minimal`/`low`/`medium`/`high`
- **2026-06-05 APPROVED**：`/office-hours` 评审通过，4 个 Open Question 在实施前再单独定（默认 reasoningEffort=MEDIUM、endpoint 保留 `/v1/chat/completions` 开工时小流量试），写入仓库 `design_doc/ARCH-8-*.md`

## 10. 关联

- ARCH-7 (`design_doc/ARCH-7-analysis-llm-token-compression.md`) — 输入/输出精简，PR1 已合入
- 2026-06-04 memory (`memory/2026-06-04-ingest-remote-llm-timeout-optimization.md`) — 流式是当时修 timeout 加的，本设计稿是它的"deliberate 简化"
- ARCH-3/4/5 (`design_doc/ARCH-3-4-5-architecture-evolution.md`) — 长期架构演进，本设计稿属于短期调整
- MiniMax API 文档 (`https://platform.minimaxi.com/docs/api-reference/responses-input-tokens`) — `reasoning.effort` 字段定义来源
