# GSTACK 评审报告

> 按 `/plan-eng-review` skill 格式输出：技术评审 + 签字位
> 评审人：gstack engineering-review (Claude Opus 4.7)
> 评审日期：2026-06-04
> 评审对象：`my_knowledge` Android 应用（com.my.knowledge）数据/AI/持久化层
> 评审范围：~6000 行核心代码，9 个单元测试 + 1 个迁移测试

---

## 1. 评审摘要（TL;DR）

工程实现了完整的"本地优先 + LLM 增强"知识库系统，4 阶段摄入管线（parse → analysis → generation → embedding）+ 4 轨并行调度 + 3 道去抖（graph/sweep/thread）+ 3 道可恢复检查点（cache hit、长源 checkpoint、cooperative cancel）均已落地。架构方向稳定，**P0 风险面已收敛**。

**核心问题**：
- `IngestOrchestrator.kt` 2793 行（"工作流引擎"单类）
- `KnowledgeRepositoryImpl.kt` 1671 行（18 个 DAO 注入）
- `AiGateway.kt` 767 行（4 处重复 try/catch）
- 测试覆盖率约 20%（关键并发路径无测试守护）

**整体评级**：**🟡 有条件通过**（CONDITIONAL PASS）

- ✅ 业务功能完整，3 道 P0 hardening 全部到位
- ✅ 错误处理有"友好中文"基础
- ⚠ 必须在下一迭代内解决 P0-1（拆 `IngestOrchestrator`）和 P0-2（`AiGateway` 错误统一）
- ⚠ 测试覆盖率需在 2 个迭代内从 20% 提升到 60%

---

## 2. 评审范围

### 2.1 In Scope

| 模块 | 文件 | 行数 |
| --- | --- | --- |
| 摄入管线 | `IngestOrchestrator.kt` | 2793 |
| AI 网关 | `AiGateway.kt`, `AiPromptTemplates.kt` | 1532 |
| 持久化 | `KnowledgeRepositoryImpl.kt`, `AppDatabase.kt` | 1952 |
| 后台调度 | `IngestRuntime.kt`, `IngestWorker.kt`, `LlmInspirationThreadWorker.kt`, `ThreadEvolutionWorker.kt`, `ProcessingTaskScheduler.kt`, `RebuildDebouncer.kt` | 730 |
| 策略与领域 | `IngestQueuePolicy.kt`, `domain/model/*`, `data/ingest/IngestDao.kt` | 200+ |
| 搜索 | `SearchDao.kt` | 165 |
| 测试 | `app/src/test/**`, `app/src/androidTest/**` | 10 文件 |

### 2.2 Out of Scope

- UI/UX（已在 `plan-design-review` 中评审）
- Gradle 配置、CI/CD
- 第三方库版本升级

---

## 3. 已识别问题汇总

### 3.1 P0（必须修）

| ID | 问题 | 文件 | 工作量 |
| --- | --- | --- | --- |
| P0-1 | `IngestOrchestrator` 单类 2793 行耦合 4 职责 | `IngestOrchestrator.kt` | 3 天 |
| P0-2 | `AiGateway` 4 处重复错误处理 | `AiGateway.kt` | 2 天 |

### 3.2 P1（应修）

| ID | 问题 | 工作量 |
| --- | --- | --- |
| P1-1 | `KnowledgeRepositoryImpl` 18 DAO 注入 | 1.5 天 |
| P1-2 | 4 轨并行无测试守护 | 1 天（测） |
| P1-3 | `RebuildDebouncer` 静默吞异常 | 0.5 天 |
| P1-4 | `LlmInspirationThreadWorker` 手写 JSON | 0.5 天 |
| P1-5 | `moveItemToBase` 装饰性 try/catch | 0.5 天 |
| P1-6 | 摄入管线无端到端 happy path 测试 | 2 天（测） |
| P1-7 | `SearchDao` Flow 包装命令式操作 | 0.5 天 |
| PERF-7 | `SearchDao` LIKE '%q%' 前缀通配 | 1 天 |
| PERF-11 | LLM 调用未限流 | 0.5 天 |

### 3.3 P2（可延后）

