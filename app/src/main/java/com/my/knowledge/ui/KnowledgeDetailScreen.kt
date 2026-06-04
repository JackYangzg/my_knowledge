package com.my.knowledge.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.ui.component.AskSheet
import com.my.knowledge.ui.component.ImportSheet
import com.my.knowledge.ui.component.KnowledgeItemRow
import com.my.knowledge.viewmodel.ImportFileItem
import com.my.knowledge.viewmodel.KnowledgeItemListViewModel
import com.my.knowledge.viewmodel.AskViewModel
import com.my.knowledge.data.ai.ScopeType
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.my.knowledge.R
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing

@Composable
fun KnowledgeDetailScreen(
    kbId: String,
    kbName: String,
    viewModel: KnowledgeItemListViewModel,
    askViewModel: AskViewModel,
    knowledgeRepository: com.my.knowledge.domain.repository.KnowledgeRepository,
    allKnowledgeBases: List<KnowledgeBaseEntity>,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit = {},
    onOpenIntermediate: (String) -> Unit = {}
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    val items by viewModel.items.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val itemCount by viewModel.itemCount.collectAsState()
    val exportStatus by viewModel.exportStatus.collectAsState()

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<KnowledgeItemEntity?>(null) }
    var statusTarget by remember { mutableStateOf<KnowledgeItemEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAskSheet by remember { mutableStateOf(false) }

    // Popup Menu State
    var showPopupMenu by remember { mutableStateOf(false) }
    var showMoveSelectionDialog by remember { mutableStateOf(false) }
    var popupTargetItem by remember { mutableStateOf<KnowledgeItemEntity?>(null) }
    // P1: 记录长按的窗口像素坐标,Popup 直接从手指位置弹出。
    var popupOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // In-place import: when the user opens the file picker from inside a
    // knowledge base, we want to lock the destination to THIS base. We
    // resolve the base entity from the [kbId] argument + [allKnowledgeBases]
    // — passing the id down from the nav graph is more reliable than
    // inferring it from the first list item, which can be empty.
    val currentKb: KnowledgeBaseEntity? = remember(kbId, allKnowledgeBases) {
        allKnowledgeBases.firstOrNull { it.id == kbId }
    }
    // Queue of files waiting for the import sheet. The picker supports
    // multi-select, so we keep the rest and pop one at a time onto the
    // sheet (mirroring KnowledgeScreen's behaviour on the home tab).
    var pendingImportUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showImportSheet by remember { mutableStateOf(false) }
    val pendingImportUri = pendingImportUris.firstOrNull()

    val kbFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                pendingImportUris = uris
                showImportSheet = true
            }
        }
    )

    // Resolve the current knowledge base id once so the floating "AI 问一问"
    // button can scope Ask to this whole base (rather than to any single
    // item the user happens to have hovered).
    val currentKbId = remember(items, kbId) { kbId.takeIf { it.isNotBlank() } ?: items.firstOrNull()?.knowledgeBaseId }
    val showFloatingAskButton = !currentKbId.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bgPage)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = kbName,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = if (itemCount > 0) "共 $itemCount 条知识" else "暂无知识",
                        fontSize = 13.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!currentKbId.isNullOrBlank()) {
                        TextButton(
                            onClick = { onOpenIntermediate(currentKbId) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Hub,
                                contentDescription = null,
                                tint = palette.brand,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.auto_1503df87), fontSize = 12.sp, color = palette.brand)
                        }
                    }
                    // In-place import. Always visible (not gated on
                    // selection mode) because the user can be browsing
                    // an empty list and still want to add the first
                    // file. Tapping opens the system file picker and
                    // routes the import straight into THIS knowledge
                    // base — no "where should this go?" question.
                    // Visual style deliberately matches the
                    // multi-select / search buttons (36dp light-blue
                    // square with brand-blue icon) so the three
                    // actions read as one toolbar.
                    if (currentKb != null) {
                        IconButton(
                            onClick = {
                                kbFilePickerLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "application/msword",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "text/plain",
                                        "text/markdown",
                                        "image/*"
                                    )
                                )
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .background(palette.bgPage, RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                Icons.Default.FileUpload,
                                contentDescription = "导入到本知识库",
                                tint = palette.brand,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (selectionMode && selectedIds.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                viewModel.exportSelectedItems(selectedIds)
                                selectedIds = emptySet()
                                selectionMode = false
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .background(palette.bgPage, RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "导出选中",
                                tint = palette.brand,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            selectionMode = !selectionMode
                            if (!selectionMode) selectedIds = emptySet()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(palette.bgPage, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            Icons.Default.Checklist,
                            contentDescription = "多选",
                            tint = if (selectionMode) palette.semanticWarning else palette.brand,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { showSearch = !showSearch },
                        modifier = Modifier
                            .size(36.dp)
                            .background(palette.bgPage, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = palette.brand,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Search bar
            if (showSearch) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.auto_c5a0477b), color = palette.textTertiary) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(spacing.md),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.brand,
                        unfocusedBorderColor = palette.borderBrand
                    )
                )
            }

            if (selectionMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "已选择 ${selectedIds.size} 条",
                    fontSize = 12.sp,
                    color = palette.textSecondary
                )
            }
        }

        val listState = rememberLazyListState()
        val shouldLoadMore by remember(items.size, hasMore, isLoadingMore) {
            derivedStateOf {
                hasMore && !isLoadingMore &&
                    listState.layoutInfo.visibleItemsInfo.isNotEmpty() &&
                    listState.layoutInfo.visibleItemsInfo.last().index >= items.size - 2
            }
        }
        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore) viewModel.loadMore()
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (items.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (searchQuery.isNotEmpty()) "未找到匹配结果" else "该知识库尚无已整理知识",
                            color = palette.textSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                items(items) { item ->
                    KnowledgeItemRow(
                        item,
                        onDelete = {
                            deleteTarget = item
                            showDeleteDialog = true
                        },
                        onRetry = { viewModel.retryItem(item.id) },
                        onStatusClick = { statusTarget = item },
                        selectionMode = selectionMode,
                        selected = item.id in selectedIds,
                        onSelectionChange = { checked ->
                            selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                        },
                        onLongClick = { pressOffset ->
                            popupTargetItem = item
                            popupOffset = pressOffset
                            showPopupMenu = true
                        },
                        onClick = {
                            if (selectionMode) {
                                selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                            } else {
                                onOpenItem(item.id)
                            }
                        }
                    )
                }
                item(key = "list-footer") {
                    ListFooter(hasMore = hasMore, isLoadingMore = isLoadingMore)
                }
            }
        }
        
        // Popup Menu
        if (showPopupMenu && popupTargetItem != null) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    popupOffset.x.roundToInt(),
                    popupOffset.y.roundToInt()
                ),
                onDismissRequest = { showPopupMenu = false },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    shape = RoundedCornerShape(spacing.sm),
                    color = Color.White,
                    shadowElevation = 8.dp,
                    tonalElevation = 2.dp
                ) {
                    Column(modifier = Modifier.widthIn(min = 180.dp)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.auto_dd985b01)) },
                            onClick = {
                                showPopupMenu = false
                                showMoveSelectionDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.auto_cea896db)) },
                            onClick = {
                                showPopupMenu = false
                                deleteTarget = popupTargetItem
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            }
        }

        // Move Selection Dialog
        if (showMoveSelectionDialog && popupTargetItem != null) {
            val currentBaseId = popupTargetItem!!.knowledgeBaseId
            AlertDialog(
                onDismissRequest = {
                    showMoveSelectionDialog = false
                    popupTargetItem = null
                },
                title = { Text(stringResource(R.string.auto_f4595123), fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        allKnowledgeBases.filter { it.id != currentBaseId }.forEach { base ->
                            TextButton(
                                onClick = {
                                    viewModel.moveItem(popupTargetItem!!.id, base.id)
                                    showMoveSelectionDialog = false
                                    popupTargetItem = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = palette.brandSubtle,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                base.iconText.ifBlank { base.name.take(1) },
                                                fontSize = 12.sp,
                                                color = palette.brand
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(base.name, color = palette.textPrimary, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = {
                        showMoveSelectionDialog = false
                        popupTargetItem = null
                    }) { Text(stringResource(R.string.auto_4d0b4688)) }
                }
            )
        }

        exportStatus?.let { status ->
            Surface(
                color = palette.brandSubtle,
                shadowElevation = 2.dp
            ) {
                Text(
                    text = status,
                    fontSize = 12.sp,
                    color = palette.brand,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
        } // close inner Column (header + LazyColumn + exportStatus)

        // Floating "AI 问一问" button — pinned to the bottom-right of the
        // knowledge-base detail page, visible only while we know which
        // base we're inside. Tap to scope the Ask session to this whole
        // base and pop the AskSheet.
        if (showFloatingAskButton) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 24.dp, end = 20.dp)
            ) {
                Surface(
                    onClick = {
                        askViewModel.setScope(ScopeType.KNOWLEDGE_BASE, currentKbId)
                        askViewModel.startNewConversation(kbName)
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
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog && deleteTarget != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                deleteTarget = null
            },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = palette.semanticWarning) },
            title = { Text(stringResource(R.string.auto_cea896db), fontWeight = FontWeight.Bold) },
            text = { Text("确定要删除「${deleteTarget!!.title}」吗？删除后可从回收站恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget?.let { viewModel.deleteItem(it.id) }
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

    if (showAskSheet) {
        AskSheet(askViewModel = askViewModel, onClose = { showAskSheet = false })
    }

    // In-place import sheet. Only show when we know where the file is
    // going (we need [currentKb] for the locked destination). The picker
    // result is a multi-URI queue — we pop one at a time and the
    // remaining URIs stay queued for the next sheet iteration, so a
    // bulk import still gets per-file confirmation.
    if (showImportSheet && pendingImportUri != null && currentKb != null) {
        ImportSheet(
            uri = pendingImportUri,
            knowledgeBases = allKnowledgeBases,
            lockedKb = currentKb,
            onClose = {
                val remaining = pendingImportUris.drop(1)
                pendingImportUris = remaining
                showImportSheet = remaining.isNotEmpty()
            },
            onConfirm = { req ->
                viewModel.importFilesToCurrentBase(
                    listOf(
                        ImportFileItem(
                            uri = pendingImportUri,
                            displayName = req.displayName,
                            mimeType = req.mimeType
                        )
                    )
                )
            }
        )
    }

    statusTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { statusTarget = null },
            title = { Text("处理状态：${item.title}", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = if (item.status == KnowledgeItemEntity.STATUS_FAILED) {
                        item.excerpt.ifBlank { "处理失败，暂无详细错误。" }
                    } else {
                        "当前状态：${item.status}"
                    }
                )
            },
            confirmButton = {
                if (item.status == KnowledgeItemEntity.STATUS_FAILED) {
                    Button(
                        onClick = {
                            viewModel.retryItem(item.id)
                            statusTarget = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.brand)
                    ) { Text(stringResource(R.string.auto_e2d53a6d)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { statusTarget = null }) { Text(stringResource(R.string.auto_6c14bd7f)) }
            }
        )
    }
}

@Composable
private fun ListFooter(hasMore: Boolean, isLoadingMore: Boolean) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoadingMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = palette.brand
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.auto_300ee3de), fontSize = 12.sp, color = palette.textTertiary)
            }
            !hasMore -> Text(
                stringResource(R.string.auto_049d09bf),
                fontSize = 12.sp,
                color = palette.textTertiary
            )
        }
    }
}
