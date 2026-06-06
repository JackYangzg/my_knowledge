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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.KnowledgeCommunityEntity
import com.my.knowledge.data.db.entity.KnowledgeEntityEntity
import com.my.knowledge.data.db.entity.KnowledgeRelationEntity
import com.my.knowledge.domain.model.KNOWLEDGE_CONCEPT_TYPE_NAMES
import com.my.knowledge.domain.model.knowledgeEntityTopLevelKind
import com.my.knowledge.domain.model.normalizeKnowledgeEntityType
import com.my.knowledge.viewmodel.IntermediateDataViewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.my.knowledge.R
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.Palette
import com.my.knowledge.ui.theme.LocalSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntermediateDataScreen(
    viewModel: IntermediateDataViewModel,
    onBack: () -> Unit,
    onViewDetail: (String) -> Unit,
    onSwitchKb: (String?) -> Unit = {}
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
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
    val tabs = listOf("实体与概念", "关联关系", "主题群")

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
        }
    }

    fun performBulkDelete() {
        if (selectedIds.isEmpty()) return
        when (selectedTab) {
            0 -> viewModel.deleteEntities(selectedIds.toList())
            1 -> viewModel.deleteRelations(selectedIds.toList())
            2 -> viewModel.deleteCommunities(selectedIds.toList())
        }
        selectedIds.clear()
        isSelectionMode = false
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
                        tint = palette.brand
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isSelectionMode) "取消选择" else "返回", fontSize = 14.sp, color = palette.brand)
                }
                Spacer(modifier = Modifier.weight(1f))
                if (isSelectionMode) {
                    TextButton(onClick = ::toggleSelectAll) {
                        val ids = currentListIds()
                        val isAllSelected = ids.isNotEmpty() && selectedIds.size == ids.size
                        Text(if (isAllSelected) "取消全选" else "全选", color = palette.brand)
                    }
                    TextButton(
                        onClick = { if (selectedIds.isNotEmpty()) pendingBulkDelete = true },
                        enabled = selectedIds.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = palette.semanticError)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("删除 (${selectedIds.size})", color = palette.semanticError, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.auto_90a51271), style = MaterialTheme.typography.displayLarge,
                    color = palette.textPrimary,
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
                            text = currentBaseName ?: "全部知识库", style = MaterialTheme.typography.labelMedium,
                            color = palette.brand,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = palette.brand,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.White,
            contentColor = palette.brand,
            divider = { HorizontalDivider(color = palette.bgSubtle) }
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
                    stringResource(R.string.auto_d2d08fd2) +
                        stringResource(R.string.auto_b2a9c854) +
                        stringResource(R.string.auto_81a39925)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    performSingleDelete(req)
                    pendingSingleDelete = null
                }) {
                    Text(stringResource(R.string.auto_3755f56f), color = palette.semanticError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSingleDelete = null }) { Text(stringResource(R.string.auto_4d0b4688)) }
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
                    stringResource(R.string.auto_1716d047) +
                        stringResource(R.string.auto_798b5e90)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    performBulkDelete()
                    pendingBulkDelete = false
                }) {
                    Text(stringResource(R.string.auto_a5c2dc91), color = palette.semanticError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkDelete = false }) { Text(stringResource(R.string.auto_4d0b4688)) }
            }
        )
    }

    // Knowledge-base scope picker. A plain AlertDialog avoids the
    // ExposedDropdownMenuBox anchor-typing crash we hit when the inner
    // anchor was a Surface instead of a TextField.
    if (showKbMenu) {
        AlertDialog(
            onDismissRequest = { showKbMenu = false },
            title = { Text(stringResource(R.string.auto_07b1aeaf)) },
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
                            if (currentKbId == null) Icons.Default.Check else Icons.Default.BubbleChart,
                            contentDescription = null,
                            tint = if (currentKbId == null) palette.brand else palette.textMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.auto_6a782196),
                            fontSize = 15.sp,
                            fontWeight = if (currentKbId == null) FontWeight.Bold else FontWeight.Normal,
                            color = palette.textPrimary
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
                                if (base.id == currentKbId) Icons.Default.Check else Icons.Default.BubbleChart,
                                contentDescription = null,
                                tint = if (base.id == currentKbId) palette.brand else palette.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                base.name,
                                fontSize = 15.sp,
                                fontWeight = if (base.id == currentKbId) FontWeight.Bold else FontWeight.Normal,
                                color = palette.textPrimary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKbMenu = false }) { Text(stringResource(R.string.auto_6c14bd7f)) }
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
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    val isAllSelected = totalCount > 0 && selectedCount == totalCount
    Surface(
        color = palette.bgSubtle,
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
                    .background(if (isAllSelected) palette.brand else Color.White)
                    .border(
                        BorderStroke(
                            1.dp,
                            if (totalCount == 0) palette.borderDefault else palette.brand
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isAllSelected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                } else {
                    Icon(Icons.Default.DoneAll, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isAllSelected) stringResource(R.string.auto_883620e4, tabLabel) else stringResource(R.string.auto_661a17cd, tabLabel), style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary
                )
                Text(
                    text = if (selectedCount > 0) stringResource(R.string.auto_613887a3, selectedCount, totalCount)
                    else stringResource(R.string.auto_f8978b9d, totalCount), style = MaterialTheme.typography.labelSmall,
                    color = palette.textMuted
                )
            }
            if (selectedCount > 0) {
                TextButton(
                    onClick = onDelete,
                    enabled = selectedCount > 0
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.semanticError)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除 ($selectedCount)", color = palette.semanticError, fontWeight = FontWeight.Bold)
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

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    if (entities.isEmpty()) {
        EmptyState("暂无提取的实体或概念")
    } else {
        val topGroups = remember(entities) {
            entities
                .groupBy { it.type.topLevelEntityKind() }
                .toList()
                .sortedWith(
                    compareBy<Pair<String, List<KnowledgeEntityEntity>>> { if (it.first == "entity") 0 else 1 }
                        .thenBy { it.first.entityKindLabel() }
                )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            topGroups.forEach { (kind, groupedByKind) ->
                item(key = "kind-section-$kind") {
                    EntityKindSection(
                        kind = kind,
                        entities = groupedByKind,
                        selectedIds = selectedIds,
                        isSelectionMode = isSelectionMode,
                        onToggleSelect = onToggleSelect,
                        onDeleteSingle = onDeleteSingle,
                        onViewDetail = onViewDetail
                    )
                }
            }
        }
    }
}

/**
 * 顶层实体/概念分组的渲染。先把所有节点按「实体 / 概念」汇聚,
 * 每个大组内再保留原来的语义类型细分和折叠逻辑。
 */
@Composable
private fun EntityKindSection(
    kind: String,
    entities: List<KnowledgeEntityEntity>,
    selectedIds: List<String>,
    isSelectionMode: Boolean,
    onToggleSelect: (String) -> Unit,
    onDeleteSingle: (String) -> Unit,
    onViewDetail: (String) -> Unit
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    var expanded by remember(kind) { mutableStateOf(true) }
    val subGroups = remember(entities) {
        entities
            .groupBy { it.type.normalizedEntityType() }
            .toList()
            .sortedWith(
                compareBy<Pair<String, List<KnowledgeEntityEntity>>> { it.first.entityTypeSortOrder() }
                    .thenBy { it.first.entityTypeLabel() }
            )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EntityKindHeader(
            title = kind.entityKindLabel(),
            count = entities.size,
            color = kind.entityKindColor(palette),
            expanded = expanded,
            onToggle = { expanded = !expanded }
        )
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                subGroups.forEach { (type, groupedEntities) ->
                    EntityGroupSection(
                        type = type,
                        groupedEntities = groupedEntities,
                        selectedIds = selectedIds,
                        isSelectionMode = isSelectionMode,
                        onToggleSelect = onToggleSelect,
                        onDeleteSingle = onDeleteSingle,
                        onViewDetail = onViewDetail
                    )
                }
            }
        }
    }
}

/**
 * 单个语义类型分组的渲染（标题 + 卡片 + 展开/收起）。`expanded` 状态用
 * `remember(type) { mutableStateOf(false) }` 持有——每组独立一份,
 * 切到别的 KB 再回来时也会重置。
 */
@Composable
private fun EntityGroupSection(
    type: String,
    groupedEntities: List<KnowledgeEntityEntity>,
    selectedIds: List<String>,
    isSelectionMode: Boolean,
    onToggleSelect: (String) -> Unit,
    onDeleteSingle: (String) -> Unit,
    onViewDetail: (String) -> Unit
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    var expanded by remember(type) { mutableStateOf(false) }
    val visible = if (expanded) groupedEntities else groupedEntities.take(MAX_VISIBLE_PER_GROUP)
    val expandable = groupedEntities.size > MAX_VISIBLE_PER_GROUP

    // 整个 group section 内部用 Column 而不是再开 LazyColumn——这里
    // 元素个数受 MAX_VISIBLE_PER_GROUP 上限(默认 3)控制,普通 Column
    // 性能完全够用,同时让 remember 上下文和 item 块分离得干净。
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EntityGroupHeader(
            title = type.entityTypeLabel(),
            count = groupedEntities.size,
            shownCount = visible.size,
            color = type.entityTypeColor(palette),
            expanded = expanded,
            expandable = expandable,
            onToggle = { expanded = !expanded }
        )
        // 卡片列表
        for (entity in visible) {
            val isSelected = selectedIds.contains(entity.id)
            // 卡片高度 = 原来 padding(16.dp) + 40dp 图标 ≈ 72dp。
            // 现在改成 10dp padding + 28dp 图标 ≈ 48dp,视觉密度约 60%。
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) Color(0xFFE0F2FE) else Color.White,
                shadowElevation = 0.5.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (isSelectionMode) onToggleSelect(entity.id) else onViewDetail(entity.name)
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelectionMode) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) palette.brand else palette.bgSubtle)
                                .clickable { onToggleSelect(entity.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(type.entityTypeColor(palette).copy(alpha = 0.12f), RoundedCornerShape(spacing.sm)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (type == "concept") Icons.Default.Category else Icons.Default.BubbleChart,
                            contentDescription = null,
                            tint = type.entityTypeColor(palette),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            entity.name, style = MaterialTheme.typography.titleSmall,
                            color = palette.textPrimary,
                            maxLines = 1
                        )
                        Text(
                            "${type.entityTypeLabel()} · 权重 ${entity.weight.toInt()}", style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            maxLines = 1
                        )
                    }
                    if (!isSelectionMode) {
                        IconButton(
                            onClick = { onDeleteSingle(entity.id) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = Color(0xFFD1D5DB),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
        // 折叠时如果被隐藏的条目超过 0,显示"展开更多 (N)"的次级入口;
        // 展开时显示"收起"。复用下面 RelationList / CommunityList 用的
        // ExpandToggleRow——三处行为完全一致,不分叉代码。
        if (expandable) {
            ExpandToggleRow(
                expanded = expanded,
                remaining = groupedEntities.size - MAX_VISIBLE_PER_GROUP,
                onToggle = { expanded = !expanded }
            )
        }
    }
}

@Composable
private fun EntityKindHeader(
    title: String,
    count: Int,
    color: Color,
    expanded: Boolean,
    onToggle: () -> Unit
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(spacing.md),
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(color.copy(alpha = 0.12f), RoundedCornerShape(spacing.sm)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (title == "概念") Icons.Default.Category else Icons.Default.BubbleChart,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
            Spacer(modifier = Modifier.width(8.dp))
            Surface(color = color.copy(alpha = 0.12f), shape = CircleShape) {
                Text(
                    "$count", style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = palette.textMuted,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
    }
}

private const val MAX_VISIBLE_PER_GROUP = 3

@Composable
private fun EntityGroupHeader(
    title: String,
    count: Int,
    shownCount: Int,
    color: Color,
    expanded: Boolean,
    expandable: Boolean,
    onToggle: () -> Unit
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    // 整个头部是点击区:点击切换展开/折叠。仅在有 > MAX_VISIBLE_PER_GROUP
    // 条时才有视觉效果(箭头旋转、显示"已折叠 N 条"),否则就是个普通小标题。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = expandable, onClick = onToggle)
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            color = color.copy(alpha = 0.12f),
            shape = RoundedCornerShape(spacing.md)
        ) {
            Text(
                text = "$count", style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        if (expandable) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (expanded) "已展开全部" else "已折叠 ${count - shownCount} 条", style = MaterialTheme.typography.labelSmall,
                color = palette.textMuted
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = palette.textMuted,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
            HorizontalDivider(
                modifier = Modifier.width(96.dp),
                color = palette.borderDefault
            )
        }
    }
}

private fun String.normalizedEntityType(): String =
    normalizeKnowledgeEntityType(this)

private fun String.topLevelEntityKind(): String =
    knowledgeEntityTopLevelKind(this)

private val conceptTypeNames = KNOWLEDGE_CONCEPT_TYPE_NAMES

private fun String.entityKindLabel(): String = when (this) {
    "concept" -> "概念"
    else -> "实体"
}

private fun String.entityKindColor(palette: Palette): Color = when (this) {
    "concept" -> palette.semanticSuccess
    else -> Color(0xFF0284C7)
}

private fun String.entityTypeSortOrder(): Int = when (this) {
    "entity", "person", "organization", "org", "location", "place", "event", "source" -> 0
    in conceptTypeNames -> 1
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
    "method" -> "方法"
    "technique" -> "技术"
    "theory" -> "理论"
    "principle" -> "原则"
    "framework" -> "框架"
    "problem" -> "问题"
    "pattern" -> "模式"
    "protocol" -> "协议"
    "metric" -> "指标"
    "algorithm" -> "算法"
    "mechanism" -> "机制"
    "model" -> "模型"
    "process" -> "过程"
    "heuristic" -> "启发式"
    "phenomenon" -> "现象"
    else -> replaceFirstChar { char -> char.uppercase() }
}

private fun String.entityTypeColor(palette: Palette): Color = when (this) {
    in conceptTypeNames -> palette.semanticSuccess
    "person" -> Color(0xFFDB2777)
    "organization", "org" -> Color(0xFF7C3AED)
    "location", "place" -> Color(0xFF0891B2)
    "event" -> palette.semanticWarning
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

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    if (relations.isEmpty()) {
        EmptyState("暂无识别的关联关系")
    } else {
        // 关联关系只有"全部关系"一个分组,仍然应用"最多 3 条 + 折叠"规则,
        // 与实体/概念分组保持一致的体感。
        var expanded by remember { mutableStateOf(false) }
        val visible = if (expanded) relations else relations.take(MAX_VISIBLE_PER_GROUP)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "relation-header") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.auto_cd549def),
                    total = relations.size,
                    shown = visible.size,
                    expanded = expanded,
                    expandable = relations.size > MAX_VISIBLE_PER_GROUP,
                    onToggle = { expanded = !expanded }
                )
            }
            items(visible, key = { it.id }) { relation ->
                val isSelected = selectedIds.contains(relation.id)
                val fromName = entityNameMap[relation.fromEntityId] ?: "未知实体"
                val toName = entityNameMap[relation.toEntityId] ?: "未知实体"

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) Color(0xFFE0F2FE) else Color.White,
                    shadowElevation = 0.5.dp,
                    modifier = Modifier.clickable {
                        if (isSelectionMode) onToggleSelect(relation.id)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSelectionMode) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) palette.brand else palette.bgSubtle),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.BubbleChart, contentDescription = null, tint = Color(0xFF6366F1), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(relation.relationType, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF6366F1), maxLines = 1)
                                Spacer(modifier = Modifier.weight(1f))
                                Text("置信度 ${(relation.confidence * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = palette.textTertiary, maxLines = 1)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = palette.bgSubtle,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        fromName, style = MaterialTheme.typography.labelMedium,
                                        color = palette.textPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = palette.textMuted
                                )
                                Surface(
                                    color = palette.bgSubtle,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        toName, style = MaterialTheme.typography.labelMedium,
                                        color = palette.textPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        if (!isSelectionMode) {
                            IconButton(
                                onClick = { onDeleteSingle(relation.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFD1D5DB), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            // 折叠时显示"展开更多",展开时显示"收起"——和 EntityList 行为一致。
            if (relations.size > MAX_VISIBLE_PER_GROUP) {
                item(key = "relation-toggle") {
                    ExpandToggleRow(
                        expanded = expanded,
                        remaining = relations.size - MAX_VISIBLE_PER_GROUP,
                        onToggle = { expanded = !expanded }
                    )
                }
            }
        }
    }
}

/**
 * 通用折叠小标题(供 RelationList / CommunityList 共用,逻辑和
 * EntityGroupHeader 一致,但没有"按类型"的概念,所以更轻量)。
 */
@Composable
private fun CollapsibleSectionHeader(
    title: String,
    total: Int,
    shown: Int,
    expanded: Boolean,
    expandable: Boolean,
    onToggle: () -> Unit
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = expandable, onClick = onToggle)
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            color = Color(0xFFE0F2FE),
            shape = RoundedCornerShape(spacing.md)
        ) {
            Text(
                text = "$total", style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.brand,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        if (expandable) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (expanded) "已展开全部" else "已折叠 ${total - shown} 条", style = MaterialTheme.typography.labelSmall,
                color = palette.textMuted
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = palette.textMuted,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
    }
}

