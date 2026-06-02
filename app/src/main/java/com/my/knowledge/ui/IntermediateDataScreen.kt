package com.my.knowledge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.KnowledgeCommunityEntity
import com.my.knowledge.data.db.entity.KnowledgeEntityEntity
import com.my.knowledge.data.db.entity.KnowledgeRelationEntity
import com.my.knowledge.viewmodel.IntermediateDataViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntermediateDataScreen(
    viewModel: IntermediateDataViewModel,
    onBack: () -> Unit,
    onViewDetail: (String) -> Unit,
    onSwitchKb: (String?) -> Unit = {}
) {
    val entities by viewModel.entities.collectAsState()
    val relations by viewModel.relations.collectAsState()
    val communities by viewModel.communities.collectAsState()
    val currentKbId by viewModel.currentKbId.collectAsState()
    val allBases by viewModel.knowledgeBases.collectAsState()
    val currentBaseName = remember(currentKbId, allBases) {
        currentKbId?.let { id -> allBases.firstOrNull { it.id == id }?.name }
    }
    var showKbMenu by remember { mutableStateOf(false) }

    val entityNameMap = remember(entities) { entities.associate { it.id to it.name } }

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("实体与概念", "关联关系", "主题群", "图谱")

    val selectedIds = remember { mutableStateListOf<String>() }
    var isSelectionMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var pendingSingleDelete by remember { mutableStateOf<SingleDeleteRequest?>(null) }
    var pendingBulkDelete by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        selectedIds.clear()
        isSelectionMode = false
        pendingSingleDelete = null
        pendingBulkDelete = false
    }

    // Whenever the underlying list changes (e.g. user switches knowledge
    // base, or the network finishes a rebuild), drop ids that are no
    // longer in the list so the "selected (N)" counter never goes stale.
    LaunchedEffect(entities, relations, communities, selectedTab) {
        val validIds = when (selectedTab) {
            0 -> entities.map { it.id }
            1 -> relations.map { it.id }
            2 -> communities.map { it.id }
            else -> emptyList()
        }.toSet()
        val it = selectedIds.iterator()
        while (it.hasNext()) {
            if (it.next() !in validIds) it.remove()
        }
        if (selectedIds.isEmpty()) isSelectionMode = false
    }

    fun currentList(): List<Any> = when (selectedTab) {
        0 -> entities
        1 -> relations
        2 -> communities
        else -> emptyList()
    }

    fun currentListIds(): List<String> = when (selectedTab) {
        0 -> entities.map { it.id }
        1 -> relations.map { it.id }
        2 -> communities.map { it.id }
        else -> emptyList()
    }

    fun toggleSelectAll() {
        val ids = currentListIds()
        if (selectedIds.size == ids.size && ids.isNotEmpty()) {
            selectedIds.clear()
        } else {
            selectedIds.clear()
            selectedIds.addAll(ids)
        }
    }

    fun performSingleDelete(delete: SingleDeleteRequest) {
        when (delete.tab) {
            0 -> viewModel.deleteEntities(listOf(delete.id))
            1 -> viewModel.deleteRelations(listOf(delete.id))
            2 -> viewModel.deleteCommunities(listOf(delete.id))
            else -> { /* graph tab has no single-row delete */ }
        }
    }

    fun performBulkDelete() {
        if (selectedIds.isEmpty()) return
        when (selectedTab) {
            0 -> viewModel.deleteEntities(selectedIds.toList())
            1 -> viewModel.deleteRelations(selectedIds.toList())
            2 -> viewModel.deleteCommunities(selectedIds.toList())
            else -> { /* graph tab bulk not used */ }
        }
        selectedIds.clear()
        isSelectionMode = false
    }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        if (isSelectionMode) {
                            isSelectionMode = false
                            selectedIds.clear()
                        } else onBack()
                    },
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
                    Text(if (isSelectionMode) "取消选择" else "返回", fontSize = 14.sp, color = Color(0xFF147EC5))
                }
                Spacer(modifier = Modifier.weight(1f))
                if (isSelectionMode) {
                    TextButton(onClick = ::toggleSelectAll) {
                        val ids = currentListIds()
                        val isAllSelected = ids.isNotEmpty() && selectedIds.size == ids.size
                        Text(if (isAllSelected) "取消全选" else "全选", color = Color(0xFF147EC5))
                    }
                    TextButton(
                        onClick = { if (selectedIds.isNotEmpty()) pendingBulkDelete = true },
                        enabled = selectedIds.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFEF4444))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("删除 (${selectedIds.size})", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "中间处理数据",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A),
                    modifier = Modifier.weight(1f)
                )
                // Knowledge-base scope switcher. Tapping opens a simple
                // bottom sheet so the user can hop between bases. We use
                // a plain Surface + AlertDialog here (rather than
                // ExposedDropdownMenuBox) because the latter's anchor
                // contract is meant for TextField/OutlinedTextField and
                // explodes at runtime when you put a Surface in there.
                Surface(
                    color = Color(0xFFE0F2FE),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.clickable { showKbMenu = true }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = currentBaseName ?: "全部知识库",
                            fontSize = 12.sp,
                            color = Color(0xFF147EC5),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = Color(0xFF147EC5),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.White,
            contentColor = Color(0xFF147EC5),
            divider = { HorizontalDivider(color = Color(0xFFF3F4F6)) }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontSize = 14.sp) }
                )
            }
        }

        // ---- Bulk-select header strip ----------------------------------
        // A persistent row at the top of the list, always visible, that
        // toggles select-all for the current tab. This is what the user
        // is asking for: the "全选" button is reachable without first
        // long-pressing a row to enter selection mode.
        if (selectedTab != 3) {
            BulkSelectHeader(
                totalCount = currentListIds().size,
                selectedCount = selectedIds.size,
                tabLabel = when (selectedTab) {
                    0 -> "实体/概念"
                    1 -> "关系"
                    else -> "主题群"
                },
                onToggleAll = {
                    isSelectionMode = true
                    toggleSelectAll()
                },
                onDelete = {
                    if (selectedIds.isNotEmpty()) pendingBulkDelete = true
                }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> EntityList(
                    entities = entities,
                    selectedIds = selectedIds,
                    isSelectionMode = isSelectionMode,
                    onToggleSelect = { id ->
                        isSelectionMode = true
                        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                    },
                    onLongPress = { id ->
                        isSelectionMode = true
                        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                    },
                    onDeleteSingle = { id -> pendingSingleDelete = SingleDeleteRequest(0, id) },
                    onViewDetail = { name ->
                        scope.launch {
                            viewModel.getEntityByName(name)?.let { entity ->
                                val ids = entity.sourceItemIdsJson.removePrefix("[").removeSuffix("]").split(",")
                                val firstId = ids.firstOrNull()?.trim()?.trim('"')
                                if (firstId != null) onViewDetail(firstId)
                            }
                        }
                    }
                )
                1 -> RelationList(
                    relations = relations,
                    entityNameMap = entityNameMap,
                    selectedIds = selectedIds,
                    isSelectionMode = isSelectionMode,
                    onToggleSelect = { id ->
                        isSelectionMode = true
                        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                    },
                    onLongPress = { id ->
                        isSelectionMode = true
                        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                    },
                    onDeleteSingle = { id -> pendingSingleDelete = SingleDeleteRequest(1, id) }
                )
                2 -> CommunityList(
                    communities = communities,
                    selectedIds = selectedIds,
                    isSelectionMode = isSelectionMode,
                    onToggleSelect = { id ->
                        isSelectionMode = true
                        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                    },
                    onLongPress = { id ->
                        isSelectionMode = true
                        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                    },
                    onDeleteSingle = { id -> pendingSingleDelete = SingleDeleteRequest(2, id) }
                )
                3 -> ForceDirectedGraph(
                    entities = entities,
                    relations = relations,
                    modifier = Modifier.fillMaxSize(),
                    onNodeClick = { entity ->
                        scope.launch {
                            val ids = entity.sourceItemIdsJson.removePrefix("[").removeSuffix("]").split(",")
                            val firstId = ids.firstOrNull()?.trim()?.trim('"')
                            if (firstId != null) onViewDetail(firstId)
                        }
                    }
                )
            }
        }
    }

    // Single-row delete confirmation.
    pendingSingleDelete?.let { req ->
        val label = when (req.tab) {
            0 -> "实体/概念"
            1 -> "关系"
            else -> "主题群"
        }
        AlertDialog(
            onDismissRequest = { pendingSingleDelete = null },
            title = { Text("删除该$label？") },
            text = {
                Text(
                    "该操作只会从「中间处理数据」列表中移除这一条，" +
                        "不会删除来源知识条目。删除后再次生成知识脉络时，" +
                        "如果来源仍然存在，仍可能重新出现。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    performSingleDelete(req)
                    pendingSingleDelete = null
                }) {
                    Text("删除", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSingleDelete = null }) { Text("取消") }
            }
        )
    }

    if (pendingBulkDelete) {
        val label = when (selectedTab) {
            0 -> "实体/概念"
            1 -> "关系"
            else -> "主题群"
        }
        AlertDialog(
            onDismissRequest = { pendingBulkDelete = false },
            title = { Text("删除选中的 ${selectedIds.size} 条$label？") },
            text = {
                Text(
                    "将一次性删除当前 Tab 选中的全部条目。来源知识条目不会被删除，" +
                        "但下次知识脉络重建时仍有可能重新出现。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    performBulkDelete()
                    pendingBulkDelete = false
                }) {
                    Text("全部删除", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkDelete = false }) { Text("取消") }
            }
        )
    }

    // Knowledge-base scope picker. A plain AlertDialog avoids the
    // ExposedDropdownMenuBox anchor-typing crash we hit when the inner
    // anchor was a Surface instead of a TextField.
    if (showKbMenu) {
        AlertDialog(
            onDismissRequest = { showKbMenu = false },
            title = { Text("选择知识库范围") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showKbMenu = false
                                onSwitchKb(null)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (currentKbId == null) Icons.Default.Check else Icons.Default.Hub,
                            contentDescription = null,
                            tint = if (currentKbId == null) Color(0xFF147EC5) else Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "全部知识库",
                            fontSize = 15.sp,
                            fontWeight = if (currentKbId == null) FontWeight.Bold else FontWeight.Normal,
                            color = Color(0xFF0F172A)
                        )
                    }
                    HorizontalDivider()
                    allBases.forEach { base ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showKbMenu = false
                                    onSwitchKb(base.id)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (base.id == currentKbId) Icons.Default.Check else Icons.Default.Hub,
                                contentDescription = null,
                                tint = if (base.id == currentKbId) Color(0xFF147EC5) else Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                base.name,
                                fontSize = 15.sp,
                                fontWeight = if (base.id == currentKbId) FontWeight.Bold else FontWeight.Normal,
                                color = Color(0xFF0F172A)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKbMenu = false }) { Text("关闭") }
            }
        )
    }
}

