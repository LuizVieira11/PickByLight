package com.uniso.wms.pickbylight.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val DarkColorScheme = darkColorScheme(
    primary = IndustrialPrimaryDark,
    onPrimary = DarkTextPrimary,
    primaryContainer = IndustrialPrimary,
    onPrimaryContainer = DarkTextPrimary,
    secondary = ShelfRackBlue,
    onSecondary = DarkTextPrimary,
    tertiary = EmeraldSuccess,
    onTertiary = DarkTextPrimary,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    error = CoralError,
    onError = DarkTextPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = IndustrialPrimary,
    onPrimary = LightSurface,
    primaryContainer = IndustrialPrimaryDark,
    onPrimaryContainer = LightSurface,
    secondary = ShelfRackBlue,
    onSecondary = LightSurface,
    tertiary = EmeraldSuccessDark,
    onTertiary = LightSurface,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    error = CoralError,
    onError = LightSurface
)

@Composable
fun WmsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val typography = createWmsTypography(fontScale)
    val dimensions = WmsDimensions()

    CompositionLocalProvider(
        LocalWmsDimensions provides dimensions
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
