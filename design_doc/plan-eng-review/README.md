# 工程评审输出（plan-eng-review）

> 走查整个业务流程（数据/AI/持久化层），定位问题并输出改进建议。
> 本目录与 `design_doc/plan-design-review/`（UI/UX 评审）并列，覆盖范围互补。

## 评审范围

- **数据/AI/持久化层**：`IngestOrchestrator`、`AiGateway`、`KnowledgeRepositoryImpl`、`AppDatabase`、DAOs
- **后台任务编排**：`IngestRuntime` + `IngestWorker` + `ProcessingTaskScheduler` + `RebuildDebouncer` + 各类 `*Worker`
- **领域模型与策略**：`IngestQueuePolicy`、Markdown 语义分块器、`LongSourceCheckpointStore`、Knowledge Graph 重建
- **测试覆盖现状**：9 个单元测试 + 1 个迁移测试

**不在范围**（已在 `plan-design-review` 中评审）：UI/UX、Compose 组件、Material 3 主题、屏幕布局、交互流。

## 文档索引

| 文件 | 内容 |
| --- | --- |
| [00-执行摘要.md](./00-执行摘要.md) | TL;DR + 严重度分级 + Top 10 风险 |
| [01-业务流程全景.md](./01-业务流程全景.md) | 4 阶段摄入管线、知识库生命周期、Ask 流程的 ASCII 数据流图 |
| [02-架构评审.md](./02-架构评审.md) | 模块边界、并发模型、状态机、依赖图；6 个架构问题 |
| [03-代码质量评审.md](./03-代码质量评审.md) | DRY 违规、错误处理、不可变性、可测性；10 个代码问题 |
| [04-测试覆盖评审.md](./04-测试覆盖评审.md) | 已覆盖/未覆盖矩阵、P0 缺口、推荐测试策略 |
| [05-性能与并发评审.md](./05-性能与并发评审.md) | N+1、内存、并发、I/O 瓶颈；6 个性能问题 |
| [06-改进路线图.md](./06-改进路线图.md) | 按 ROI 排序的行动清单（P0 / P1 / P2） |
| [07-GSTACK评审报告.md](./07-GSTACK评审报告.md) | GSTACK 格式的正式评审结论与签字位 |

## 一句话总结

**架构方向正确（4 阶段管线 + 4 轨并行 + 3 道去抖 + 3 道可恢复检查点已落实），代码质量与技术债已积累到一个临界点：`IngestOrchestrator.kt` 2793 行是单点风险，`KnowledgeRepositoryImpl.kt` 1671 行 / 18 个 DAO 是耦合过载信号。** 建议在未来 2 个迭代内将两个"上帝类"按职责拆为 4-5 个聚焦组件，测试覆盖率从当前约 20% 提升到 60%+（P0 路径），然后再考虑新功能。

## 严重度速查

- **P0（必须修，影响生产安全/正确性）**：2 项
- **P1（应修，影响可维护性/可测性）**：7 项
- **P2（可延后，影响技术债/长期演进）**：6 项

详细列表见 [00-执行摘要.md](./00-执行摘要.md)。