/**
 * Persistent header strip above the list, exposing a "全选" toggle and
 * a delete button. Visible even when the user hasn't yet long-pressed a
 * row to enter selection mode — that's the affordance the user
 * explicitly asked for.
 */
@Composable
private fun BulkSelectHeader(
    totalCount: Int,
    selectedCount: Int,
    tabLabel: String,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit
) {
    val isAllSelected = totalCount > 0 && selectedCount == totalCount
    Surface(
        color = Color(0xFFF1F5F9),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .clickable { onToggleAll() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isAllSelected) Color(0xFF147EC5) else Color.White)
                    .border(
                        BorderStroke(
                            1.dp,
                            if (totalCount == 0) Color(0xFFE2E8F0) else Color(0xFF147EC5)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isAllSelected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                } else {
                    Icon(Icons.Default.DoneAll, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isAllSelected) "已全选 $tabLabel" else "全选$tabLabel",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = if (selectedCount > 0) "已选 $selectedCount / $totalCount 项"
                    else "共 $totalCount 项",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }
            if (selectedCount > 0) {
                TextButton(
                    onClick = onDelete,
                    enabled = selectedCount > 0
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFEF4444))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除 ($selectedCount)", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private data class SingleDeleteRequest(val tab: Int, val id: String)

@Composable
private fun EntityList(
    entities: List<KnowledgeEntityEntity>,
    selectedIds: List<String>,
    isSelectionMode: Boolean,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onDeleteSingle: (String) -> Unit,
    onViewDetail: (String) -> Unit
) {
    if (entities.isEmpty()) {
        EmptyState("暂无提取的实体或概念")
    } else {
        val groups = remember(entities) {
            entities
                .groupBy { it.type.normalizedEntityType() }
                .toList()
                .sortedWith(
                    compareBy<Pair<String, List<KnowledgeEntityEntity>>> { it.first.entityTypeSortOrder() }
                        .thenBy { it.first.entityTypeLabel() }
                )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            groups.forEach { (type, groupedEntities) ->
                item(key = "header-$type") {
                    EntityGroupHeader(
                        title = type.entityTypeLabel(),
                        count = groupedEntities.size,
                        color = type.entityTypeColor()
                    )
                }
                items(groupedEntities, key = { it.id }) { entity ->
                    val isSelected = selectedIds.contains(entity.id)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) Color(0xFFE0F2FE) else Color.White,
                        shadowElevation = 0.5.dp,
                        modifier = Modifier.clickable {
                            if (isSelectionMode) onToggleSelect(entity.id) else onViewDetail(entity.name)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelectionMode) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) Color(0xFF147EC5) else Color(0xFFF1F5F9))
                                        .clickable { onToggleSelect(entity.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(type.entityTypeColor().copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (type == "concept") Icons.Default.Category else Icons.Default.BubbleChart,
                                    contentDescription = null,
                                    tint = type.entityTypeColor(),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entity.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                Text(
                                    "${type.entityTypeLabel()} · 权重: ${entity.weight.toInt()}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF5F87A3)
                                )
                            }
                            if (!isSelectionMode) {
                                IconButton(onClick = { onDeleteSingle(entity.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFD1D5DB), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntityGroupHeader(
    title: String,
    count: Int,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            color = color.copy(alpha = 0.12f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "$count",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        HorizontalDivider(
            modifier = Modifier.width(96.dp),
            color = Color(0xFFE2E8F0)
        )
    }
}

private fun String.normalizedEntityType(): String =
    trim().lowercase().ifBlank { "entity" }

private fun String.entityTypeSortOrder(): Int = when (this) {
    "entity", "person", "organization", "org", "location", "place", "event", "source" -> 0
    "concept" -> 1
    else -> 2
}

private fun String.entityTypeLabel(): String = when (this) {
    "entity" -> "实体"
    "concept" -> "概念"
    "person" -> "人物"
    "organization", "org" -> "组织"
    "location", "place" -> "地点"
    "event" -> "事件"
    "source" -> "来源"
    else -> replaceFirstChar { char -> char.uppercase() }
}

private fun String.entityTypeColor(): Color = when (this) {
    "concept" -> Color(0xFF16A34A)
    "person" -> Color(0xFFDB2777)
    "organization", "org" -> Color(0xFF7C3AED)
    "location", "place" -> Color(0xFF0891B2)
    "event" -> Color(0xFFEA580C)
    "source" -> Color(0xFF475569)
    else -> Color(0xFF0284C7)
}

@Composable
private fun RelationList(
    relations: List<KnowledgeRelationEntity>,
    entityNameMap: Map<String, String>,
    selectedIds: List<String>,
    isSelectionMode: Boolean,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onDeleteSingle: (String) -> Unit
) {
    if (relations.isEmpty()) {
        EmptyState("暂无识别的关联关系")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(relations, key = { it.id }) { relation ->
                val isSelected = selectedIds.contains(relation.id)
                val fromName = entityNameMap[relation.fromEntityId] ?: "未知实体"
                val toName = entityNameMap[relation.toEntityId] ?: "未知实体"

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) Color(0xFFE0F2FE) else Color.White,
                    shadowElevation = 0.5.dp,
                    modifier = Modifier.clickable {
                        if (isSelectionMode) onToggleSelect(relation.id)
                    }
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isSelectionMode) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) Color(0xFF147EC5) else Color(0xFFF1F5F9)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Hub, contentDescription = null, tint = Color(0xFF6366F1), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(relation.relationType, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6366F1))
                                Spacer(modifier = Modifier.weight(1f))
                                Text("置信度: ${(relation.confidence * 100).toInt()}%", fontSize = 11.sp, color = Color(0xFFA3A3A3))
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = Color(0xFFF1F5F9),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        fromName,
                                        fontSize = 14.sp,
                                        color = Color(0xFF0F172A),
                                        modifier = Modifier.padding(8.dp),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.padding(horizontal = 8.dp).size(16.dp),
                                    tint = Color(0xFF94A3B8)
                                )
                                Surface(
                                    color = Color(0xFFF1F5F9),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        toName,
                                        fontSize = 14.sp,
                                        color = Color(0xFF0F172A),
                                        modifier = Modifier.padding(8.dp),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        if (!isSelectionMode) {
                            IconButton(onClick = { onDeleteSingle(relation.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFD1D5DB), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityList(
    communities: List<KnowledgeCommunityEntity>,
    selectedIds: List<String>,
    isSelectionMode: Boolean,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onDeleteSingle: (String) -> Unit
) {
    if (communities.isEmpty()) {
        EmptyState("暂无形成的主题群")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(communities, key = { it.id }) { community ->
                val isSelected = selectedIds.contains(community.id)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) Color(0xFFE0F2FE) else Color.White,
                    shadowElevation = 0.5.dp,
                    modifier = Modifier.clickable {
                        if (isSelectionMode) onToggleSelect(community.id)
                    }
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isSelectionMode) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) Color(0xFF147EC5) else Color(0xFFF1F5F9)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(community.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(community.summary, fontSize = 13.sp, color = Color(0xFF5F87A3), lineHeight = 20.sp)
                        }
                        if (!isSelectionMode) {
                            IconButton(onClick = { onDeleteSingle(community.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFD1D5DB), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFFDBEEFF))
            Spacer(modifier = Modifier.height(16.dp))
            Text(text, fontSize = 14.sp, color = Color(0xFFA3A3A3))
        }
    }
}
