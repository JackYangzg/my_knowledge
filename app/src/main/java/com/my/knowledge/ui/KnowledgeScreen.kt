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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.R
import com.my.knowledge.data.ai.ScopeType
import com.my.knowledge.ui.component.KnowledgeDigestSection
import com.my.knowledge.ui.component.AskSheet
import com.my.knowledge.ui.component.ImportSheet
import com.my.knowledge.viewmodel.AskViewModel
import com.my.knowledge.viewmodel.KnowledgeHomeViewModel
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(
    viewModel: KnowledgeHomeViewModel,
    askViewModel: AskViewModel,
    knowledgeRepository: com.my.knowledge.domain.repository.KnowledgeRepository,
    onOpenContext: () -> Unit,
    onOpenFragments: () -> Unit,
    onOpenKbDetail: (String) -> Unit,
    onOpenKbManage: () -> Unit,
    onOpenNewNote: (String) -> Unit = {}
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
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
                .background(palette.bgPage), // Ocean 25
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item {
                PageHeader(
                    title = stringResource(R.string.auto_1dda51f9),
                    hint = stringResource(R.string.auto_1fba2a48),
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
                            colors = ButtonDefaults.textButtonColors(contentColor = palette.brand)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.auto_60e2bcad), fontWeight = FontWeight.Bold)
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
                Section(title = stringResource(R.string.auto_89858b21), more = stringResource(R.string.auto_4989b5cf), onMoreClick = onOpenKbManage) {
                    if (knowledgeBases.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.auto_1674dc8d), color = palette.textTertiary, fontSize = 14.sp)
                        }
                    } else {
                        knowledgeBases.forEachIndexed { index, item ->
                            if (index != 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = palette.borderBrand)
                            QuietCell(
                                title = item.name,
                                desc = item.description,
                                onClick = { onOpenKbDetail(item.id) },
                                leftContent = {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(spacing.md))
                                            .background(palette.brandSubtle)
                                            .border(1.dp, palette.borderBrand, RoundedCornerShape(spacing.md)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = item.iconText, style = MaterialTheme.typography.titleMedium,
                                            color = palette.brand
                                        )
                                    }
                                },
                                right = {
                                    Surface(
                                        color = if (item.isSystem) Color(0xFFFFF7ED) else palette.brandSubtle,
                                        shape = CircleShape
                                    ) {
                                        Text(
                                            text = item.itemCount.toString(), style = MaterialTheme.typography.labelSmall,
                                            color = if (item.isSystem) palette.semanticWarning else palette.brand,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

        }

        // Floating "AI 问一问" button. Same look as the rest of the app's
        // detail pages, but scoped to GLOBAL — the model is told it can
        // see every knowledge base in the app and must search across
        // them itself.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 24.dp, end = 20.dp)
        ) {
            Surface(
                onClick = {
                    askViewModel.setScope(ScopeType.GLOBAL, "")
                    askViewModel.startNewConversation("全局知识库问答")
                    showAskSheet = true
                },
                shape = CircleShape,
                color = palette.bgInverse,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .size(56.dp)
                    .shadow(12.dp, CircleShape)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "AI",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (showAskSheet) {
            AskSheet(askViewModel = askViewModel, onClose = { showAskSheet = false })
        }

        if (showImportSheet && selectedFileUri != null) {
            ImportSheet(
                uri = selectedFileUri,
                knowledgeBases = knowledgeBases,
                onClose = {
                    val remaining = selectedFileUris.drop(1)
                    selectedFileUris = remaining
                    showImportSheet = remaining.isNotEmpty()
                },
                onConfirm = { req ->
                    // Sheet returns the resolved KB id; map it back to a
                    // name for KnowledgeHomeViewModel.importUri, which
                    // historically takes a name (the legacy API).
                    val targetName = knowledgeBases.firstOrNull { it.id == req.targetKbId }?.name
                        ?: "未归类"
                    viewModel.importUri(
                        uri = selectedFileUri,
                        displayName = req.displayName,
                        mimeType = req.mimeType,
                        sourceType = "file_import",
                        targetLibrary = targetName
                    )
                }
            )
        }
        // "+" FAB: 从主页快速新建知识到任意 KB(优先当前第一可用 KB)。
        val nonRecycle = knowledgeBases.firstOrNull { it.type != "recyclebin" }
        if (nonRecycle != null) {
            FloatingActionButton(
                onClick = { onOpenNewNote(nonRecycle.name) },
                containerColor = palette.brand,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 20.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建知识")
            }
        }
    }
}
