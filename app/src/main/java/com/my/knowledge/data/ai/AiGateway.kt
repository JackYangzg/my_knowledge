package com.my.knowledge.data.ai

import com.my.knowledge.ui.KnowledgeManager
import com.my.knowledge.ui.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

interface AiProvider {
    suspend fun chat(prompt: String, context: String): String
    suspend fun complete(systemPrompt: String, userMessage: String): String
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
                put("max_tokens", JsonPrimitive(2048))
                put("temperature", JsonPrimitive(temperature))
            }

            val url = URL("${config.baseUrl}/chat/completions")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                connection.doOutput = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000

                val bodyBytes = requestBody.toString().toByteArray(Charsets.UTF_8)
                connection.outputStream.use { it.write(bodyBytes) }

                val responseCode = connection.responseCode
                val responseText = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    return@withContext "[AI 调用失败] HTTP $responseCode: $errorText"
                }

                parseChatResponse(responseText)
            } finally {
                connection.disconnect()
            }
        } catch (e: java.net.ConnectException) {
            "[连接失败] 无法连接到 ${config.baseUrl}，请检查网络和 Base URL 配置。"
        } catch (e: java.net.SocketTimeoutException) {
            "[超时] AI 服务响应超时，请稍后重试。"
        } catch (e: Exception) {
            "[AI 调用异常] ${e.localizedMessage ?: "未知错误"}"
        }
    }

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
}