@Composable
private fun ExpandToggleRow(expanded: Boolean, remaining: Int, onToggle: () -> Unit) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    TextButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        if (expanded) {
            Text(stringResource(R.string.auto_5d581564), style = MaterialTheme.typography.labelMedium, color = palette.textMuted)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = palette.textMuted,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(180f)
            )
        } else {
            Text(
                "展开更多（还有 $remaining 条）", style = MaterialTheme.typography.labelMedium,
                color = palette.brand
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = palette.brand,
                modifier = Modifier.size(16.dp)
            )
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

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    if (communities.isEmpty()) {
        EmptyState("暂无形成的主题群")
    } else {
        // 主题群只有"全部主题群"一个分组——但仍然用 60% 高度 + 折叠 3 条规则。
        var expanded by remember { mutableStateOf(false) }
        val visible = if (expanded) communities else communities.take(MAX_VISIBLE_PER_GROUP)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "community-header") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.auto_989adfc8),
                    total = communities.size,
                    shown = visible.size,
                    expanded = expanded,
                    expandable = communities.size > MAX_VISIBLE_PER_GROUP,
                    onToggle = { expanded = !expanded }
                )
            }
            items(visible, key = { it.id }) { community ->
                val isSelected = selectedIds.contains(community.id)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) Color(0xFFE0F2FE) else Color.White,
                    shadowElevation = 0.5.dp,
                    modifier = Modifier.clickable {
                        if (isSelectionMode) onToggleSelect(community.id)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSelectionMode) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) palette.brand else palette.bgSubtle),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(community.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(community.summary, style = MaterialTheme.typography.labelMedium, color = palette.textSecondary, lineHeight = 18.sp, maxLines = 2)
                        }
                        if (!isSelectionMode) {
                            IconButton(
                                onClick = { onDeleteSingle(community.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFD1D5DB), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            if (communities.size > MAX_VISIBLE_PER_GROUP) {
                item(key = "community-toggle") {
                    ExpandToggleRow(
                        expanded = expanded,
                        remaining = communities.size - MAX_VISIBLE_PER_GROUP,
                        onToggle = { expanded = !expanded }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.BubbleChart, contentDescription = null, modifier = Modifier.size(48.dp), tint = palette.borderBrand)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text, fontSize = 14.sp, color = palette.textTertiary)
        }
    }
}
