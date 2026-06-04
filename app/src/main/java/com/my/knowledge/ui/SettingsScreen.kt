package com.my.knowledge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Info
import android.provider.Settings
import android.net.Uri
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.SmartToy
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.ui.res.stringResource
import com.my.knowledge.R
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing

@Composable
fun SettingsScreen(onBack: () -> Unit) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    val currentConfig = KnowledgeManager.modelConfig
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    var provider by remember { mutableStateOf(currentConfig.provider) }
    var modelName by remember { mutableStateOf(currentConfig.modelName) }
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var baseUrl by remember { mutableStateOf(currentConfig.baseUrl) }
    var imageAnalysisProvider by remember { mutableStateOf(currentConfig.imageAnalysisProvider) }
    var imageAnalysisApiKey by remember { mutableStateOf(currentConfig.imageAnalysisApiKey) }
    var imageAnalysisBaseUrl by remember { mutableStateOf(currentConfig.imageAnalysisBaseUrl) }
    var searchAnalysisProvider by remember { mutableStateOf(currentConfig.searchAnalysisProvider) }
    var searchAnalysisApiKey by remember { mutableStateOf(currentConfig.searchAnalysisApiKey) }
    var searchAnalysisBaseUrl by remember { mutableStateOf(currentConfig.searchAnalysisBaseUrl) }
    var voiceProvider by remember { mutableStateOf(currentConfig.voiceProvider) }
    var voiceApiKey by remember { mutableStateOf(currentConfig.voiceApiKey) }
    var voiceAppId by remember { mutableStateOf(currentConfig.voiceAppId) }
    var voiceClusterId by remember { mutableStateOf(currentConfig.voiceClusterId) }
    var debugPromptEnabled by remember { mutableStateOf(currentConfig.debugPromptEnabled) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun applyProviderDefaults(nextProvider: String) {
        provider = nextProvider
        if (nextProvider == "minimax") {
            modelName = "MiniMax-M3"
            baseUrl = "https://api.minimaxi.com/v1"
            imageAnalysisProvider = "minimax"
            searchAnalysisProvider = "minimax"
        }
    }

    fun applyImageProviderDefaults(nextProvider: String) {
        imageAnalysisProvider = nextProvider
        if (nextProvider == "minimax") imageAnalysisBaseUrl = "https://api.minimaxi.com/v1"
    }

    fun applySearchProviderDefaults(nextProvider: String) {
        searchAnalysisProvider = nextProvider
        if (nextProvider == "minimax") searchAnalysisBaseUrl = "https://api.minimaxi.com/v1"
    }

    fun applyVoiceProviderDefaults(nextProvider: String) {
        voiceProvider = nextProvider
        if (nextProvider == "volcengine") {
            voiceClusterId = "volc_ent_asr_streaming"
        }
    }

    fun saveConfig() {
        KnowledgeManager.updateModelConfig(
            ModelConfig(
                provider = provider,
                modelName = modelName,
                apiKey = apiKey,
                baseUrl = baseUrl,
                imageAnalysisProvider = imageAnalysisProvider,
                imageAnalysisApiKey = imageAnalysisApiKey,
                imageAnalysisBaseUrl = imageAnalysisBaseUrl,
                searchAnalysisProvider = searchAnalysisProvider,
                searchAnalysisApiKey = searchAnalysisApiKey,
                searchAnalysisBaseUrl = searchAnalysisBaseUrl,
                voiceProvider = voiceProvider,
                voiceApiKey = voiceApiKey,
                voiceAppId = voiceAppId,
                voiceClusterId = voiceClusterId,
                debugPromptEnabled = debugPromptEnabled
            )
        )
        statusMessage = "配置已保存"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bgPage)
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
                onClick = {
                    if (section == null) onBack() else section = null
                },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(24.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = palette.brand
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.auto_11d02415), fontSize = 14.sp, color = palette.brand)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = section?.title ?: "设置", style = MaterialTheme.typography.displayLarge,
                color = palette.textPrimary
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            when (section) {
                null -> {
                    SettingsSectionRow(
                        icon = Icons.Default.SmartToy,
                        title = stringResource(R.string.auto_4db4aab1),
                        desc = "$provider · $modelName",
                        onClick = { section = SettingsSection.Model }
                    )
                    SettingsSectionRow(
                        icon = Icons.Default.ImageSearch,
                        title = stringResource(R.string.auto_ce8b57b2),
                        desc = "$imageAnalysisProvider · ${if (imageAnalysisApiKey.isBlank()) "未配置 API Key" else "已配置"}",
                        onClick = { section = SettingsSection.Image }
                    )
                    SettingsSectionRow(
                        icon = Icons.Default.Search,
                        title = stringResource(R.string.auto_38e5f976),
                        desc = "$searchAnalysisProvider · ${if (searchAnalysisApiKey.isBlank()) "未配置 API Key" else "已配置"}",
                        onClick = { section = SettingsSection.Search }
                    )
                    SettingsSectionRow(
                        icon = Icons.Default.Mic,
                        title = stringResource(R.string.auto_6c6f71fe),
                        desc = "$voiceProvider · ${if (voiceApiKey.isBlank()) "未配置 API Key" else "已配置"}",
                        onClick = { section = SettingsSection.Voice }
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        shape = RoundedCornerShape(spacing.md),
                        color = Color.White,
                        border = BorderStroke(1.dp, palette.borderBrand)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = palette.brand, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.auto_0ee20898), style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
                                Text(stringResource(R.string.auto_2804e24e), fontSize = 12.sp, color = palette.textSecondary, modifier = Modifier.padding(top = 3.dp))
                            }
                            Switch(
                                checked = debugPromptEnabled,
                                onCheckedChange = {
                                    debugPromptEnabled = it
                                    saveConfig()
                                }
                            )
                        }
                    }
                    SettingsHint()
                }
                SettingsSection.Model -> {
                    ProviderDropdown(label = "模型提供商", value = provider, onValueChange = ::applyProviderDefaults)
                    SettingsTextField(label = "模型名称", value = modelName, onValueChange = { modelName = it }, placeholder = "MiniMax-M3")
                    SettingsTextField(label = "API Key", value = apiKey, onValueChange = { apiKey = it }, placeholder = "输入模型 API Key", isPassword = true)
                    SettingsTextField(label = "OpenAI 兼容 Base URL", value = baseUrl, onValueChange = { baseUrl = it }, placeholder = "https://api.minimaxi.com/v1")
                    SaveButton(
                        statusMessage = statusMessage,
                        onSave = { saveConfig() },
                        onTest = {
                            statusMessage = "正在测试模型接口..."
                            scope.launch {
                                statusMessage = testMinimaxEndpoint(baseUrl, apiKey, modelName, SettingsSection.Model)
                            }
                        }
                    )
                }
                SettingsSection.Image -> {
                    ProviderDropdown(label = "服务提供商", value = imageAnalysisProvider, onValueChange = ::applyImageProviderDefaults)
                    SettingsTextField(label = "图片分析 API Key", value = imageAnalysisApiKey, onValueChange = { imageAnalysisApiKey = it }, placeholder = "输入图片分析 API Key", isPassword = true)
                    SettingsTextField(label = "OpenAI 兼容 Base URL", value = imageAnalysisBaseUrl, onValueChange = { imageAnalysisBaseUrl = it }, placeholder = "https://api.minimaxi.com/v1")
                    SaveButton(
                        statusMessage = statusMessage,
                        onSave = { saveConfig() },
                        onTest = {
                            statusMessage = "正在测试图片分析接口..."
                            scope.launch {
                                statusMessage = testMinimaxEndpoint(imageAnalysisBaseUrl, imageAnalysisApiKey, modelName, SettingsSection.Image)
                            }
                        }
                    )
                }
                SettingsSection.Search -> {
                    ProviderDropdown(label = "服务提供商", value = searchAnalysisProvider, onValueChange = ::applySearchProviderDefaults)
                    SettingsTextField(label = "搜索分析 API Key", value = searchAnalysisApiKey, onValueChange = { searchAnalysisApiKey = it }, placeholder = "输入搜索分析 API Key", isPassword = true)
                    SettingsTextField(label = "OpenAI 兼容 Base URL", value = searchAnalysisBaseUrl, onValueChange = { searchAnalysisBaseUrl = it }, placeholder = "https://api.minimaxi.com/v1")
                    SaveButton(
                        statusMessage = statusMessage,
                        onSave = { saveConfig() },
                        onTest = {
                            statusMessage = "正在测试搜索分析接口..."
                            scope.launch {
                                statusMessage = testMinimaxEndpoint(searchAnalysisBaseUrl, searchAnalysisApiKey, modelName, SettingsSection.Search)
                            }
                        }
                    )
                }
                SettingsSection.Voice -> {
                    val context = LocalContext.current
                    
                    Surface(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                        shape = RoundedCornerShape(spacing.md),
                        color = Color(0xFFF0F9FF),
                        border = BorderStroke(1.dp, Color(0xFFBAE6FD))
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.auto_876fcbec), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0369A1))
                                Text(stringResource(R.string.auto_09db5d2c), fontSize = 12.sp, color = Color(0xFF0284C7))
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color(0xFF0284C7))
                        }
                    }

                    VoiceProviderDropdown(label = "语音服务商", value = voiceProvider, onValueChange = ::applyVoiceProviderDefaults)
                    SettingsTextField(label = "API Key (Access Token)", value = voiceApiKey, onValueChange = { voiceApiKey = it }, placeholder = "输入火山引擎 Access Token", isPassword = true)
                    SettingsTextField(label = "App ID", value = voiceAppId, onValueChange = { voiceAppId = it }, placeholder = "输入火山引擎 App ID")
                    SettingsTextField(label = "Cluster ID", value = voiceClusterId, onValueChange = { voiceClusterId = it }, placeholder = "volc_ent_asr_streaming")
                    SaveButton(
                        statusMessage = statusMessage,
                        onSave = { saveConfig() },
                        onTest = {
                            statusMessage = "正在检查语音接口配置..."
                            scope.launch {
                                statusMessage = if (voiceApiKey.isBlank() || voiceAppId.isBlank()) {
                                    "请先填写火山引擎 App ID 和 API Key"
                                } else {
                                    "语音配置格式已就绪，录音时将使用实时 WebSocket 识别"
                                }
                            }
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

private enum class SettingsSection(val title: String) {
    Model("模型配置"),
    Image("图片分析接口"),
    Search("搜索分析接口"),
    Voice("语音功能配置")
}

@Composable
fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        Text(label, fontSize = 14.sp, color = palette.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = palette.textTertiary) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(spacing.md),
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.brand,
                unfocusedBorderColor = palette.borderBrand
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceProviderDropdown(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        Text(label, fontSize = 14.sp, color = palette.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                shape = RoundedCornerShape(spacing.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.brand,
                    unfocusedBorderColor = palette.borderBrand
                )
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.auto_536c8267)) },
                    onClick = {
                        onValueChange("volcengine")
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDropdown(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        Text(label, fontSize = 14.sp, color = palette.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                shape = RoundedCornerShape(spacing.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.brand,
                    unfocusedBorderColor = palette.borderBrand
                )
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("minimax") },
                    onClick = {
                        onValueChange("minimax")
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun CapabilitySection(
    title: String,
    provider: String,
    onProviderChange: (String) -> Unit
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, modifier = Modifier.padding(bottom = 12.dp))
        ProviderDropdown(label = "服务提供商", value = provider, onValueChange = onProviderChange)
    }
}

@Composable
private fun SettingsSectionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(spacing.md),
        color = Color.White,
        border = BorderStroke(1.dp, palette.borderBrand)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = palette.brand, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
                Text(desc, fontSize = 12.sp, color = palette.textSecondary, modifier = Modifier.padding(top = 3.dp))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = palette.textTertiary)
        }
    }
}

