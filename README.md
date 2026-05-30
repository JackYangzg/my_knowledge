# My Knowledge

个人知识管理 Android 应用 — 本地优先、AI 辅助的知识收集、加工与脉络发现系统。

## 核心理念

| 理念 | 说明 |
|------|------|
| **本地优先** | 所有数据存储在本地 Room 数据库，AI 调用默认关闭，用户主动开启后才联网 |
| **Two-Step Ingest** | 灵感/文件导入后，经 WorkManager 管道自动完成摘要 → 标签 → 归档建议 → 脉络演进的四阶段加工 |
| **知识脉络** | 系统自动分析知识库内容，提取主线、关联、缺口，生成探索建议，支持版本化演进日志 |
| **可信边界** | AI 回答自带可信标记：`【来自原文】` `【AI推理】` `【信息不足】`，用户始终知道信息源头 |

## 技术栈

| 层级 | 技术 |
|------|------|
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM (ViewModel + StateFlow) |
| 数据库 | Room + FTS4 全文搜索 |
| 后台任务 | WorkManager (CoroutineWorker) |
| AI 接入 | OpenAI 兼容 HTTP API (HttpURLConnection) |
| 序列化 | kotlinx.serialization |
| 依赖注入 | 手动 DI (DependencyProvider + ViewModelFactory) |
| 本地配置 | SharedPreferences |

## 项目结构

```
app/src/main/java/com/my/knowledge/
├── MainActivity.kt                    # 单 Activity 入口
├── BakingScreen.kt / BakingViewModel.kt  # 欢迎/引导页
├── UiState.kt                         # 通用 UI 状态封装
│
├── data/
│   ├── ai/
│   │   ├── AiGateway.kt              # OpenAI 兼容 API 网关 (AiProvider 接口)
│   │   └── AiPromptTemplates.kt      # AI Prompt 模板
│   ├── db/
│   │   ├── AppDatabase.kt            # Room 数据库定义 (11 实体, 10 DAO)
│   │   ├── dao/
│   │   │   ├── AiConversationDao.kt  # AI 对话 CRUD
│   │   │   ├── AiMessageDao.kt       # AI 消息 CRUD
│   │   │   ├── ArchiveRecommendationDao.kt  # 归档建议 DAO
│   │   │   ├── AttachmentDao.kt      # 附件 DAO
│   │   │   ├── KnowledgeBaseDao.kt   # 知识库 CRUD
│   │   │   ├── KnowledgeItemDao.kt   # 知识条目 CRUD + 分页
│   │   │   ├── KnowledgeThreadDao.kt # 知识脉络 DAO
│   │   │   ├── NoteDao.kt            # 笔记 DAO
│   │   │   ├── ProcessingTaskDao.kt  # 加工任务 DAO
│   │   │   └── SearchDao.kt          # FTS4 + LIKE 搜索
│   │   └── entity/
│   │       ├── AiEntities.kt         # AiConversationEntity, AiMessageEntity
│   │       ├── ArchiveRecommendationEntity.kt
│   │       ├── AttachmentEntity.kt
│   │       ├── KnowledgeBaseEntity.kt
│   │       ├── KnowledgeItemEntity.kt # 9 状态状态机
│   │       ├── KnowledgeItemFts.kt   # FTS4 虚拟表
│   │       ├── KnowledgeThreadEntities.kt  # Thread + ThreadLog
│   │       ├── NoteEntity.kt
│   │       └── ProcessingTaskEntity.kt
│   ├── file/LocalFileStore.kt        # 本地文件存储
│   ├── processing/
│   │   └── ProcessingTaskScheduler.kt # 加工任务调度器
│   ├── repository/
│   │   ├── KnowledgeRepositoryImpl.kt # 知识仓库实现 (8 DAO)
│   │   └── NoteRepositoryImpl.kt
│   └── search/FtsSearchEngine.kt     # FTS 搜索引擎封装
│
├── domain/
│   ├── repository/
│   │   ├── KnowledgeRepository.kt    # 知识仓库接口
│   │   └── NoteRepository.kt
│   └── usecase/
│       ├── AutoSaveNoteUseCase.kt
│       └── CreateNoteUseCase.kt
│
├── viewmodel/
│   ├── AskViewModel.kt              # AI 问答 (对话管理, 可信标记, 保存为知识)
│   ├── KnowledgeHomeViewModel.kt    # 首页知识库列表
│   ├── KnowledgeItemListViewModel.kt # 知识条目列表 + 分页 (PAGE_SIZE=3)
│   ├── KnowledgeManageViewModel.kt  # 知识库管理
│   ├── NoteEditorViewModel.kt       # 笔记编辑器
│   ├── ProcessingStatusViewModel.kt # 加工状态监控
│   ├── ProfileViewModel.kt          # 个人中心
│   └── ThreadViewModel.kt           # 知识脉络解析与展示
│
├── worker/
│   ├── ArchiveRecommendWorker.kt     # 归档建议 Worker
│   ├── KnowledgeProcessingWorker.kt  # 通用两步加工 Worker
│   ├── SummaryWorker.kt             # AI 摘要 Worker (AI 优先 + 本地回退)
│   ├── TagWorker.kt                 # AI 标签 Worker (AI 优先 + 本地回退)
│   └── ThreadEvolutionWorker.kt     # 知识脉络演进 Worker
│
└── ui/
    ├── KnowledgeApp.kt              # 顶层导航 (subPage 路由)
    ├── KnowledgeScreen.kt           # 知识库首页
    ├── KnowledgeDetailScreen.kt     # 知识条目详情 (分页导航)
    ├── KnowledgeManageScreen.kt     # 知识库管理 (创建/删除/重命名)
    ├── InspirationScreen.kt         # 灵感记录 (文件导入/图片/附件/录音)
    ├── ProcessingStatusScreen.kt    # 加工状态页 (任务卡片 + 归档建议)
    ├── ProfileScreen.kt             # 个人中心
    ├── SettingsScreen.kt            # 设置页 (AI 开关, 模型配置)
    ├── SubScreens.kt                # 知识脉络页 + 碎片整理页
    ├── KnowledgeManager.kt          # 全局配置单例 (AI 开关, 模型配置)
    ├── ViewModelProvider.kt         # ViewModel 工厂
    ├── Data.kt / Models.kt          # 数据模型与示例数据
    ├── Components.kt                # 通用 UI 组件
    ├── component/
    │   ├── KnowledgeComponents.kt   # 知识相关组件 (KnowledgeCard 等)
    │   └── Sheets.kt               # 底部弹出 (导入确认 + AI 问答)
    └── theme/
        ├── Color.kt / Theme.kt / Type.kt  # Material 3 主题
```

