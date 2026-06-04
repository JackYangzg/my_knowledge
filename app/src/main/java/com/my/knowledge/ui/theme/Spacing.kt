package com.my.knowledge.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 4dp-based spacing scale. Prefer these names over raw `.dp` literals so
 * rhythm stays consistent. The 10/14/18/22 outliers found by the audit
 * (01-系统盘点.md §4.3) are intentionally absent — round to the nearest
 * step instead of re-introducing off-grid values.
 */
data class Spacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val huge: Dp = 48.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
