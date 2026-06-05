package com.my.knowledge.data.ai

import android.util.Base64
import com.my.knowledge.ui.KnowledgeManager
import com.my.knowledge.ui.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

internal class RetryableRemoteCallException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class AiRetryEvent(
    val attempt: Int,
    val maxAttempts: Int,
    val delayMs: Long,
    val errorType: String,
    val message: String,
)

interface AiProvider {
    suspend fun chat(prompt: String, context: String): String
    suspend fun complete(systemPrompt: String, userMessage: String): String
    suspend fun analyzeImage(imageBytes: ByteArray, mimeType: String, title: String, ocrText: String): String
    fun completeStream(systemPrompt: String, userMessage: String): Flow<String>
    suspend fun chatJson(
        systemPrompt: String,
        userPrompt: String,
        schemaHint: String,
        temperature: Float = 0.2f
    ): String

    /**
     * P0-2: JSON-mode streaming variant of [chatJson]. Walks the
     * `/chat/completions` SSE stream, accumulates every token chunk
     * in-memory, and returns the concatenated text after
     * `cleanModelOutput`. The contract is identical to [chatJson]
     * (same schemaHint suffixing, same temperature default, same
     * "[配置缺失]" short-circuit when the API key is blank) but the
     * underlying request is `stream: true` so callers that pipe
     * `onChunk` into a throttled progress writer see real token
     * progress instead of one giant read-until-EOF.
     *
     * The implementation is cooperative-cancellation aware: every
     * SSE line reads `coroutineContext.ensureActive()` first, so a
     * `cancel()` from a parent job tears the HTTP connection down
     * within the next read instead of waiting out the read
     * timeout.
     *
     * Errors are NOT silently swallowed: a mid-stream EOF, an HTTP
     * 4xx/5xx, or a connection failure surfaces as an exception the
     * caller can route to the orchestrator's retry path. The legacy
     * [completeStream] keeps its "emit a [错误] string and continue"
     * behaviour for back-compat; the new path is strict by design.
     *
     * @param onChunk invoked once per SSE delta. Defaults to no-op so
     *   the spec-mandated `(systemPrompt, userPrompt, schemaHint,
     *   temperature)` 4-arg signature still resolves for callers
     *   that don't care about progress.
     */
    suspend fun streamJson(
        systemPrompt: String,
        userPrompt: String,
        schemaHint: String,
        temperature: Float = 0.2f,
        onChunk: (String) -> Unit = {}
    ): String
}

