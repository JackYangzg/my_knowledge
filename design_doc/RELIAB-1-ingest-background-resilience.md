# RELIAB-1 — Ingest 后台网络韧性：黑屏 / 后台不断流

> 状态：**DRAFT**（2026-06-05）
> 范围：`IngestRuntime` / `IngestRuntimeLoop` / `IngestWorker` / `AiGateway` HTTP 层
> 用户问题：手机黑屏或 app 退到后台时,LLM 请求会失败/中断。需求：**只要进程不退出,ingest 网络请求就不应被打断**
> 用户在 office-hours 中说"请确保"——本文档是方案,先写后实施

---

## 0. 结论先行

**IngestRuntime 已经是 process-lifetime 协程作用域**（`CoroutineScope(SupervisorJob() + Dispatchers.IO)`，`IngestRuntime.kt:37`）—— 这意味着 Activity 销毁、ViewModel 销毁、屏幕旋转都不影响 ingest 协程。wake/wifi lock 也在用（`IngestRuntime.kt:99-120`）。

**真正会让后台请求失败的 4 个具体点**：

| # | 缺口 | 失败表现 | 严重度 |
|---|---|---|---|
| G1 | 没有 foreground service | API 28+ 后台被 Doze 节流,长 LLM 流式请求 5-10 分钟后被掐断 | **高** |
| G2 | HTTP 用 `HttpURLConnection`(无连接池) | 每次新请求建新 TCP 握手,网络抖动放大；超时配置残缺 | **中** |
| G3 | wake/wifi lock `runCatching{}` 静默吞异常 | lock 获取失败时没有任何日志,继续裸跑 | **低** |
| G4 | 没有指导用户加电池白名单 | 国产 ROM 几分钟就杀后台进程 | **中** |

修复路径：4 个独立 PR,G1 + G2 必做,G3 + G4 顺手做。

---

## 1. 现状盘点（基于实际代码,2026-06-05）

### 1.1 协程作用域 — ✅ 已经是 process-lifetime

`IngestRuntime.kt:37`：