@Composable
private fun SettingsHint() {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(color = Color(0xFFFFF7ED), shape = RoundedCornerShape(spacing.sm), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = palette.semanticWarning, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.auto_3c554377), fontSize = 12.sp, color = Color(0xFF92400E))
        }
    }
}

@Composable
private fun SaveButton(
    statusMessage: String?,
    onSave: () -> Unit,
    onTest: () -> Unit
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onTest,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(spacing.lg)
            ) {
                Text(stringResource(R.string.auto_0152e9cd), style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(spacing.lg),
                colors = ButtonDefaults.buttonColors(containerColor = palette.bgInverse)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.auto_817af187), style = MaterialTheme.typography.titleMedium)
            }
        }
        statusMessage?.let {
            Surface(color = palette.brandSubtle, shape = RoundedCornerShape(spacing.sm), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(it, fontSize = 12.sp, color = palette.brand, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

private suspend fun testMinimaxEndpoint(
    baseUrl: String,
    apiKey: String,
    modelName: String,
    section: SettingsSection
): String = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) return@withContext "请先填写 API Key"
    val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"
    val payload = when (section) {
        SettingsSection.Model -> """{"model":"${modelName.escapeJson()}","messages":[{"role":"user","content":"只回复OK"}],"max_tokens":32,"temperature":0}"""
        SettingsSection.Image -> """{"model":"${modelName.escapeJson()}","messages":[{"role":"user","content":[{"type":"text","text":"这张图片是什么颜色？只答颜色。"},{"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR42mP8z8AARQAEXgH+LKVrAAAAAElFTkSuQmCC"}}]}],"max_tokens":64,"temperature":0}"""
        SettingsSection.Search -> """{"model":"${modelName.escapeJson()}","messages":[{"role":"user","content":"使用搜索能力回答今天日期，只输出一句话。"}],"tools":[{"type":"web_search"}],"max_tokens":96,"temperature":0}"""
        SettingsSection.Voice -> return@withContext "语音测试暂未通过 REST 接口实现"
    }
    runCatching {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
        }
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val body = if (code in 200..299) {
            connection.inputStream.bufferedReader().readText()
        } else {
            connection.errorStream?.bufferedReader()?.readText().orEmpty()
        }
        connection.disconnect()
        if (code in 200..299 && body.contains("\"choices\"")) "接口测试成功" else "接口测试失败：HTTP $code"
    }.getOrElse { "接口测试失败：${it.localizedMessage ?: "未知错误"}" }
}

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
