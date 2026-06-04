package com.my.knowledge.data.ai

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * P0-2 review cleanup: collapsed 4 try/catch error ladders in [AiGateway]
 * into a single classifier. Returns a Chinese-friendly error string
 * suitable for surfacing in the UI or persisting in
 * `processing_task.errorMessage`.
 *
 * The 3 "String-returning" call sites (`callApi`, `completeStreamObserved`,
 * `streamJsonObserved`) all had byte-identical catch ladders before this
 * refactor; keeping them in one place is the only way to guarantee
 * error-message parity when categories grow (e.g. add 415 / 451 next).
 *
 * CancellationException is intentionally NOT handled here: it must
 * propagate as-is so the parent scope's `Job.cancel()` is honored.
 * The call sites throw it back up unchanged via Kotlin's catch order.
 */
internal fun Throwable.toAiErrorMessage(baseUrl: String): String = when (this) {
    is UnknownHostException ->
        "[DNS 失败] 无法解析 $baseUrl 中的主机名，请检查 Base URL 或网络。"
    is ConnectException ->
        "[连接失败] 无法连接到 $baseUrl，请检查网络和 Base URL 配置。"
    is SocketTimeoutException ->
        "[超时] AI 服务 5 分钟内未返回结果，请稍后重试或减小输入长度。"
    is SSLException ->
        "[SSL 错误] 与 $baseUrl 的 TLS 握手失败：${localizedMessage ?: "未知"}"
    is RetryableRemoteCallException ->
        "[AI 调用异常] ${localizedMessage ?: "远端请求失败"}"
    else ->
        "[AI 调用异常] ${localizedMessage ?: "未知错误"}"
}

/**
 * P0-2: image-analysis surfaces failures as [IllegalStateException]
 * (the orchestrator catches it and writes a [com.my.knowledge.data.db.entity.ReviewItemEntity]
 * so the user sees the failure on the "中间处理数据" screen) and prefixes
 * "图片分析" so the user immediately knows which subsystem failed.
 * Reuses the same classification ladder as [toAiErrorMessage].
 */
internal fun Throwable.toImageAnalysisException(baseUrl: String): IllegalStateException = when (this) {
    is UnknownHostException ->
        IllegalStateException("图片分析 DNS 失败：无法解析 $baseUrl", this)
    is ConnectException ->
        IllegalStateException("图片分析连接失败：无法连接到 $baseUrl", this)
    is SocketTimeoutException ->
        IllegalStateException("图片分析超时：AI 服务 5 分钟内未返回结果", this)
    is SSLException ->
        IllegalStateException("图片分析 SSL 错误：${localizedMessage ?: "未知"}", this)
    is RetryableRemoteCallException ->
        IllegalStateException(localizedMessage ?: "图片分析远端请求失败", this)
    else ->
        IllegalStateException(localizedMessage ?: "图片分析未知错误", this)
}
