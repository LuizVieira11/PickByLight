package com.uniso.wms.pickbylight.util

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit
) {
    // No-op seguro para ambiente WasmJs
}
