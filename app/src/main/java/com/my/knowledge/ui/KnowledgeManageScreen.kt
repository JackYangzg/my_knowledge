package com.my.knowledge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.ui.component.MiniTag
import com.my.knowledge.ui.component.SummaryCard
import com.my.knowledge.viewmodel.KnowledgeHomeViewModel
import com.my.knowledge.viewmodel.KnowledgeManageViewModel
import androidx.compose.ui.res.stringResource
import com.my.knowledge.R
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing

@Composable
fun KnowledgeManageScreen(
    homeViewModel: KnowledgeHomeViewModel,
    manageViewModel: KnowledgeManageViewModel,
    onBack: () -> Unit,
    onOpenKbDetail: (String) -> Unit
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    val knowledgeBases by homeViewModel.knowledgeBases.collectAsState()
    var deleteTarget by remember { mutableStateOf<KnowledgeBaseEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var moveToUnfiled by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var createDescription by remember { mutableStateOf("") }
    var createNameError by remember { mutableStateOf<String?>(null) }

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
                onClick = onBack,
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
                Text(stringResource(R.string.auto_94c32741), style = MaterialTheme.typography.titleSmall, color = palette.brand)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.auto_053fc4ec), style = MaterialTheme.typography.displayLarge,
                        color = palette.textPrimary
                    )
                    Text(
                        text = stringResource(R.string.auto_af04525e), style = MaterialTheme.typography.labelLarge,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Button(
                    onClick = {
                        createName = ""
                        createDescription = ""
                        showCreateDialog = true
                    },
                    shape = RoundedCornerShape(spacing.md),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.brand),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.auto_f93ee39a), style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard(Modifier.weight(1f), knowledgeBases.size.toString(), "知识库")
                    SummaryCard(Modifier.weight(1f), knowledgeBases.sumOf { it.itemCount }.toString(), "知识总数")
                    SummaryCard(Modifier.weight(1f), knowledgeBases.find { it.type == "unfiled" }?.itemCount?.toString() ?: "0", "未归档")
                }
            }

            item {
                Section(title = stringResource(R.string.auto_6a782196), more = stringResource(R.string.auto_a7f814c0)) {
                    knowledgeBases.forEachIndexed { index, item ->
                        if (index != 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = palette.borderBrand)
                        QuietCell(
                            title = item.name,
                            desc = if (item.type == "unfiled") "系统默认知识库，暂存还没决定去向的内容" else item.description,
                            onClick = { onOpenKbDetail(item.id) },
                            leftContent = {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(palette.brandSubtle)
                                        .border(1.dp, palette.borderBrand, RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = item.iconText, style = MaterialTheme.typography.titleMedium,
                                        color = palette.brand
                                    )
                                }
                            },
                            right = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Column(modifier = Modifier.padding(top = 6.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            MiniTag("${item.itemCount} 条知识")
                                            if (item.isSystem) MiniTag("系统")
                                        }
                                    }
                                    if (item.allowDelete) {
                                        IconButton(
                                            onClick = { deleteTarget = item; showDeleteDialog = true },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "删除",
                                                tint = palette.textMuted,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // Create knowledge base dialog
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                icon = {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = palette.brand,
                        modifier = Modifier.size(24.dp)
                    )
                },
                title = { Text(stringResource(R.string.auto_f93ee39a), fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = createName,
                            onValueChange = {
                                createName = it
                                createNameError = null
                            },
                            label = { Text(stringResource(R.string.auto_82e28f0d)) },
                            placeholder = { Text(stringResource(R.string.auto_ef91ee7d), color = palette.textTertiary) },
                            singleLine = true,
                            isError = createNameError != null,
                            supportingText = if (createNameError != null) {
                                { Text(createNameError!!, color = palette.semanticError, style = MaterialTheme.typography.labelMedium) }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(spacing.md)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = createDescription,
                            onValueChange = { createDescription = it },
                            label = { Text(stringResource(R.string.auto_912ab301)) },
                            placeholder = { Text(stringResource(R.string.auto_fb0a5997), color = palette.textTertiary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(spacing.md)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val name = createName.trim()
                            if (name.isBlank()) {
                                createNameError = "名称不能为空"
                                return@Button
                            }
                            if (knowledgeBases.any { it.name == name }) {
                                createNameError = "该名称已存在，请使用其他名称"
                                return@Button
                            }
                            val desc = createDescription.trim().ifBlank { null }
                            manageViewModel.createKnowledgeBase(name, desc)
                            showCreateDialog = false
                        },
                        enabled = true,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.bgInverse)
                    ) {
                        Text(stringResource(R.string.auto_fcbd0932), color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) {
                        Text(stringResource(R.string.auto_4d0b4688))
                    }
                }
            )
        }

        // P1: Delete confirmation dialog
        if (showDeleteDialog && deleteTarget != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = palette.semanticWarning) },
                title = { Text(stringResource(R.string.auto_e62a78b1), fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("确定要删除「${deleteTarget!!.name}」吗？")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "该知识库包含 ${deleteTarget!!.itemCount} 条知识。", style = MaterialTheme.typography.labelLarge,
                            color = palette.textSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = moveToUnfiled,
                                onCheckedChange = { moveToUnfiled = it }
                            )
                            Text(stringResource(R.string.auto_103e3d9c), fontSize = 14.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            manageViewModel.deleteKnowledgeBase(deleteTarget!!.id, moveToUnfiled)
                            showDeleteDialog = false
                            deleteTarget = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.semanticError)
                    ) {
                        Text(stringResource(R.string.auto_3755f56f), color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDeleteDialog = false
                        deleteTarget = null
                    }) {
                        Text(stringResource(R.string.auto_4d0b4688))
                    }
                }
            )
        }
    }
}
