package com.my.knowledge.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Brand-anchored color palette.
 *
 * Replaces the 30+ hardcoded `Color(0xFF...)` calls scattered across the UI
 * layer. Add new shades here rather than inlining hex values in Composables.
 *
 * Light/dark variants are switched in [My_knowledgeTheme] via [LocalPalette].
 */
data class Palette(
    val brand: Color,
    val brandSubtle: Color,
    val brandOnSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textMuted: Color,
    val textOnBrand: Color,
    val bgPage: Color,
    val bgCard: Color,
    val bgSubtle: Color,
    val bgInverse: Color,
    val borderBrand: Color,
    val borderDefault: Color,
    val borderStrong: Color,
    val semanticError: Color,
    val semanticWarning: Color,
    val semanticSuccess: Color,
    val semanticErrorBg: Color,
    val semanticErrorBorder: Color,
)

val LightPalette = Palette(
    brand = Color(0xFF147EC5),
    brandSubtle = Color(0xFFEFF7FF),
    brandOnSubtle = Color(0xFF147EC5),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF5F87A3),
    textTertiary = Color(0xFFA3A3A3),
    textMuted = Color(0xFF94A3B8),
    textOnBrand = Color(0xFFFFFFFF),
    bgPage = Color(0xFFF7FBFF),
    bgCard = Color(0xFFFFFFFF),
    bgSubtle = Color(0xFFF1F5F9),
    bgInverse = Color(0xFF111827),
    borderBrand = Color(0xFFDBEEFF),
    borderDefault = Color(0xFFE2E8F0),
    borderStrong = Color(0xFFCBD5E1),
    semanticError = Color(0xFFDC2626),
    semanticWarning = Color(0xFFEA580C),
    semanticSuccess = Color(0xFF16A34A),
    semanticErrorBg = Color(0xFFFEF2F2),
    semanticErrorBorder = Color(0xFFFECACA),
)

val DarkPalette = Palette(
    brand = Color(0xFF5BA8DC),
    brandSubtle = Color(0xFF0B2A45),
    brandOnSubtle = Color(0xFFBFE0F7),
    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFFCBD5E1),
    textTertiary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    textOnBrand = Color(0xFF0B1220),
    bgPage = Color(0xFF0B1220),
    bgCard = Color(0xFF111827),
    bgSubtle = Color(0xFF1F2937),
    bgInverse = Color(0xFFF1F5F9),
    borderBrand = Color(0xFF1E3A5F),
    borderDefault = Color(0xFF1F2937),
    borderStrong = Color(0xFF374151),
    semanticError = Color(0xFFEF4444),
    semanticWarning = Color(0xFFF59E0B),
    semanticSuccess = Color(0xFF22C55E),
    semanticErrorBg = Color(0xFF450A0A),
    semanticErrorBorder = Color(0xFF7F1D1D),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }
