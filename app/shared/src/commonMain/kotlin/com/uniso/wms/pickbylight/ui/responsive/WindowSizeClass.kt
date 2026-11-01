package com.uniso.wms.pickbylight.ui.responsive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Classes de tamanho de janela conforme a Seção 1.2 da especificação:
 * - COMPACT: < 600dp (Smartphones e Coletores Mobile)
 * - MEDIUM: 600dp a 840dp (Tablets Pequenos / Dobráveis)
 * - EXPANDED: > 840dp (Tablets Industriais e Desktop/Web)
 */
enum class WindowWidthSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED;

    val isCompact: Boolean get() = this == COMPACT
    val isMedium: Boolean get() = this == MEDIUM
    val isExpanded: Boolean get() = this == EXPANDED
}

data class WindowSizeClass(
    val widthSizeClass: WindowWidthSizeClass,
    val widthDp: Dp,
    val heightDp: Dp
) {
    companion object {
        fun calculateFromSize(width: Dp, height: Dp): WindowSizeClass {
            val widthClass = when {
                width < 600.dp -> WindowWidthSizeClass.COMPACT
                width <= 840.dp -> WindowWidthSizeClass.MEDIUM
                else -> WindowWidthSizeClass.EXPANDED
            }
            return WindowSizeClass(
                widthSizeClass = widthClass,
                widthDp = width,
                heightDp = height
            )
        }
    }
}

val LocalWindowSizeClass = compositionLocalOf<WindowSizeClass> {
    WindowSizeClass.calculateFromSize(360.dp, 640.dp)
}

@Composable
fun ResponsiveContainer(
    modifier: Modifier = Modifier,
    content: @Composable (WindowSizeClass) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val windowSizeClass = WindowSizeClass.calculateFromSize(maxWidth, maxHeight)
        CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
            content(windowSizeClass)
        }
    }
}
