package com.my.knowledge.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.ui.component.KnowledgeDigestSection
import com.my.knowledge.ui.component.AskSheet
import com.my.knowledge.ui.component.ImportSheet
import com.my.knowledge.viewmodel.AskViewModel
import com.my.knowledge.viewmodel.KnowledgeHomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(
    viewModel: KnowledgeHomeViewModel,
    askViewModel: AskViewModel,
    onOpenContext: () -> Unit,
    onOpenFragments: () -> Unit,
    onOpenKbDetail: (String) -> Unit,
    onOpenKbManage: () -> Unit
) {
    val knowledgeBases by viewModel.knowledgeBases.collectAsState()
    
    var showAskSheet by remember { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }
    var selectedFileUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val selectedFileUri = selectedFileUris.firstOrNull()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                selectedFileUris = uris
                showImportSheet = true
            }
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF7FBFF)), // Ocean 25
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item {
                PageHeader(
                    title = "知识库",
                    hint = "少打扰，多沉淀",
                    action = {
                        TextButton(
                            onClick = { 
                                filePickerLauncher.launch(arrayOf(
                                    "application/pdf",
                                    "application/msword",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "text/plain",
                                    "image/*"
                                ))
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF147EC5))
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("导入", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            item {
                KnowledgeDigestSection(
                    onOpenContext = onOpenContext,
                    onOpenFragments = onOpenFragments
                )
            }

            item {
                Section(title = "我的知识库", more = "管理", onMoreClick = onOpenKbManage) {
                    if (knowledgeBases.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("暂无知识库", color = Color(0xFFA3A3A3), fontSize = 14.sp)
                        }
                    } else {
                        knowledgeBases.forEachIndexed { index, item ->
                            if (index != 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = Color(0xFFDBEEFF))
                            QuietCell(
                                title = item.name,
                                desc = item.description,
                                onClick = { onOpenKbDetail(item.id) },
                                leftContent = {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFEFF7FF))
                                            .border(1.dp, Color(0xFFCBE8FF), RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = item.iconText,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF147EC5)
                                        )
                                    }
                                },
                                right = {
                                    Surface(
                                        color = if (item.isSystem) Color(0xFFFFF7ED) else Color(0xFFEFF7FF),
                                        shape = CircleShape
                                    ) {
                                        Text(
                                            text = item.itemCount.toString(),
                                            fontSize = 11.sp,
                                            color = if (item.isSystem) Color(0xFFEA580C) else Color(0xFF147EC5),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            item {
                Section(title = "继续处理", more = "稍后") {
                    QuietCell(
                        title = "未分类内容处理",
                        desc = "系统已准备好建议",
                        leftContent = {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFF5F5F5)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("○", color = Color(0xFF525252), fontSize = 18.sp)
                            }
                        },
                        right = {
                            Text("5 条建议", fontSize = 12.sp, color = Color(0xFFEA580C))
                        },
                        onClick = onOpenFragments
                    )
                }
            }
        }

        // Floating Action Button for "Ask"
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 20.dp)
        ) {
            FloatingActionButton(
                onClick = { showAskSheet = true },
                containerColor = Color(0xFF111827),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(48.dp).shadow(12.dp, CircleShape)
            ) {
                Text("AI", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (showAskSheet) {
            AskSheet(askViewModel = askViewModel, onClose = { showAskSheet = false })
        }

        if (showImportSheet && selectedFileUri != null) {
            ImportSheet(
                viewModel = viewModel,
                uri = selectedFileUri,
                onClose = {
                    val remaining = selectedFileUris.drop(1)
                    selectedFileUris = remaining
                    showImportSheet = remaining.isNotEmpty()
                }
            )
        }
    }
}
