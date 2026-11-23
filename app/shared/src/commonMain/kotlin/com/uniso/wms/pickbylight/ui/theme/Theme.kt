package com.uniso.wms.pickbylight.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily

private val LightColorScheme = lightColorScheme(
    primary = ActionGraphite,
    onPrimary = OnStrong,
    primaryContainer = SurfaceMuted,
    onPrimaryContainer = Ink,
    secondary = BrandWine,
    onSecondary = OnStrong,
    secondaryContainer = SurfaceMuted,
    onSecondaryContainer = Ink,
    tertiary = OkTeal,
    onTertiary = OnStrong,
    tertiaryContainer = SurfaceMuted,
    onTertiaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = SurfaceCard,
    onSurface = Ink,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = InkMuted,
    surfaceTint = SurfaceCard,
    surfaceBright = SurfaceCard,
    surfaceDim = SurfaceMuted,
    surfaceContainerLowest = SurfaceCard,
    surfaceContainerLow = SurfaceCard,
    surfaceContainer = SurfaceCard,
    surfaceContainerHigh = SurfaceMuted,
    surfaceContainerHighest = SurfaceMuted,
    outline = LineOutline,
    outlineVariant = SurfaceMuted,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = Paper,
    error = AlarmRed,
    onError = OnStrong,
    errorContainer = AlarmRed,
    onErrorContainer = OnStrong
)

@Composable
fun WmsTheme(
    fontScale: Float = 1.0f,
    fontFamily: FontFamily = rememberBarlowFontFamily(),
    content: @Composable () -> Unit
) {
    val typography = remember(fontFamily, fontScale) { createWmsTypography(fontFamily, fontScale) }
    val dimensions = WmsDimensions()

    CompositionLocalProvider(
        LocalWmsDimensions provides dimensions
    ) {
        MaterialTheme(
            colorScheme = LightColorScheme,
            typography = typography,
            content = content
        )
    }
}

