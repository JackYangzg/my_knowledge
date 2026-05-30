package com.my.knowledge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings Screen with Local-First Toggle (P0)
 * All AI external calls must be explicitly enabled by user
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val currentConfig = KnowledgeManager.modelConfig
    var provider by remember { mutableStateOf(currentConfig.provider) }
    var modelName by remember { mutableStateOf(currentConfig.modelName) }
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var baseUrl by remember { mutableStateOf(currentConfig.baseUrl) }
    
    // P0: Local-first toggle - AI calls disabled by default
    var aiExternalCallsEnabled by remember { mutableStateOf(KnowledgeManager.aiExternalCallsEnabled) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7FBFF))
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 20.dp)
                .padding(top = 48.dp, bottom = 12.dp)
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(24.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFF147EC5)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("返回", fontSize = 14.sp, color = Color(0xFF147EC5))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "设置",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // P0: Local-First Section
            LocalFirstSection(
                enabled = aiExternalCallsEnabled,
                onEnabledChange = { enabled ->
                    aiExternalCallsEnabled = enabled
                    KnowledgeManager.aiExternalCallsEnabled = enabled
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                "模型配置",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SettingsTextField(label = "提供商", value = provider, onValueChange = { provider = it }, placeholder = "例如: OpenAI, Anthropic")
            SettingsTextField(label = "模型名称", value = modelName, onValueChange = { modelName = it }, placeholder = "例如: gpt-4o, claude-3-5-sonnet")
            SettingsTextField(label = "API Key", value = apiKey, onValueChange = { apiKey = it }, placeholder = "输入您的 API 密钥", isPassword = true)
            SettingsTextField(label = "Base URL", value = baseUrl, onValueChange = { baseUrl = it }, placeholder = "API 基础地址")

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    KnowledgeManager.updateModelConfig(
                        ModelConfig(provider, modelName, apiKey, baseUrl)
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827))
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("保存配置", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

/**
 * P0: Local-First Section
 * Ensures all AI external calls require explicit user opt-in
 */
@Composable
fun LocalFirstSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFDBEEFF))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (enabled) Icons.Default.Cloud else Icons.Default.Storage,
                        contentDescription = null,
                        tint = if (enabled) Color(0xFF147EC5) else Color(0xFF5F87A3),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "本地优先模式",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            if (enabled) "已开启外部 AI 调用" else "仅使用本地数据处理",
                            fontSize = 12.sp,
                            color = Color(0xFF5F87A3)
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF147EC5)
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Surface(
                color = Color(0xFFFFF7ED),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = Color(0xFFEA580C),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "开启后，AI 将使用外部服务处理内容。原文、附件、索引和问答结果默认只存储在本地。",
                        fontSize = 12.sp,
                        color = Color(0xFF92400E)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false
) {
    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        Text(label, fontSize = 14.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Color(0xFFA3A3A3)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF147EC5),
                unfocusedBorderColor = Color(0xFFDBEEFF)
            )
        )
    }
}