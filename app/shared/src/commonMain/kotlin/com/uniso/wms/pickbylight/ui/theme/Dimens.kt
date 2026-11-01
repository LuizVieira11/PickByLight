package com.uniso.wms.pickbylight.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Diretrizes Ergonômicas Industriais (Seção 1.3 da especificação):
 * - Toque mínimo: 48dp
 * - Altura ideal de botões primários: >= 56dp
 * - Espaçamento mínimo entre botões: 12dp
 */
data class WmsDimensions(
    val minTouchTarget: Dp = 48.dp,
    val primaryButtonHeight: Dp = 56.dp,
    val minButtonSpacing: Dp = 10.dp,
    val paddingSmall: Dp = 6.dp,
    val paddingMedium: Dp = 12.dp, // Reduzido de 16.dp para 12.dp para maximizar área útil horizontal
    val paddingLarge: Dp = 18.dp, // Reduzido de 24.dp para 18.dp
    val screenHorizontalPadding: Dp = 12.dp,
    val cardCornerRadius: Dp = 12.dp,
    val buttonCornerRadius: Dp = 10.dp
)

val LocalWmsDimensions = staticCompositionLocalOf { WmsDimensions() }
