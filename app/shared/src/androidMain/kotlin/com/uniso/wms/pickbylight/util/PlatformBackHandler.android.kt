package com.uniso.wms.pickbylight.util

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit
) {
    var lastBackTime by remember { mutableStateOf(0L) }
    BackHandler(enabled = enabled) {
        val now = System.currentTimeMillis()
        if (now - lastBackTime >= 500L) {
            lastBackTime = now
            onBack()
        }
    }
}