## 数据流

```
灵感记录 / 文件导入
        │
        ▼
   KnowledgeItemEntity (status = "unfiled")
        │
        ▼
   WorkManager 加工管道
   ┌─────────────────────────────────────┐
   │ 1. SummaryWorker  → AI/本地摘要生成   │
   │ 2. TagWorker      → AI/本地标签提取   │
   │ 3. ArchiveRecommendWorker → 归档建议  │
   │ 4. ThreadEvolutionWorker → 脉络演进   │
   └─────────────────────────────────────┘
        │
        ▼
   status = "archived"  ← 用户确认归档
        │
        ▼
   KnowledgeThread (知识脉络)
   ├── description     概述
   ├── coreQuestion    核心问题
   ├── mainlines       知识主线
   ├── relations       知识关联
   ├── gaps            知识缺口
   ├── suggestions     探索建议
   └── logs            演进日志
```

## 知识条目状态机

```
draft → unfiled → processing → processed → recommend_ready → archived
                   ↘ need_review / failed / deleted
```

## 功能清单

- **知识库管理**: 创建、重命名、删除知识库；查看知识条目列表（分页）
- **灵感编辑器**: Markdown 编辑、文件导入（图片/PDF/Word）、附件添加、录音按钮（权限请求）
- **AI 问答**: 对话管理、多轮对话、可信标记（【来自原文】【AI推理】【信息不足】）、答案保存为知识
- **加工管道**: 自动摘要生成、自动标签提取、归档建议（置信度评分）、手动重试失败任务
- **知识脉络**: 主线时间线、关联网络、缺口识别、探索建议、版本化演进日志
- **全文搜索**: FTS4 中文分词搜索 + LIKE 回退
- **本地优先**: AI 调用默认关闭，SharedPreferences 持久化开关状态
- **可配置 AI**: 支持任意 OpenAI 兼容 API（自定义 Base URL、Model Name、API Key）

## 构建与运行

**环境要求:**
- Android Studio Hedgehog (2024.1+) 或更高版本
- JDK 11+
- Android SDK 35
- Gradle 8.x

**构建步骤:**

```bash
# 克隆项目
git clone <repo-url>
cd my_knowledge

# 设置 JDK (macOS 示例)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# 构建 Debug APK
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug
```

## AI 配置

1. 打开应用 → 个人中心 → 设置
2. 开启「启用 AI 外部调用」
3. 填写模型配置：
   - **Base URL**: API 端点地址 (例如 `https://api.openai.com/v1`)
   - **Model Name**: 模型名称 (例如 `gpt-4o`, `deepseek-chat`)
   - **API Key**: 你的 API 密钥
4. 返回主界面，AI 功能即可使用

支持所有 OpenAI 兼容 API (OpenAI, DeepSeek, 通义千问, Moonshot 等)。
