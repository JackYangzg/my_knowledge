package com.my.knowledge.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.ui.KnowledgeInsight
import androidx.compose.ui.res.stringResource
import com.my.knowledge.R
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing

@Composable
fun KnowledgeDigestSection(onOpenContext: () -> Unit, onOpenFragments: () -> Unit) {
    val spacing = LocalSpacing.current
    val palette = LocalPalette.current
    Column(modifier = Modifier.padding(top = spacing.xl, start = spacing.xl, end = spacing.xl)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.auto_e2ca3cf4),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.textSecondary
                )
                Text(
                    text = stringResource(R.string.auto_1cad8496),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textTertiary
                )
            }
            Text(
                text = stringResource(R.string.auto_ed2172fd),
                style = MaterialTheme.typography.labelMedium,
                color = palette.textTertiary
            )
        }

        Spacer(modifier = Modifier.height(spacing.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            DigestCard(
                modifier = Modifier.weight(1f),
                iconText = "⌘",
                title = stringResource(R.string.auto_93960a93),
                desc = "导入文件后自动提炼脉络",
                bottomText = "立即查看",
                onClick = onOpenContext
            )
            DigestCard(
                modifier = Modifier.weight(1f),
                iconText = "▦",
                title = stringResource(R.string.auto_c6bce0ff),
                desc = "18 条零散记录待归纳",
                bottomText = "继续整理",
                onClick = onOpenFragments
            )
        }
    }
}

@Composable
fun DigestCard(
    modifier: Modifier = Modifier,
    iconText: String,
    title: String,
    desc: String,
    bottomText: String,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(spacing.lg),
        color = palette.bgCard,
        border = BorderStroke(1.dp, palette.borderBrand),
        shadowElevation = 2.dp,
        modifier = modifier.height(156.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(spacing.lg)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(spacing.md))
                    .background(palette.brandSubtle)
                    .border(1.dp, palette.borderBrand, RoundedCornerShape(spacing.md)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = iconText,
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.brand
                )
            }
            Spacer(modifier = Modifier.height(spacing.md))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Clip
            )
            Spacer(modifier = Modifier.height(spacing.xs + 2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = bottomText,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.brand,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(spacing.xs))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = palette.brand
                )
            }
        }
    }
}

@Composable
fun SummaryCard(modifier: Modifier = Modifier, value: String, label: String) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(spacing.lg),
        color = palette.bgCard,
        border = BorderStroke(1.dp, palette.borderBrand),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = spacing.md, horizontal = spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 5.dp)
            )
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
        color = palette.bgCard,
        border = BorderStroke(1.dp, palette.borderBrand),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun MiniTag(text: String) {
    val palette = LocalPalette.current
    Surface(
        color = palette.brandSubtle,
        border = BorderStroke(1.dp, palette.borderBrand),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = palette.brand,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun InsightRow(insight: KnowledgeInsight) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.background(palette.bgCard).padding(spacing.xl)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = palette.brand,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.auto_14d3a72c),
                style = MaterialTheme.typography.labelLarge,
                color = palette.brand
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = insight.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary
        )
        Spacer(modifier = Modifier.height(spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            insight.keyTakeaways.forEach { point ->
                Surface(
                    color = palette.bgPage,
                    border = BorderStroke(1.dp, palette.borderBrand),
                    shape = CircleShape
                ) {
                    Text(
                        text = point,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = spacing.sm, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun KnowledgeItemRow(
    item: KnowledgeItemEntity,
    onDelete: () -> Unit = {},
    onRetry: () -> Unit = {},
    onStatusClick: () -> Unit = {},
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelectionChange: (Boolean) -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: (Offset) -> Unit = {}
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    var rowWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                rowWindowOrigin = Offset(bounds.left, bounds.top)
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { offset ->
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                        )
                        onLongClick(rowWindowOrigin + offset)
                    }
                )
            },
        color = palette.bgCard,
        border = BorderStroke(0.5.dp, palette.borderDefault)
    ) {
        Column(modifier = Modifier.padding(vertical = 10.dp, horizontal = spacing.xl)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = onSelectionChange,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    if (item.status == KnowledgeItemEntity.STATUS_FAILED) {
                        IconButton(onClick = onRetry, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "重试",
                                tint = palette.brand,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = palette.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Text(
                text = item.excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = spacing.xs)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = palette.textTertiary
                    )
                    Text(
                        text = item.sourceType,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textTertiary
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    StatusTag(
                        text = processingStatusLabel(item.status),
                        isError = item.status == KnowledgeItemEntity.STATUS_FAILED,
                        onClick = onStatusClick
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusTag(text: String, isError: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        color = if (isError) palette.semanticErrorBg else palette.brandSubtle,
        border = BorderStroke(1.dp, if (isError) palette.semanticErrorBorder else palette.borderBrand),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) palette.semanticError else palette.brand,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

private fun processingStatusLabel(status: String): String = when (status) {
    KnowledgeItemEntity.STATUS_PROCESSING -> "加工中"
    KnowledgeItemEntity.STATUS_RECOMMEND_READY -> "待归档"
    KnowledgeItemEntity.STATUS_NEED_REVIEW -> "待复核"
    KnowledgeItemEntity.STATUS_ARCHIVED -> "已归档"
    KnowledgeItemEntity.STATUS_FAILED -> "加工失败"
    KnowledgeItemEntity.STATUS_UNFILED -> "未归档"
    else -> status
}
