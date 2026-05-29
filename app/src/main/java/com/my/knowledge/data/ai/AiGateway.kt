package com.my.knowledge.data.ai

import com.my.knowledge.ui.ModelConfig

interface AiProvider {
    suspend fun chat(prompt: String, context: String): String
}

class AiGateway(private val config: ModelConfig) {
    suspend fun process(prompt: String, context: String): String {
        // Here we would switch between local and external providers
        return "这是来自 ${config.modelName} 的分析结果。"
    }
}
