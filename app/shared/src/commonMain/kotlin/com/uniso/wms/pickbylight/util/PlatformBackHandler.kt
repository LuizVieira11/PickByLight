package com.uniso.wms.pickbylight.util

import androidx.compose.runtime.Composable

/**
 * Interceptador do botão 'Voltar' multiplataforma.
 * No Android: delega para androidx.activity.compose.BackHandler.
 * No WasmJs: fallback seguro.
 */
@Composable
expect fun PlatformBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit
)