```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

- 这个 `scope` 是 `object IngestRuntime` 的成员,**process lifetime**,不绑 Activity/ViewModel
- 注释（line 18-27）明确说："imports keep draining while the app is merely in the background. If the process is killed, the persisted pending/running task rows plus WorkManager restart the pipeline on the next opportunity."

### 1.2 Wake / Wifi Lock — ⚠️ 用了,但静默吞异常

`IngestRuntime.kt:99-120`：

```kotlin
private suspend fun withIngestRuntimeLocks(appContext: Context, block: suspend () -> Unit) {
    val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "my_knowledge:IngestRuntime")
        ?.apply { setReferenceCounted(false) }
    val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "my_knowledge:IngestRuntimeWifi")
        ?.apply { setReferenceCounted(false) }

    try {
        runCatching { wakeLock?.acquire(INGEST_RUNTIME_LOCK_TIMEOUT_MS) }   // ← 静默吞
        runCatching { wifiLock?.acquire() }                                  // ← 静默吞
        block()
    } finally {
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
    }
}
```

**问题**：
- `runCatching{}` 把 `WakeLock` 获取失败（OEM 限制 / 电池优化 / doze）静默吞掉,`block()` 仍然跑,可能因为没有 wake lock 被 Doze 杀掉
- `INGEST_RUNTIME_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L`（6 小时）合理,但没人知道 lock 实际拿到了没
- **`PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF` 一起用是对的**,但 API 28+ `WifiManager` deprecated 了 `WIFI_MODE_FULL_HIGH_PERF`,推荐 `WifiManager.WIFI_MODE_FULL_LOW_LATENCY`(API 29+)

### 1.3 WorkManager 恢复路径 — ✅ 已经在

`IngestWorker.kt:11-18`：

```kotlin
override suspend fun doWork(): Result {
    return try {
        IngestRuntime.runOnce(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
```

+ `IngestRuntime` 注释说"persisted pending/running task rows plus WorkManager restart the pipeline on the next opportunity"——**进程被杀也能恢复**。

### 1.4 HTTP 层 — ⚠️ 用的是 `HttpURLConnection`,非 OkHttp

`AiGateway.kt:25-26`：

```kotlin
import java.net.HttpURLConnection
import java.net.URL
```

- `HttpURLConnection` 是 Java SE 旧 API,**没有连接池**,每次请求都新 TCP 握手
- **超时默认是 0（无限）**——长 LLM 流式响应 5min+ 不会主动断开,但反过来意味着上游挂掉时本地不会超时
- 没有 HTTP/2 多路复用,单连接并发=1
- **没有拦截器机制**,重试/日志/header 注入要手写

### 1.5 错误分类 + 重试 — ✅ 已经有了

`IngestErrorClassifier.kt:26-46`：

- DNS / connection reset / refused / SSL / timeout / 5xx 全部归类为可重试
- 已被 `IngestOrchestrator.shouldFailImmediately` 消费
- 上游 LLM 端点临时挂掉时,会按重试策略再试

### 1.6 已有的好习惯

- `AiGateway` 注释（line 60-66）明确说"SSE 每次 read 先 `ensureActive()`",协程取消时**主动关连接**,不会泄漏
- `MAX_CONCURRENT_LLM_CALLS` Semaphore 限流（`AiGateway.kt:116`）—— 4 个 ingest lane 不会把 socket pool 打爆

---

## 2. Android 后台行为的真相(为什么"后台失败"会发生)

| 触发条件 | Android 行为 | 是否影响 ingest |
|---|---|---|
| Activity 销毁,app 退到后台 | 进程保留,UI 暂停 | **不影响** (process-lifetime scope) |
| 屏幕黑屏 (Power off) | 屏幕关,进程保留,CPU 进入省电 | **PARTIAL_WAKE_LOCK 救一下** |
| Doze 模式 (API 24+, 屏幕关 + 静止 >30min) | 网络访问被批量到 maintenance window | **会断流** —— wake lock 救不了 |
| App Standby (API 24+, 长时间未用) | 限制网络/JobScheduler | 偶尔 |
| 国产 ROM 后台管理(华为/小米/OPPO) | 几分钟就杀进程,除非加白名单 | **会断** —— wake lock 也救不了 |
| API 28+ 后台网络限制 | 默认禁止后台长连接 | **会断** —— 需要 foreground service 提升优先级 |
| API 31+ 前台服务类型声明 | 必须声明 `dataSync` 或 `shortService` | 必须修 manifest |

**关键洞察**：
- **PARTIAL_WAKE_LOCK** 只保证 CPU 不睡,**不**保证网络不被节流
- **WIFI_MODE_FULL_HIGH_PERF** 只在拿到锁期间保证 WiFi 高性能,Doze 期间一样会被切
- 真正能扛 Doze + 国产 ROM 杀进程的只有 **Foreground Service + 通知 + 用户白名单**

---

## 3. PR 拆分（4 个,前 2 个必做）

| PR | 改动 | 风险 | 验收 |
|---|---|---|---|
| **PR-N1**（必做）| 加 `IngestForegroundService`(foreground service + 通知 + 进度更新) | 中：API 31+ 必须声明 `foregroundServiceType="dataSync"`,Android 14+ 还要 `dataSync` runtime permission;**不**当 service 启动会让进程被杀 | 锁屏后 30min,任务仍在跑 |
| **PR-N2**（必做）| HTTP 层从 `HttpURLConnection` 切到 OkHttp(连接池 + 显式超时 + interceptor 链路) | 中：streaming response 的 backpressure 行为变了,要重写 `streamSseOnce` | 长 LLM 流式 5min+ 不超时断开 |
| **PR-N3**（顺手做）| wake/wifi lock 改 Result,获取失败打 warning 日志 | 极低 | logcat 看 `wake lock failed` 时机 |
| **PR-N4**（顺手做）| 设置页加"为 ingest 加电池白名单"引导 + 检测 `isIgnoringBatteryOptimizations` | 极低 | 用户跳转电池白名单页 |

---

## 4. PR-N1：Foreground Service（最关键）

### 4.1 现状的痛

- `IngestRuntime` 是普通 `object`,没有通知
- 用户锁屏 → 几分钟内被 Doze / 国产 ROM 节流 → LLM 流式 5min 响应在第 3min 被掐 → 协程异常退出 → 走 WorkManager 重试 → 浪费 token

### 4.2 改动

**新文件** `app/src/main/java/com/my/knowledge/worker/IngestForegroundService.kt`：

```kotlin
class IngestForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendingCount = intent?.getIntExtra("pending_count", 0) ?: 0
        startForeground(
            INGEST_NOTIFICATION_ID,
            buildNotification(pendingCount),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC   // API 30+ 必须
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(pendingCount: Int): Notification {
        // 渠道:"知识库导入" — IMPORTANCE_LOW,不发声
        return NotificationCompat.Builder(this, "ingest_progress")
            .setSmallIcon(R.drawable.ic_ingest)
            .setContentTitle("正在整理知识库")
            .setContentText("剩余 $pendingCount 条")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
```

**`AndroidManifest.xml`**：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />  <!-- API 34+ -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />            <!-- API 33+ -->

<service
    android:name=".worker.IngestForegroundService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

**`IngestRuntime.kt`** 改造：
- `start()` 开头：`ContextCompat.startForegroundService(appContext, Intent(...).putExtra("pending_count", pending))`
- `runOnceInLocks` 末尾 / `cancel()` 时：`stopForeground(STOP_FOREGROUND_REMOVE)`
- 进度更新：`IngestOrchestrator.runUntilIdle` 在每个 item 完成时回写一个 `pending_count` 到 `MutableStateFlow` → service 监听后 `notificationManager.notify(id, buildNotification(...))`

### 4.3 行为变化

| 场景 | 现状 | 改后 |
|---|---|---|
| 锁屏 5min | 可能被 Doze 节流 LLM | 通知常驻,进程优先级高 |
| 锁屏 1h | 几乎肯定断流 | 持续运行（除非用户手动停止通知） |
| 国产 ROM 杀进程 | 几分钟就杀 | foreground service 大幅降低被杀概率（仍需白名单兜底） |
| 任务完成 | IngestRuntime 协程退出 | `stopForeground` 自动收起通知 |

### 4.4 风险

- **API 34+ 权限**：必须申请 `FOREGROUND_SERVICE_DATA_SYNC`,在 `MainActivity` 启动时一次性申请
- **API 33+ 通知权限**：必须申请 `POST_NOTIFICATIONS`,被拒也能跑（通知不显示而已）
- **电量审计**：Google Play 对"无意义 foreground service"敏感,要确保 service 在没任务时**真的退出**
- **Service 泄露**：`stopForeground` 必须在所有路径调用（成功 / 异常 / 取消）—— 用 `try/finally` 包

### 4.5 验收

- 锁屏 30min,logcat 看 `IngestForegroundService` 仍在 `START_STICKY`
- 任务完成 / 失败时通知消失
- 多次启动不堆叠 service（idempotent `startForeground`）

---

## 5. PR-N2：OkHttp 切换

### 5.1 现状的痛

`AiGateway.kt:25-26` 用 `HttpURLConnection`,没有连接池、没有拦截器链。

### 5.2 改动

**build.gradle.kts** 加依赖：

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")  // debug only
```

**新文件** `app/src/main/java/com/my/knowledge/data/ai/LlmHttpClient.kt`：

```kotlin
object LlmHttpClient {
    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)    // LLM streaming 5min+ 留余量
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0)                       // 整体不限,流式可能要 5-15min
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))   // 5 个连接,5min 空闲保留
            .retryOnConnectionFailure(true)       // 默认 true,显式说明
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                        else HttpLoggingInterceptor.Level.NONE
            })
            .build()
    }
}
```

**`AiGateway.streamSseOnce` 改写**：

- `URL` + `HttpURLConnection.openConnection` → `OkHttpClient.newCall(Request)` + SSE 解析
- 每次 `source.readLine` 之前 `coroutineContext.ensureActive()` 保留(已有)
- 新增：连接重试用 OkHttp interceptor,而不是手写 catch

### 5.3 行为变化

| 指标 | 现状 (HttpURLConnection) | 改后 (OkHttp) |
|---|---|---|
| 连接复用 | 0（每次新 TCP） | 5 个连接池 |
| TCP 握手耗时 | 50-200ms/次 | 0（复用） |
| 长流式 read 超时 | 默认 0（无限,挂死等到 OS 杀） | 10min（可控） |
| 日志 | 手写 `Log.d` | 拦截器统一 |
| 重试连接失败 | 手写 `retryRemoteCall` | 内置 `retryOnConnectionFailure(true)` |

### 5.4 风险

- **SSE 解析逻辑改写**：现有 `streamSseOnce` 处理 `data: ...\n\n` 分隔,要保证改写后行为一致（每行 1 个 delta,空行 flush）
- **OkHttp 依赖 +300KB**：可接受,这是 Android 主流
- **OkHttp 与 proxy / intercept 配置**：默认走系统 DNS,如果 LLM endpoint 在墙外要单独配 DNS,这个**不在本 PR 范围**

### 5.5 验收

- `InspirationThreadPromptTest` 等 5 个 LLM 相关单测**全部通过**(输入输出不变)
- 真机长 LLM 流式 8min 不超时
- Network Inspector 看连接数从 1 个 = 长连接,变 N 个 LLM lane 共用 5 个池

---

## 6. PR-N3：Wake / Wifi Lock 加日志

### 6.1 改动（最小）

`IngestRuntime.kt:99-120` 的 `runCatching{}` 改为：

```kotlin
val wakeLockResult = runCatching { wakeLock?.acquire(INGEST_RUNTIME_LOCK_TIMEOUT_MS) }
wakeLockResult.onFailure { Log.w("IngestRuntime", "wake lock failed: ${it.message}") }
```

`wifiLock` 同理。

**新增字段**（轻量,无 schema 变化）：

```kotlin
val lockStatus: StateFlow<LockStatus> = ...
data class LockStatus(val wakeLockHeld: Boolean, val wifiLockHeld: Boolean, val since: Long)
```

UI 端可显示"电池优化可能影响 ingest"提示。

### 6.2 验收

- logcat 出现 `IngestRuntime wake lock failed` 时机
- 强制关 wake lock（开发者选项）后,日志能反映

---

## 7. PR-N4：电池白名单引导

### 7.1 改动

**新文件** `app/src/main/java/com/my/knowledge/ui/BatteryOptimizationPrompt.kt`：

- 检测 `PowerManager.isIgnoringBatteryOptimizations(pkg)`
- 没加白名单 → 设置页加红色 banner:"为避免后台 ingest 被杀,请加电池白名单"
- 点击 → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 跳转

### 7.2 验收

- 用户加白名单后,banner 消失
- 跳过 banner 不影响 ingest 运行

---

## 8. 验证策略

### 8.1 手动回归（最关键）

1. 准备一个 ≥ 20 条未 ingest 的知识库
2. 启动 ingest,**立即锁屏**
3. 观察 30min：
   - logcat 仍有 LLM 请求/响应
   - 通知"正在整理知识库"持续显示,数字递减
   - ingest 进度条在解锁后看到的是真实进度（不是停在某处）
4. 同样流程测 1h、3h

### 8.2 设备覆盖

- Pixel（原生 Android）—— Doze 行为标准
- 华为 / 小米 / OPPO —— 国产 ROM 杀进程模式
- API 28 / 31 / 34 各一台 —— foreground service 权限差异

### 8.3 自动测试

- `LlmHttpClientTest`：mock WebServer 测超时/重试/连接池
- `IngestForegroundServiceTest`：用 `ServiceTestRule` 验证 start/stop 行为

---

## 9. 风险与回退

| 风险 | 触发 | 回退 |
|---|---|---|
| N1 foreground service 被 Google Play 拒 | 通知一直显示但任务已完成 | PR 内必须 `stopForeground(STOP_FOREGROUND_REMOVE)` 三处保证（成功/异常/取消）|
| N1 API 34 权限被拒 | 用户拒了 `POST_NOTIFICATIONS` | 通知不显示但 service 仍跑；任务能完成 |
| N2 OkHttp 改写 SSE 解析时漏 case | LLM 偶尔返回 `data: [DONE]` | 单测覆盖 SSE 4 种格式：正常 / 多行 / DONE / 中断 |
| N2 升级到 OkHttp 5.x 破坏 API | OkHttp 5 是 kotlinx 协程版 | 暂用 4.12.0,等 5.x GA 再切 |
| N3 wake lock 日志打太多 | 失败率高的设备刷屏 | 加 throttle：同分钟内同类日志只 1 次 |
| N4 跳转白名单页被国产 ROM 拦截 | 部分 ROM 不响应 Intent | 加 fallback：跳到应用详情页 |

---

## 10. 关联

- **PERF-11** —— `MAX_CONCURRENT_LLM_CALLS` 限流,与本设计正交（一个管并发,一个管持续性）
- **ARCH-7 / ARCH-7.1** —— LLM token 精简：N2 改 OkHttp 不影响 prompt 内容
- **THREAD-1** —— 脉络演化：thread 演化不在前台 service 内,但共用 `IngestRuntime` 调度

---

## 11. 决策记录

- **2026-06-05 立项**：用户在 office-hours 报"黑屏/后台 ingest 中断"
- **2026-06-05 落定方向**：
  - **不**重写 `IngestRuntime` 协程作用域（已经是 process-lifetime,不要动）
  - **必做** N1（foreground service）+ N2（OkHttp）
  - **顺手做** N3（日志）+ N4（白名单引导）
  - **不做** 长任务用 JobScheduler 替代 WorkManager（已有恢复路径,不值得再换）
  - **不做** 把 ingest 拆成"前台立刻跑 + 后台分批跑"两段（增加复杂度,现在不是问题）
- **未决项**：
  - N1 notification 文字是否要让用户自定义？现在写死"正在整理知识库",后续可放设置
  - N2 选 OkHttp 4.12.0 还是 5.x？建议 4.12.0（5.x 还在 RC,生产不稳）
  - N4 是设置页 banner 还是启动时弹窗？建议 banner（不打断用户）
  - PR-N1 / N2 是分开 PR 还是合一？建议分开（独立验收,失败可单独回退）
