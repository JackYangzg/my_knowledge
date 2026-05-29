package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val currentConfig = KnowledgeManager.modelConfig
    var provider by remember { mutableStateOf(currentConfig.provider) }
    var modelName by remember { mutableStateOf(currentConfig.modelName) }
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var baseUrl by remember { mutableStateOf(currentConfig.baseUrl) }

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