class AiGateway(
    /**
     * Indirection over [KnowledgeManager.modelConfig] so JVM unit
     * tests can plug in a fixture without touching Android
     * `SharedPreferences`. The default delegates to the production
     * global; production wiring never sets it.
     */
    private val configProvider: () -> ModelConfig = { KnowledgeManager.modelConfig }
) : AiProvider {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * PERF-11: cap concurrent LLM HTTP calls to
     * [MAX_CONCURRENT_LLM_CALLS] = 2. The orchestrator runs 4
     * parallel ingest lanes (parse / analysis / generation /
     * embedding), and the LLM-backed lanes would each open their
     * own SSE connection without throttling — exhausting the
     * upstream provider's rate limit and the device's socket
     * pool. Fair Semaphore so requests are FIFO instead of
     * last-acquirer-wins (avoids one lane starving another).
     *
     * This is the lowest-level choke point: every public method
     * that hits the network (`chat` / `complete` /
     * `chatJson` / `streamJson` / `analyzeImage` plus the
     * `*Observed` variants) funnels through one of the three
     * private helpers wrapped below. So a single Semaphore
     * field covers the whole surface.
     */
    private val concurrencyLimiter = Semaphore(MAX_CONCURRENT_LLM_CALLS)

    override suspend fun chat(prompt: String, context: String): String {
        val config = configProvider()
        if (config.apiKey.isBlank()) {
            return "[配置缺失] 请在设置中配置 API Key。"
        }

        return callApi(config, null, """
系统: 你是一个知识管理助手。请基于用户提供的上下文回答问题。
上下文: $context
用户: $prompt
请回答:""".trimIndent())
    }

    override suspend fun complete(systemPrompt: String, userMessage: String): String {
        val config = configProvider()
        if (config.apiKey.isBlank()) {
            return "[配置缺失] 请在设置中配置 API Key。"
        }

        return callApi(config, systemPrompt, userMessage)
    }

    suspend fun completeObserved(
        systemPrompt: String,
        userMessage: String,
        temperature: Float = 0.7f,
        maxAttempts: Int = MAX_REMOTE_ATTEMPTS,
        onRetry: suspend (AiRetryEvent) -> Unit = {},
    ): String {
        val config = configProvider()
        if (config.apiKey.isBlank()) {
            return "[配置缺失] 请在设置中配置 API Key。"
        }

        return callApi(
            config = config,
            systemPrompt = systemPrompt,
            userMessage = userMessage,
            temperature = temperature,
            maxAttempts = maxAttempts,
            onRetry = onRetry,
        )
    }

    suspend fun completeStreamObserved(
        systemPrompt: String,
        userMessage: String,
        temperature: Float = 0.7f,
        maxAttempts: Int = MAX_REMOTE_ATTEMPTS,
        onRetry: suspend (AiRetryEvent) -> Unit = {},
        onChunk: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val config = configProvider()
        if (config.apiKey.isBlank()) {
            return@withContext "[配置缺失] 请在设置中配置 API Key。"
        }

        try {
            retryRemoteCall(maxAttempts, onRetry) {
                val accumulator = StringBuilder()
                streamSseOnce(
                    config = config,
                    systemPrompt = systemPrompt,
                    userMessage = userMessage,
                    temperature = temperature,
                    onDelta = { delta ->
                        accumulator.append(delta)
                        onChunk(delta)
                    },
                )
                val raw = accumulator.toString()
                if (raw.isBlank()) {
                    throw RetryableRemoteCallException("远端流式响应为空")
                }
                val cleaned = with(AiTextCleaner) { raw.cleanModelOutput() }
                if (cleaned.isBlank() && raw.isNotBlank()) {
                    "[AI 调用失败] 模型仅返回了思考过程，未给出实际内容。"
                } else {
                    cleaned
                }
            }
        } catch (e: Throwable) {
            return@withContext e.toAiErrorMessage(config.baseUrl)
        }
    }

    override fun completeStream(systemPrompt: String, userMessage: String): Flow<String> = flow {
        val config = configProvider()
        if (config.apiKey.isBlank()) {
            emit("[配置缺失] 请在设置中配置 API Key。")
            return@flow
        }

        try {
            streamSseOnce(
                config = config,
                systemPrompt = systemPrompt,
                userMessage = userMessage,
                temperature = 0.7f,
                onDelta = { delta -> this@flow.emit(delta) },
            )
        } catch (e: CancellationException) {
            // Cooperative cancellation: re-throw so the parent scope
            // sees the cancel cause. Swallowing this would leak
            // the connection until readTimeout.
            throw e
        } catch (e: java.net.ConnectException) {
            emit("[连接失败] 无法连接到 ${config.baseUrl}，请检查网络和 Base URL 配置。")
        } catch (e: java.net.SocketTimeoutException) {
            emit("[超时] AI 服务 5 分钟内未返回结果，请稍后重试或减小输入长度。")
        } catch (e: Exception) {
            emit("[AI 调用异常] ${e.localizedMessage ?: "未知错误"}")
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun chatJson(
        systemPrompt: String,
        userPrompt: String,
        schemaHint: String,
        temperature: Float
    ): String {
        val config = configProvider()
        if (config.apiKey.isBlank()) return ""
        return callApi(
            config = config,
            systemPrompt = "$systemPrompt\n\n只输出严格 JSON，不要 Markdown，不要解释。\nSchema:\n$schemaHint",
            userMessage = userPrompt,
            temperature = temperature
        )
    }

    suspend fun chatJsonObserved(
        systemPrompt: String,
        userPrompt: String,
        schemaHint: String,
        temperature: Float = 0.2f,
        maxAttempts: Int = MAX_REMOTE_ATTEMPTS,
        onRetry: suspend (AiRetryEvent) -> Unit = {},
    ): String {
        val config = configProvider()
        if (config.apiKey.isBlank()) return ""
        return callApi(
            config = config,
            systemPrompt = "$systemPrompt\n\n只输出严格 JSON，不要 Markdown，不要解释。\nSchema:\n$schemaHint",
            userMessage = userPrompt,
            temperature = temperature,
            maxAttempts = maxAttempts,
            onRetry = onRetry,
        )
    }

    override suspend fun streamJson(
        systemPrompt: String,
        userPrompt: String,
        schemaHint: String,
        temperature: Float,
        onChunk: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val config = configProvider()
        if (config.apiKey.isBlank()) return@withContext ""
        // Same suffix the non-streaming `chatJson` appends — keeping
        // the two paths' prompt shape identical is what makes
        // "streamJson is equivalent to chatJson" a true statement.
        val effectiveSystem = "$systemPrompt\n\n只输出严格 JSON，不要 Markdown，不要解释。\nSchema:\n$schemaHint"
        val accumulator = StringBuilder()
        // JSON-mode streaming MUST buffer the full body before
        // parsing — a partial JSON document is unrecoverable, and
        // calling `cleanModelOutput` on a half-formed string would
        // leave dangling `<think>` half-tags. So we accumulate
        // everything and only apply text cleaning at the end.
        streamSseOnce(
            config = config,
            systemPrompt = effectiveSystem,
            userMessage = userPrompt,
            temperature = temperature,
            onDelta = { delta ->
                accumulator.append(delta)
                onChunk(delta)
            },
        )
        val raw = accumulator.toString()
        val cleaned = with(AiTextCleaner) { raw.cleanModelOutput() }
        // Mirror chatJson's "thinking-only" guard so the orchestrator
        // sees the same failure shape (an error string starting with
        // `[`) regardless of which entry point it called.
        if (cleaned.isBlank() && raw.isNotBlank()) {
            return@withContext "[AI 调用失败] 模型仅返回了思考过程，未给出实际内容。"
        }
        cleaned
    }

    suspend fun streamJsonObserved(
        systemPrompt: String,
        userPrompt: String,
        schemaHint: String,
        temperature: Float = 0.2f,
        maxAttempts: Int = MAX_REMOTE_ATTEMPTS,
        onRetry: suspend (AiRetryEvent) -> Unit = {},
        onChunk: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val config = configProvider()
        if (config.apiKey.isBlank()) return@withContext ""
        val effectiveSystem = "$systemPrompt\n\n只输出严格 JSON，不要 Markdown，不要解释。\nSchema:\n$schemaHint"
        try {
            retryRemoteCall(maxAttempts, onRetry) {
                val accumulator = StringBuilder()
                streamSseOnce(
                    config = config,
                    systemPrompt = effectiveSystem,
                    userMessage = userPrompt,
                    temperature = temperature,
                    onDelta = { delta ->
                        accumulator.append(delta)
                        onChunk(delta)
                    },
                )
                val raw = accumulator.toString()
                if (raw.isBlank()) {
                    throw RetryableRemoteCallException("远端流式 JSON 响应为空")
                }
                val cleaned = with(AiTextCleaner) { raw.cleanModelOutput() }
                if (cleaned.isBlank() && raw.isNotBlank()) {
                    "[AI 调用失败] 模型仅返回了思考过程，未给出实际内容。"
                } else {
                    cleaned
                }
            }
        } catch (e: Throwable) {
            return@withContext e.toAiErrorMessage(config.baseUrl)
        }
    }

    suspend fun analyze(prompt: String): String {
        val config = configProvider()
        if (config.apiKey.isBlank()) {
            return ""
        }
        return callApi(config, null, prompt)
    }

    override suspend fun analyzeImage(
        imageBytes: ByteArray,
        mimeType: String,
        title: String,
        ocrText: String
    ): String = concurrencyLimiter.withPermit {
        withContext(Dispatchers.IO) {
            val config = configProvider()
            val apiKey = config.imageAnalysisApiKey.trim()
            val baseUrl = config.imageAnalysisBaseUrl.trim()
            if (baseUrl.isBlank()) {
                throw IllegalStateException("图片分析 Base URL 未配置")
            }
            if (apiKey.isBlank()) {
                throw IllegalStateException("图片分析 API Key 未配置")
            }

            val prompt = buildImageAnalysisPrompt(title, ocrText)
            val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val messages = buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(prompt))
                        })
                        add(buildJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            put("image_url", buildJsonObject {
                                put("url", JsonPrimitive("data:$mimeType;base64,$imageBase64"))
                            })
                        })
                    })
                })
            }
            val requestBody = buildJsonObject {
                put("model", JsonPrimitive(config.modelName))
                put("messages", messages)
                put("max_tokens", JsonPrimitive(1024))
                put("temperature", JsonPrimitive(0))
            }

            try {
                retryRemoteCall {
                    analyzeImageOnce(baseUrl, apiKey, requestBody.toString())
                }
            } catch (e: Throwable) {
                throw e.toImageAnalysisException(baseUrl)
            }
        }
    }

    suspend fun isAvailable(): Boolean {
        return configProvider().apiKey.isNotBlank()
    }

    private suspend fun callApi(
        config: ModelConfig,
        systemPrompt: String?,
        userMessage: String,
        temperature: Float = 0.7f,
        maxAttempts: Int = MAX_REMOTE_ATTEMPTS,
        onRetry: suspend (AiRetryEvent) -> Unit = {},
    ): String = concurrencyLimiter.withPermit {
        withContext(Dispatchers.IO) {
            try {
                retryRemoteCall(maxAttempts, onRetry) {
                    callApiOnce(config, systemPrompt, userMessage, temperature)
                }
            } catch (e: Throwable) {
                e.toAiErrorMessage(config.baseUrl)
            }
        }
    }

    private fun callApiOnce(
        config: ModelConfig,
        systemPrompt: String?,
        userMessage: String,
        temperature: Float
    ): String {
        val messages = buildJsonArray {
            if (systemPrompt != null) {
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(systemPrompt))
                })
            }
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(userMessage))
            })
        }

        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(config.modelName))
            put("messages", messages)
            put("max_tokens", JsonPrimitive(8192))
            put("temperature", JsonPrimitive(temperature))
            // ARCH-8 §2.1: 嵌套 reasoning.effort 字段
            // (MiniMax /v1/responses 规范,枚举 none/minimal/low/medium/high)
            put("reasoning", buildJsonObject {
                put("effort", JsonPrimitive(config.reasoningEffort.apiValue))
            })
        }

        val url = URL("${config.baseUrl.trimEnd('/')}/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = AI_READ_TIMEOUT_MS

            val bodyBytes = requestBody.toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(bodyBytes) }

            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                val classified = classifyHttpError(responseCode, errorText)
                if (responseCode.isRetryableHttpStatus()) {
                    throw RetryableRemoteCallException(classified)
                }
                return classified
            }

            val parsed = parseChatResponse(responseText)
            val cleaned = with(AiTextCleaner) { parsed.cleanModelOutput() }
            if (cleaned.startsWith("[API 错误]")) return cleaned
            if (cleaned.isBlank() && parsed.isNotBlank()) {
                return "[AI 调用失败] 模型仅返回了思考过程，未给出实际内容。"
            }
            return cleaned
        } finally {
            connection.disconnect()
        }
    }

    /**
     * P0-2: shared SSE stream reader used by both [completeStream]
     * (Flow wrapper) and [streamJson] (buffered). Opens
     * `POST /chat/completions` with `stream: true`, walks the SSE
     * `data: ...` lines, and invokes [onDelta] for every non-blank
     * delta extracted by [parseStreamDelta].
     *
     * Cooperative cancellation: `coroutineContext.ensureActive()` is
     * called before every `readLine()` so a parent `Job.cancel()` is
     * observed within the latency of the next read, not the
     * `readTimeout`. The connection is `disconnect()`ed in `finally`
     * so a cancelled reader doesn't leave a half-open socket waiting
     * on the server.
     *
     * Errors propagate as exceptions (no silent empty-string
     * returns). [completeStream] catches them and emits a `[错误]`
     * string for back-compat with the pre-P0-2 chat UI; the new
     * [streamJson] path lets them bubble up to the orchestrator's
     * retry handler.
     */
    private suspend fun streamSseOnce(
        config: ModelConfig,
        systemPrompt: String,
        userMessage: String,
        temperature: Float,
        onDelta: suspend (String) -> Unit,
    ) = concurrencyLimiter.withPermit {
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(config.modelName))
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("system"))
                        put("content", JsonPrimitive(systemPrompt))
                    })
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive(userMessage))
                    })
                },
            )
            put("max_tokens", JsonPrimitive(8192))
            put("temperature", JsonPrimitive(temperature))
            put("stream", JsonPrimitive(true))
            // ARCH-8 §2.1: 嵌套 reasoning.effort 字段
            // (MiniMax /v1/responses 规范,枚举 none/minimal/low/medium/high)
            put("reasoning", buildJsonObject {
                put("effort", JsonPrimitive(config.reasoningEffort.apiValue))
            })
        }

        val url = URL("${config.baseUrl.trimEnd('/')}/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = AI_READ_TIMEOUT_MS
            connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                val classified = classifyHttpError(responseCode, errorText)
                if (responseCode.isRetryableHttpStatus()) {
                    throw RetryableRemoteCallException(classified)
                }
                // Non-retryable: surface the classified error to the
                // caller. The orchestrator's exception path will
                // mark the task failed (or, if it's a "shouldFail
                // immediately" case, bubble it to the user as a
                // review item).
                throw IllegalStateException(classified)
            }

            // Manual read loop instead of `useLines { forEach }`:
            // Sequence.forEach doesn't check coroutine cancellation,
            // which would mean a cancelled read spins until the
            // timeout. ensureActive() is the cooperative-cancel hook
            // that lets cancel() tear the connection down within the
            // next read latency.
            val reader = connection.inputStream.bufferedReader()
            reader.use { r ->
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val line = r.readLine() ?: break
                    val payload = line.trim().removePrefix("data:").trim()
                    if (payload.isBlank()) continue
                    if (payload == "[DONE]") break
                    val delta = parseStreamDelta(payload)
                    if (!delta.isNullOrBlank()) onDelta(delta)
                }
            }
        } catch (e: CancellationException) {
            // Don't wrap or transform — let the coroutine machinery
            // see the original cause for structured concurrency.
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun analyzeImageOnce(baseUrl: String, apiKey: String, requestBody: String): String {
        val url = URL("${baseUrl.trimEnd('/')}/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = AI_READ_TIMEOUT_MS
            connection.outputStream.use {
                it.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                val classified = classifyHttpError(responseCode, errorText)
                if (responseCode.isRetryableHttpStatus()) {
                    throw RetryableRemoteCallException(classified)
                }
                throw IllegalStateException(classified)
            }
            val parsed = with(AiTextCleaner) { parseChatResponse(responseText).cleanModelOutput() }
            if (parsed.isBlank() || parsed.isAiError()) {
                throw IllegalStateException(parsed.ifBlank { "图片分析接口返回空结果" })
            }
            return parsed
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun <T> retryRemoteCall(
        maxAttempts: Int = MAX_REMOTE_ATTEMPTS,
        onRetry: suspend (AiRetryEvent) -> Unit = {},
        block: suspend () -> T
    ): T {
        var last: Throwable? = null
        val attempts = maxAttempts.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                if (!e.isRetryableRemoteFailure() || attempt == attempts - 1) throw e
                last = e
                val delayMs = remoteRetryDelayMs(attempt)
                onRetry(
                    AiRetryEvent(
                        attempt = attempt + 1,
                        maxAttempts = attempts,
                        delayMs = delayMs,
                        errorType = e::class.simpleName ?: "Throwable",
                        message = e.localizedMessage ?: "远端请求失败",
                    )
                )
                delay(delayMs)
            }
        }
        throw last ?: RetryableRemoteCallException("远端请求失败")
    }

    private fun Throwable.isRetryableRemoteFailure(): Boolean =
        this is RetryableRemoteCallException ||
            this is java.net.UnknownHostException ||
            this is java.net.ConnectException ||
            this is java.net.SocketException ||
            this is java.net.SocketTimeoutException ||
            this is javax.net.ssl.SSLException

    private fun remoteRetryDelayMs(attempt: Int): Long =
        ((attempt + 1) * 10_000L).coerceAtMost(MAX_REMOTE_RETRY_DELAY_MS)

    private fun Int.isRetryableHttpStatus(): Boolean =
        this == 408 || this == 409 || this == 425 || this == 429 || this in 500..599

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val AI_READ_TIMEOUT_MS = 300_000
        const val MAX_REMOTE_ATTEMPTS = 4
        const val MAX_REMOTE_RETRY_DELAY_MS = 30_000L
        // PERF-11: hard cap on concurrent LLM HTTP calls. The
        // orchestrator runs 4 ingest lanes; without throttling
        // every lane opens its own SSE/POST connection, which
        // trips upstream rate limits and exhausts the device
        // socket pool. 2 is the sweet spot: one short lane +
        // one long-running Stage-1/Stage-2 call, with two
        // remaining lanes blocked on the semaphore.
        const val MAX_CONCURRENT_LLM_CALLS = 2
    }

    private fun classifyHttpError(code: Int, body: String): String {
        val trimmed = body.take(400)
        return when (code) {
            401 -> "[鉴权失败] API Key 无效或过期，请到设置中检查。"
            403 -> "[无权限] 该 API Key 没有访问此模型的权限。"
            404 -> "[模型未找到] 模型名称 ${configProvider().modelName} 在该 Base URL 下不可用。"
            413 -> "[请求过大] 输入超过服务端限制，请减小源文本长度。"
            429 -> "[限流] 服务端限流，请稍后重试。"
            in 500..599 -> "[服务端错误] HTTP $code，请稍后重试。详情：$trimmed"
            else -> "[AI 调用失败] HTTP $code: $trimmed"
        }
    }

    private fun buildImageAnalysisPrompt(title: String, ocrText: String): String = buildString {
        appendLine("Describe this image factually for a knowledge-base index.")
        appendLine("Include visible text verbatim, chart axes/values, diagram structure, and key visual elements.")
        appendLine("Do NOT speculate beyond what is visible. If important details are unclear, say they are unclear.")
        appendLine("Use 2 to 4 sentences. Output plain text only — no markdown, no preamble.")
        if (title.isNotBlank()) {
            appendLine()
            appendLine("Source title: $title")
        }
        if (ocrText.isNotBlank()) {
            appendLine()
            appendLine("Local OCR text that may help verification:")
            appendLine(ocrText.take(4_000))
        }
    }

    private fun String.isAiError(): Boolean =
        startsWith("[API 错误]") ||
            startsWith("[解析失败]") ||
            startsWith("[AI 调用失败]") ||
            startsWith("[鉴权失败]") ||
            startsWith("[无权限]") ||
            startsWith("[模型未找到]") ||
            startsWith("[请求过大]") ||
            startsWith("[限流]") ||
            startsWith("[服务端错误]")

    private fun parseChatResponse(responseText: String): String {
        return try {
            val responseObj = json.parseToJsonElement(responseText).jsonObject
            val choices = responseObj["choices"]?.jsonArray
            if (choices != null && choices.isNotEmpty()) {
                val message = choices[0].jsonObject["message"]?.jsonObject
                message?.get("content")?.jsonPrimitive?.content ?: responseText
            } else {
                val error = responseObj["error"]?.jsonObject
                val errorMsg = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                "[API 错误] $errorMsg"
            }
        } catch (e: Exception) {
            "[解析失败] ${e.localizedMessage ?: "无法解析 AI 响应"}"
        }
    }

    private fun parseStreamDelta(payload: String): String? {
        return try {
            val responseObj = json.parseToJsonElement(payload).jsonObject
            val choice = responseObj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val delta = choice["delta"]?.jsonObject
            val message = choice["message"]?.jsonObject
            (delta?.getString("content") ?: message?.getString("content"))
        } catch (_: Exception) {
            null
        }
    }

    private fun Map<String, JsonElement>.getString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