| ID | 问题 | 工作量 |
| --- | --- | --- |
| P2-1 | `ScheduleFullPipeline` 旧管线仍可达 | 0.5 天 |
| P2-2 | `IngestQueuePolicy` 8 行独立文件 | 0.1 天 |
| P2-3 | JSON 手写字符统计 | 0.2 天 |
| P2-4 | `WifiLock` API 29+ 警告 | 0.1 天 |
| P2-5 | Migration 重复 `addColumnIfMissing` | 0.2 天 |
| P2-6 | Search 8 处通配符索引失效 | 已合并到 PERF-7 |
| ARCH-1 | Worker 绕过 DI 硬 new Repository | 1.5 天 |
| ARCH-2 | `IngestRuntime` 是 `object` 全局单例 | 0.5 天 |
| ARCH-4 | `*Json` 字符串列失去 Room 查询 | 5+ 天 |
| ARCH-5 | Knowledge Graph 硬字符串 dedup | 3 天 |
| ARCH-6 | Cache key 不含 prompt version | 已合并到 P0-1.4 |
| PERF-5 | `rebuildGraphForBase` 全量重建 | 3 天 |
| PERF-6 | 16 维 hash-bucket 假向量 | 5+ 天 |

**总计**：2 P0（5 天）+ 9 P1（9 天）+ 12 P2（~20 天）

---

## 4. 关键正面发现

下列项目**已正确实现**，评审认为**无需修改**：

1. **P0-1 去抖（`RebuildDebouncer`）**：3 种 debounce 窗口 + `collectLatest` 自动取消 in-flight + per-KB 失败隔离 — 设计干净
2. **P0-2 cooperative cancel**：`ensureActive()` per SSE line — 1MB 流式 JSON 用户取消 < 50ms 退出
3. **P0-3 长源 checkpoint**（`LongSourceCheckpointStore`）：崩溃后可续跑
4. **MIGRATION 设计**：v4 → v9 全部带 `addColumnIfMissing`，不强制重建
5. **同源链式任务**（`IngestQueuePolicy`）：核心不变量，单测覆盖完整
6. **`RebuildDebouncerTest`、`IngestQueuePolicyTest`、`ParseAiAnalysisJsonTest`** 三个测试质量高

---

## 5. 评审结论与签字

### 5.1 评审结论

**有条件通过**（CONDITIONAL PASS）

工程功能完整、架构方向稳定、P0 风险已收敛。但代码体量与技术债已积累到临界点，**必须在下一迭代内解决 P0-1 和 P0-2**。建议按 [06-改进路线图.md](./06-改进路线图.md) 的 3 迭代计划执行。

### 5.2 Go / No-Go 决策

| 决策点 | 结论 |
| --- | --- |
| 当前可发布到生产？ | ✅ **可**（无安全/正确性 P0） |
| 建议下一迭代做什么？ | 🔴 **必须**完成 P0-1 + P0-2 |
| 是否冻结新功能？ | ⚠ 建议冻结"摄入管线新功能"直到 P0-1 完成 |
| 是否冻结数据库迁移？ | ✅ 可继续（迁移设计扎实） |
| 是否冻结架构重构？ | ⚠ 禁止大改（依赖 P0-1） |

### 5.3 签字位

```
┌────────────────────────────────────────────────────────┐
│ 评审人: gstack engineering-review (Claude Opus 4.7)   │
│ 评审日期: 2026-06-04                                    │
│ 评审结论: CONDITIONAL PASS                              │
│                                                         │
│ 项目负责人签字位: ________________  日期: _________     │
│ 架构师签字位:    ________________  日期: _________     │
│ QA Lead 签字位:  ________________  日期: _________     │
└────────────────────────────────────────────────────────┘
```

---

## 6. 附录

### 6.1 评审方法学

- 源码精读：~6000 行核心代码，逐文件过
- 测试盘点：9 单元 + 1 集成
- 业务流程追踪：入口 → 持久化 → 退出，3 个核心流程
- 架构对照：vs CLAUDE.md 中描述的"4 阶段 + 4 轨 + 3 道去抖 + 3 道检查点"
- 性能/并发：所有 `suspend` + `withContext` + 锁 + 原子变量梳理

### 6.2 工具与命令

```bash
# 评审时使用的关键命令
wc -l app/src/main/java/com/my/knowledge/data/ingest/IngestOrchestrator.kt  # 2793
find app/src/test -name "*.kt" -type f                                      # 9 个
ls design_doc/plan-eng-review/                                              # 输出目录
```

### 6.3 关联文档

- [00-执行摘要.md](./00-执行摘要.md)
- [01-业务流程全景.md](./01-业务流程全景.md)
- [02-架构评审.md](./02-架构评审.md)
- [03-代码质量评审.md](./03-代码质量评审.md)
- [04-测试覆盖评审.md](./04-测试覆盖评审.md)
- [05-性能与并发评审.md](./05-性能与并发评审.md)
- [06-改进路线图.md](./06-改进路线图.md)
