package com.my.knowledge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.KnowledgeFragmentChainEntity
import com.my.knowledge.data.db.entity.KnowledgeFragmentGapEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import com.my.knowledge.domain.fragment.LifecycleStatus
import com.my.knowledge.ui.theme.LocalPalette
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * FRAG-1.6 — chain detail screen.
 *
 * Layout: 目标卡 → 完整度横条 → 缺口列表(每条带 inline 重新分析 TextField) → 底部按钮矩阵
 * Button matrix per `LifecycleStatus`:
 *   NEED_REVIEW      → [📥 重新分析]
 *   DISTILL_READY    → [✨ 开始提炼]
 *   RECOMMEND_READY  → [📦 归档]
 *   ARCHIVED         → [⭐ 标星 / 取消标星] [📤 分享图片] (分享图片在 FRAG-1.7 实现)
 *
 * 数据源:`chainDao.observeById(chainId)` + `gapDao.observeByChain(chainId)` +
 * `itemDao.getById(chain.distilledItemId)`(标星读源 item)。
 *
 * Worker 调度:通过宿主 [ProcessingTaskScheduler] 调度的 worker
 * 改完 chain/gap 表后,Room Flow 会自动刷新此 Composable。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FragmentChainDetailScreen(
    chainId: String,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val db = remember(context) { AppDatabase.getInstance(context) }
    val scheduler = remember(context) { ProcessingTaskScheduler(context) }

    var chain by remember { mutableStateOf<KnowledgeFragmentChainEntity?>(null) }
    var gaps by remember { mutableStateOf<List<KnowledgeFragmentGapEntity>>(emptyList()) }
    var distilledItem by remember { mutableStateOf<KnowledgeItemEntity?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(chainId) {
        db.fragmentChainDao().observeById(chainId).collectLatest { chain = it }
    }
    LaunchedEffect(chainId) {
        db.fragmentGapDao().observeByChain(chainId).collectLatest { gaps = it }
    }
    LaunchedEffect(chain?.distilledItemId) {
        val id = chain?.distilledItemId
        distilledItem = if (id.isNullOrBlank()) null else db.knowledgeItemDao().getById(id)
    }

    val status = chain?.status?.let { LifecycleStatus.fromName(it) } ?: LifecycleStatus.NEED_REVIEW
    val unresolvedGaps = gaps.count { !it.resolved }
    val totalGaps = gaps.size
    val completeness = when {
        chain == null -> 0f
        totalGaps == 0 -> 1f
        else -> 1f - (unresolvedGaps.toFloat() / totalGaps)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chain?.title?.ifBlank { "碎片整理" } ?: "碎片整理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.bgPage,
                    titleContentColor = palette.textPrimary,
                ),
            )
        },
        containerColor = palette.bgPage,
    ) { padding ->
        if (chain == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("chain 不存在或已删除", color = palette.textSecondary)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item { GoalCard(chain = chain!!, status = status) }
            item {
                CompletenessBar(
                    completeness = completeness,
                    unresolvedGaps = unresolvedGaps,
                    totalGaps = totalGaps,
                )
            }
            item {
                Text(
                    text = "⚠ 缺口 (${unresolvedGaps})",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (gaps.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("无缺口,可直接提炼", color = palette.textSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                items(gaps) { gap ->
                    GapCard(
                        gap = gap,
                        onReanalyze = { userText ->
                            busy = true
                            scheduler.scheduleGapReanalysis(chainId, userText)
                        },
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
                ActionRow(
                    status = status,
                    distilledItem = distilledItem,
                    busy = busy,
                    onReanalyze = {
                        scheduler.scheduleGapReanalysis(chainId, "整体重新分析")
                    },
                    onDistill = { scheduler.scheduleDistillation(chainId) },
                    onArchive = {
                        scope.launch {
                            db.fragmentChainDao().updateStatus(
                                chainId, LifecycleStatus.ARCHIVED.name, System.currentTimeMillis(),
                            )
                        }
                    },
                    onStar = {
                        scope.launch {
                            val item = distilledItem ?: return@launch
                            val now = System.currentTimeMillis()
                            val updated = item.copy(
                                starredAt = if (item.starredAt == null) now else null,
                                updatedAt = now,
                            )
                            db.knowledgeItemDao().insert(updated)
                            distilledItem = updated
                        }
                    },
                    onShareImage = {
                        // FRAG-1.7: 实现 share image
                        busy = true
                    },
                )
            }
        }
    }
}

@Composable
private fun GoalCard(chain: KnowledgeFragmentChainEntity, status: LifecycleStatus) {
    val palette = LocalPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, palette.borderBrand),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📌 目标", style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
                Spacer(modifier = Modifier.weight(1f))
                StatusBadge(status)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = chain.goalSummary.ifBlank { "(无目标描述)" },
                color = palette.textPrimary,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "confidence: ${"%.2f".format(chain.confidence)} · " +
                    "entity ${chain.entityCount} · source ${chain.sourceCount}",
                color = palette.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun StatusBadge(status: LifecycleStatus) {
    val palette = LocalPalette.current
    val (label, color) = when (status) {
        LifecycleStatus.NEED_REVIEW -> "待完善" to palette.brand
        LifecycleStatus.DISTILL_READY -> "可提炼" to Color(0xFF2E7D32)
        LifecycleStatus.RECOMMEND_READY -> "已推荐" to Color(0xFFEF6C00)
        LifecycleStatus.ARCHIVED -> "已归档" to Color(0xFF6D4C41)
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.12f)) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CompletenessBar(completeness: Float, unresolvedGaps: Int, totalGaps: Int) {
    val palette = LocalPalette.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "完整度",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { completeness.coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f).height(8.dp),
                color = palette.brand,
                trackColor = palette.borderBrand,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "${(completeness * 100).toInt()}% · $unresolvedGaps/$totalGaps gap",
                fontSize = 12.sp,
                color = palette.textSecondary,
            )
        }
    }
}

@Composable
private fun GapCard(
    gap: KnowledgeFragmentGapEntity,
    onReanalyze: (String) -> Unit,
) {
    val palette = LocalPalette.current
    var text by remember(gap.id) { mutableStateOf("") }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (gap.resolved) Color(0xFFE8F5E9) else Color.White,
        border = BorderStroke(1.dp, palette.borderBrand),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (gap.resolved) "✅" else "⚠",
                    fontSize = 16.sp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${gap.description} [${gap.priority}]",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "建议:${gap.suggestion}",
                fontSize = 12.sp,
                color = palette.textSecondary,
            )
            if (gap.resolved) {
                Spacer(modifier = Modifier.height(6.dp))
                val reason = when {
                    !gap.resolvedByUserText.isNullOrBlank() ->
                        "⚠️ 用户声称已补,尚未经新 ingest 验证"
                    !gap.resolvedByItemId.isNullOrBlank() ->
                        "已由新导入条目覆盖 (item=${gap.resolvedByItemId})"
                    else -> "已解决"
                }
                Text(reason, fontSize = 11.sp, color = Color(0xFF2E7D32))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("告诉我你已经补充了什么证据…", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onReanalyze(text.trim())
                            text = ""
                        }
                    },
                    enabled = text.isNotBlank(),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("提交", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    status: LifecycleStatus,
    distilledItem: KnowledgeItemEntity?,
    busy: Boolean,
    onReanalyze: () -> Unit,
    onDistill: () -> Unit,
    onArchive: () -> Unit,
    onStar: () -> Unit,
    onShareImage: () -> Unit,
) {
    val palette = LocalPalette.current
    val isStarred = distilledItem?.starredAt != null
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        when (status) {
            LifecycleStatus.NEED_REVIEW -> {
                PrimaryButton("📥 重新分析", Icons.Outlined.Refresh, palette.brand, !busy, onReanalyze)
            }
            LifecycleStatus.DISTILL_READY -> {
                PrimaryButton("✨ 开始提炼", Icons.Default.AutoAwesome, palette.brand, !busy, onDistill)
            }
            LifecycleStatus.RECOMMEND_READY -> {
                PrimaryButton("📦 归档", Icons.Outlined.Archive, Color(0xFFEF6C00), !busy, onArchive)
            }
            LifecycleStatus.ARCHIVED -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        if (isStarred) "取消标星" else "⭐ 标星",
                        if (isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                        if (isStarred) Color(0xFFFFB300) else palette.brand,
                        !busy && distilledItem != null,
                        onStar,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        "📤 分享图片",
                        Icons.Outlined.Share,
                        palette.brand,
                        !busy,
                        onShareImage,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (distilledItem == null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "该 chain 缺少 distilledItem,无法标星/分享",
                        fontSize = 11.sp,
                        color = palette.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
            disabledContainerColor = color.copy(alpha = 0.3f),
            disabledContentColor = Color.White.copy(alpha = 0.7f),
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label)
    }
}
