package com.my.knowledge.data.ai

import android.util.Base64
import com.my.knowledge.ui.KnowledgeManager
import com.my.knowledge.ui.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
}

class AiGateway : AiProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chat(prompt: String, context: String): String {
        val config = KnowledgeManager.modelConfig
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
        val config = KnowledgeManager.modelConfig
        if (config.apiKey.isBlank()) {
            return "[配置缺失] 请在设置中配置 API Key。"
        }

        return callApi(config, systemPrompt, userMessage)
    }

    override fun completeStream(systemPrompt: String, userMessage: String): Flow<String> = flow {
        val config = KnowledgeManager.modelConfig
        if (config.apiKey.isBlank()) {
            emit("[配置缺失] 请在设置中配置 API Key。")
            return@flow
        }

        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(systemPrompt))
            })
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(userMessage))
            })
        }

        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(config.modelName))
            put("messages", messages)
            put("max_tokens", JsonPrimitive(4096))
            put("temperature", JsonPrimitive(0.7f))
            put("stream", JsonPrimitive(true))
        }

        val url = URL("${config.baseUrl.trimEnd('/')}/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                emit("[AI 调用失败] HTTP $responseCode: $errorText")
                return@flow
            }

            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val payload = line.trim().removePrefix("data:").trim()
                    if (payload.isBlank() || payload == "[DONE]") return@forEach
                    parseStreamDelta(payload)?.takeIf { it.isNotBlank() }?.let { emit(it) }
                }
            }
        } catch (e: java.net.ConnectException) {
            emit("[连接失败] 无法连接到 ${config.baseUrl}，请检查网络和 Base URL 配置。")
        } catch (e: java.net.SocketTimeoutException) {
            emit("[超时] AI 服务响应超时，请稍后重试。")
        } catch (e: Exception) {
            emit("[AI 调用异常] ${e.localizedMessage ?: "未知错误"}")
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun chatJson(
        systemPrompt: String,
        userPrompt: String,
        schemaHint: String,
        temperature: Float
    ): String {
        val config = KnowledgeManager.modelConfig
        if (config.apiKey.isBlank()) return ""
        return callApi(
            config = config,
            systemPrompt = "$systemPrompt\n\n只输出严格 JSON，不要 Markdown，不要解释。\nSchema:\n$schemaHint",
            userMessage = userPrompt,
            temperature = temperature
        )
    }

    suspend fun analyze(prompt: String): String {
        val config = KnowledgeManager.modelConfig
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
    ): String = withContext(Dispatchers.IO) {
        val config = KnowledgeManager.modelConfig
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
            val url = URL("${baseUrl.trimEnd('/')}/chat/completions")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.doOutput = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.outputStream.use {
                    it.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val responseText = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    throw IllegalStateException(classifyHttpError(responseCode, errorText))
                }
                val parsed = with(AiTextCleaner) { parseChatResponse(responseText).cleanModelOutput() }
                if (parsed.isBlank() || parsed.isAiError()) {
                    throw IllegalStateException(parsed.ifBlank { "图片分析接口返回空结果" })
                }
                parsed
            } finally {
                connection.disconnect()
            }
        } catch (e: java.net.UnknownHostException) {
            throw IllegalStateException("图片分析 DNS 失败：无法解析 $baseUrl", e)
        } catch (e: java.net.ConnectException) {
            throw IllegalStateException("图片分析连接失败：无法连接到 $baseUrl", e)
        } catch (e: java.net.SocketTimeoutException) {
            throw IllegalStateException("图片分析超时：服务响应超过 120 秒", e)
        } catch (e: javax.net.ssl.SSLException) {
            throw IllegalStateException("图片分析 SSL 错误：${e.localizedMessage ?: "未知"}", e)
        }
    }

    suspend fun isAvailable(): Boolean {
        return KnowledgeManager.modelConfig.apiKey.isNotBlank()
    }

    private suspend fun callApi(
        config: ModelConfig,
        systemPrompt: String?,
        userMessage: String,
        temperature: Float = 0.7f
    ): String = withContext(Dispatchers.IO) {
        try {
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
            }

            val url = URL("${config.baseUrl.trimEnd('/')}/chat/completions")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                connection.doOutput = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 300_000

                val bodyBytes = requestBody.toString().toByteArray(Charsets.UTF_8)
                connection.outputStream.use { it.write(bodyBytes) }

                val responseCode = connection.responseCode
                val responseText = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    return@withContext classifyHttpError(responseCode, errorText)
                }

                val parsed = parseChatResponse(responseText)
                // Diagnose "reasoning-only" outputs: many models emit a long
                // thinking block but no real content. Surface that as an error
                // instead of silently returning empty / truncated text.
                if (parsed.startsWith("[API 错误]")) return@withContext parsed
                parsed
            } finally {
                connection.disconnect()
            }
        } catch (e: java.net.UnknownHostException) {
            "[DNS 失败] 无法解析 ${config.baseUrl} 中的主机名，请检查 Base URL 或网络。"
        } catch (e: java.net.ConnectException) {
            "[连接失败] 无法连接到 ${config.baseUrl}，请检查网络和 Base URL 配置。"
        } catch (e: java.net.SocketTimeoutException) {
            "[超时] AI 服务响应超时（5 分钟）。请检查模型速度或减小输入长度。"
        } catch (e: javax.net.ssl.SSLException) {
            "[SSL 错误] 与 ${config.baseUrl} 的 TLS 握手失败：${e.localizedMessage ?: "未知"}"
        } catch (e: Exception) {
            "[AI 调用异常] ${e.localizedMessage ?: "未知错误"}"
        }
    }

    private fun classifyHttpError(code: Int, body: String): String {
        val trimmed = body.take(400)
        return when (code) {
            401 -> "[鉴权失败] API Key 无效或过期，请到设置中检查。"
            403 -> "[无权限] 该 API Key 没有访问此模型的权限。"
            404 -> "[模型未找到] 模型名称 ${KnowledgeManager.modelConfig.modelName} 在该 Base URL 下不可用。"
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
