package com.my.knowledge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.ui.component.InsightRow
import com.my.knowledge.ui.component.MiniTag
import com.my.knowledge.ui.component.StatCard
import com.my.knowledge.viewmodel.KnowledgeHomeViewModel
import com.my.knowledge.viewmodel.ThreadViewModel
import androidx.compose.ui.res.stringResource
import com.my.knowledge.R
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KnowledgeContextScreen(
    homeViewModel: KnowledgeHomeViewModel,
    threadViewModel: ThreadViewModel,
    onBack: () -> Unit
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    val bases by homeViewModel.knowledgeBases.collectAsState()
    val formalBases = bases.filter { it.type != "unfiled" && it.type != "system" }
    var selectedKbId by remember { mutableStateOf<String?>(null) }

    val thread by threadViewModel.thread.collectAsState()
    val mainlines by threadViewModel.parsedMainlines.collectAsState()
    val relations by threadViewModel.parsedRelations.collectAsState()
    val gaps by threadViewModel.parsedGaps.collectAsState()
    val suggestions by threadViewModel.parsedSuggestions.collectAsState()
    val logs by threadViewModel.threadLogs.collectAsState()
    val graphEntities by threadViewModel.graphEntities.collectAsState()
    val graphRelations by threadViewModel.graphRelations.collectAsState()
    val graphCommunities by threadViewModel.graphCommunities.collectAsState()
    var showAllEvolutionLogs by remember(selectedKbId) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bgPage),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        item {
            PageHeader(
                title = stringResource(R.string.auto_93960a93),
                hint = stringResource(R.string.auto_b721d9b3),
                back = {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.brand)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.auto_11d02415), fontSize = 14.sp, color = palette.brand)
                    }
                },
                action = {
                    if (selectedKbId != null) {
                        Surface(
                            onClick = { threadViewModel.triggerManualEvolution() },
                            shape = CircleShape,
                            color = Color.White,
                            border = BorderStroke(1.dp, palette.borderBrand),
                            shadowElevation = 1.dp
                        ) {
                            Text(stringResource(R.string.auto_146d3b54), style = MaterialTheme.typography.labelMedium, color = palette.brand, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }
            )
        }

        if (formalBases.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AccountTree, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.auto_d09adbd6), color = palette.textSecondary, fontSize = 14.sp)
                        Text(stringResource(R.string.auto_19cab229), style = MaterialTheme.typography.labelMedium, color = palette.textTertiary)
                    }
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    formalBases.forEach { base ->
                        val selected = selectedKbId == base.id
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedKbId = base.id
                                threadViewModel.setKnowledgeBaseId(base.id)
                            },
                            label = { Text(base.name, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.brand,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            if (selectedKbId != null && thread != null) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        // Description
                        Surface(
                            shape = RoundedCornerShape(spacing.lg),
                            color = Color.White,
                            shadowElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.auto_153042ed), style = MaterialTheme.typography.titleSmall, color = palette.textPrimary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(thread!!.description, style = MaterialTheme.typography.labelLarge, color = palette.textSecondary, lineHeight = 20.sp)
                            }
                        }

                        // Core question
                        Surface(
                            shape = RoundedCornerShape(spacing.lg),
                            color = palette.brandSubtle,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.HelpOutline, contentDescription = null, tint = palette.brand, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(thread!!.coreQuestion, style = MaterialTheme.typography.labelLarge, color = palette.textPrimary)
                            }
                        }

                        if (mainlines.isNotEmpty()) {
                            SectionHeader("知识主线", Icons.Default.Timeline)
                            Surface(
                                shape = RoundedCornerShape(spacing.md),
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    mainlines.forEachIndexed { idx, line ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .background(palette.brand, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("${idx + 1}", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(line, style = MaterialTheme.typography.labelLarge, color = palette.textPrimary)
                                        }
                                        if (idx < mainlines.size - 1) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .padding(start = 9.dp)
                                                    .width(2.dp)
                                                    .height(12.dp)
                                                    .background(palette.borderBrand)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                    }
                                }
                            }
                        }

                        if (relations.isNotEmpty()) {
                            SectionHeader("知识关联", Icons.Default.AccountTree)
                            Surface(
                                shape = RoundedCornerShape(spacing.md),
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    relations.forEach { rel ->
                                        Row(
                                            modifier = Modifier.padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                color = palette.brandSubtle,
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(rel.from, style = MaterialTheme.typography.labelMedium, color = palette.brand, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                            }
                                            Text(" → ", style = MaterialTheme.typography.labelMedium, color = palette.textTertiary)
                                            Surface(
                                                color = palette.brandSubtle,
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(rel.to, style = MaterialTheme.typography.labelMedium, color = palette.brand, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(rel.relation, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                                        }
                                    }
                                }
                            }
                        }

                        if (graphEntities.isNotEmpty()) {
                            SectionHeader("实体关系图谱", Icons.Default.Hub)
                            Surface(
                                shape = RoundedCornerShape(spacing.md),
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        "${graphEntities.size} 个实体 · ${graphRelations.size} 条关系", style = MaterialTheme.typography.labelMedium,
                                        color = palette.textSecondary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        graphEntities.take(16).forEach { entity ->
                                            Surface(color = palette.brandSubtle, shape = CircleShape) {
                                                Text(
                                                    entity.name, style = MaterialTheme.typography.labelSmall,
                                                    color = palette.brand,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (graphCommunities.isNotEmpty()) {
                            SectionHeader("知识社区", Icons.Default.Groups)
                            Surface(
                                shape = RoundedCornerShape(spacing.md),
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    graphCommunities.take(6).forEach { community ->
                                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                            Text(community.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                                            Text(community.summary, style = MaterialTheme.typography.labelMedium, color = palette.textSecondary, modifier = Modifier.padding(top = 2.dp))
                                        }
                                    }
                                }
                            }
                        }

                        if (gaps.isNotEmpty()) {
                            SectionHeader("知识缺口", Icons.Default.WarningAmber)
                            Surface(
                                shape = RoundedCornerShape(spacing.md),
                                color = Color(0xFFFFF7ED),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    gaps.forEach { gap ->
                                        Row(
                                            modifier = Modifier.padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = palette.semanticWarning, modifier = Modifier.size(6.dp).padding(top = 6.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(gap, style = MaterialTheme.typography.labelLarge, color = Color(0xFF92400E))
                                        }
                                    }
                                }
                            }
                        }

                        if (suggestions.isNotEmpty()) {
                            SectionHeader("探索建议", Icons.Default.TipsAndUpdates)
                            Surface(
                                shape = RoundedCornerShape(spacing.md),
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    suggestions.forEach { s ->
                                        Row(
                                            modifier = Modifier.padding(vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Lightbulb, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(s, style = MaterialTheme.typography.labelLarge, color = palette.textPrimary)
                                        }
                                    }
                                }
                            }
                        }

                        if (logs.isNotEmpty()) {
                            SectionHeader("演进日志 (v${thread!!.version})", Icons.Default.History)
                            Surface(
                                shape = RoundedCornerShape(spacing.md),
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    val visibleLogs = if (showAllEvolutionLogs) logs else logs.take(2)
                                    visibleLogs.forEach { log ->
                                        Row(
                                            modifier = Modifier.padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Icon(
                                                if (log.triggerType == "manual") Icons.Default.Person else Icons.Default.Settings,
                                                contentDescription = null,
                                                tint = palette.textTertiary,
                                                modifier = Modifier.size(14.dp).padding(top = 2.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(log.summary, style = MaterialTheme.typography.labelMedium, color = palette.textPrimary)
                                                Text(
                                                    log.triggerType + " · " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(log.createdAt)), style = MaterialTheme.typography.labelSmall,
                                                    color = palette.textTertiary
                                                )
                                            }
                                        }
                                    }
                                    if (logs.size > 2) {
                                        HorizontalDivider(
                                            color = palette.bgSubtle,
                                            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                                        )
                                        TextButton(
                                            onClick = { showAllEvolutionLogs = !showAllEvolutionLogs },
                                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                if (showAllEvolutionLogs) "收起日志" else "展开全部 ${logs.size} 条日志", style = MaterialTheme.typography.labelMedium,
                                                color = palette.brand,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (selectedKbId == null) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.TouchApp, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.auto_b257119b), fontSize = 14.sp, color = palette.textSecondary)
                        }
                    }
                }
            } else {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = palette.brand)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(stringResource(R.string.auto_5b54efff), style = MaterialTheme.typography.labelLarge, color = palette.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.textSecondary)
        Spacer(modifier = Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = palette.textSecondary)
    }
}

@Composable
fun FragmentOrganizeScreen(onBack: () -> Unit) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    var filter by remember { mutableStateOf("全部") }
    val filters = listOf("全部", "待归类", "可提炼", "可归档")
    val fragments = KnowledgeManager.fragments

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bgPage), // Ocean 25
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        item {
            PageHeader(
                title = stringResource(R.string.auto_c6bce0ff),
                hint = stringResource(R.string.auto_9340db23),
                back = {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.brand)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.auto_11d02415), fontSize = 14.sp, color = palette.brand)
                    }
                },
                action = {
                    Surface(
                        onClick = {},
                        shape = CircleShape,
                        color = Color.White,
                        border = BorderStroke(1.dp, palette.borderBrand),
                        shadowElevation = 1.dp
                    ) {
                        Text(stringResource(R.string.auto_dcce9a14), style = MaterialTheme.typography.labelMedium, color = palette.brand, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            )
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(Modifier.weight(1f), fragments.size.toString(), "待整理")
                    StatCard(Modifier.weight(1f), "0", "已推荐")
                    StatCard(Modifier.weight(1f), "0", "可归档")
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filters.forEach { item ->
                        val selected = filter == item
                        Surface(
                            onClick = { filter = item },
                            shape = CircleShape,
                            color = if (selected) palette.brand else Color.White,
                            border = if (selected) null else BorderStroke(1.dp, palette.borderBrand)
                        ) {
                            Text(
                                text = item, style = MaterialTheme.typography.labelMedium,
                                color = if (selected) Color.White else palette.textSecondary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        if (fragments.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.auto_b05a59ae), color = palette.textSecondary, fontSize = 14.sp)
                }
            }
        } else {
            item {
                Section(title = stringResource(R.string.auto_73565d17)) {
                    fragments.forEachIndexed { index, fragment ->
                        if (index != 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = palette.borderBrand)
                        QuietCell(
                            icon = when {
                                fragment.sourceFile.endsWith(".md") -> Icons.Default.Description
                                else -> Icons.Default.Link
                            },
                            title = fragment.title,
                            desc = "来源: ${fragment.sourceFile}",
                            right = { MiniTag(fragment.tags.firstOrNull() ?: "待分类") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(modifier: Modifier = Modifier, value: String, label: String) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(spacing.lg),
        color = Color.White,
        border = BorderStroke(1.dp, palette.borderBrand),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = palette.textPrimary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
