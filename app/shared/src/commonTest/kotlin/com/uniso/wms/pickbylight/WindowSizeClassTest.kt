package com.uniso.wms.pickbylight

import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.ui.responsive.WindowSizeClass
import com.uniso.wms.pickbylight.ui.responsive.WindowWidthSizeClass
import com.uniso.wms.pickbylight.ui.state.UiState
import com.uniso.wms.pickbylight.util.AppBuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowSizeClassTest {

    @Test
    fun shouldClassifyAsCompactWhenWidthLessThan600dp() {
        val sizeClass = WindowSizeClass.calculateFromSize(360.dp, 640.dp)
        assertEquals(WindowWidthSizeClass.COMPACT, sizeClass.widthSizeClass)
        assertTrue(sizeClass.widthSizeClass.isCompact)
    }

    @Test
    fun shouldClassifyAsMediumWhenWidthBetween600dpAnd840dp() {
        val sizeClass600 = WindowSizeClass.calculateFromSize(600.dp, 800.dp)
        val sizeClass840 = WindowSizeClass.calculateFromSize(840.dp, 800.dp)

        assertEquals(WindowWidthSizeClass.MEDIUM, sizeClass600.widthSizeClass)
        assertEquals(WindowWidthSizeClass.MEDIUM, sizeClass840.widthSizeClass)
        assertTrue(sizeClass600.widthSizeClass.isMedium)
    }

    @Test
    fun shouldClassifyAsExpandedWhenWidthGreaterThan840dp() {
        val sizeClass = WindowSizeClass.calculateFromSize(1024.dp, 768.dp)
        assertEquals(WindowWidthSizeClass.EXPANDED, sizeClass.widthSizeClass)
        assertTrue(sizeClass.widthSizeClass.isExpanded)
    }

    @Test
    fun shouldVerifyDefaultMqttBrokerUrlIsNotEmpty() {
        assertTrue(AppBuildConfig.MQTT_BROKER_URL.isNotBlank())
        assertTrue(AppBuildConfig.MQTT_BROKER_URL.startsWith("tcp://"))
    }

    @Test
    fun shouldInstantiateAllUiStates() {
        val loading: UiState<String> = UiState.Loading("Aguardando ESP32...")
        val empty: UiState<String> = UiState.Empty("Vazio", "Nenhum item", "Cadastrar")
        val error: UiState<String> = UiState.Error("Falha de conexão", canRetry = true)
        val success: UiState<String> = UiState.Success("OK")

        assertTrue(loading is UiState.Loading)
        assertTrue(empty is UiState.Empty)
        assertTrue(error is UiState.Error)
        assertTrue(success is UiState.Success)
    }
}
