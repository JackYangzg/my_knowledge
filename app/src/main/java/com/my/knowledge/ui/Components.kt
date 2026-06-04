package com.my.knowledge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing

@Composable
fun PageHeader(
    title: String,
    hint: String? = null,
    action: @Composable (() -> Unit)? = null,
    back: @Composable (() -> Unit)? = null
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg)
            .padding(top = spacing.huge, bottom = spacing.md)
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                back?.invoke()
                Text(
                    text = title,
                    style = MaterialTheme.typography.displayLarge,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                hint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = spacing.xs)
                    )
                }
            }
            action?.invoke()
        }
    }
}

@Composable
fun QuietCell(
    icon: ImageVector? = null,
    title: String,
    desc: String? = null,
    leftContent: @Composable (() -> Unit)? = null,
    right: @Composable (() -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    Surface(
        onClick = onClick,
        color = palette.bgCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            if (leftContent != null) {
                leftContent()
            } else if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(spacing.md))
                        .background(palette.brandSubtle)
                        .border(1.dp, palette.borderBrand, RoundedCornerShape(spacing.md)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = palette.brand
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    right?.invoke()
                }
                desc?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = spacing.xs)
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = palette.textMuted
            )
        }
    }
}

@Composable
fun Section(
    title: String,
    more: String? = null,
    onMoreClick: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.padding(top = spacing.xxl)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = palette.textSecondary
            )
            more?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.brand,
                    modifier = Modifier.clickable { onMoreClick() }
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgCard)
                .border(0.5.dp, palette.borderBrand)
        ) {
            content()
        }
    }
}

@Composable
fun SoftTag(text: String) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    Surface(
        color = palette.brandSubtle,
        shape = CircleShape,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = palette.brand,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = spacing.xs)
        )
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
