package com.uniso.wms.pickbylight.data.settings

import com.uniso.wms.pickbylight.data.mqtt.MqttProtocolMode
import com.uniso.wms.pickbylight.util.generateUUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object BrokerUrlDataStore {
    private val savedUrlState = MutableStateFlow<String?>(null)
    private val sharedFlow: Flow<String?> = savedUrlState.asStateFlow()
    private val savedProtocolModeState = MutableStateFlow(MqttProtocolMode.AUTO)
    private var savedClientId: String? = null

    actual fun getBrokerUrl(): Flow<String?> = sharedFlow

    actual val savedBrokerUrlFlow: Flow<String?>
        get() = sharedFlow

    actual suspend fun saveBrokerUrl(url: String) {
        val clean = url.trim()
        savedUrlState.value = clean.ifBlank { null }
    }

    actual suspend fun getSavedBrokerUrl(): String? = savedUrlState.value

    actual fun getProtocolMode(): Flow<MqttProtocolMode> = savedProtocolModeState.asStateFlow()

    actual suspend fun saveProtocolMode(mode: MqttProtocolMode) {
        savedProtocolModeState.value = mode
    }

    actual suspend fun getSavedProtocolMode(): MqttProtocolMode = savedProtocolModeState.value

    actual suspend fun getOrCreateClientId(): String =
        savedClientId ?: "wms_mobile_${generateUUID()}".also { savedClientId = it }

    // ADR-6: Web/Wasm não persiste o inventário da feature de gerenciamento de ESPs.
    actual suspend fun saveEspInventoryJson(rawJson: String) {}

    actual suspend fun getSavedEspInventoryJson(): String? = null
}
