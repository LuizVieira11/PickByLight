package com.uniso.wms.pickbylight.data.mqtt

import kotlinx.coroutines.flow.StateFlow

enum class MqttConnectionState {
    CONNECTED,
    DISCONNECTED,
    CONNECTING,
    ERROR
}

interface MqttService {
    val connectionState: StateFlow<MqttConnectionState>
    val pendingCommandsCount: StateFlow<Int>

    /**
     * Conecta ao broker MQTT.
     * @param brokerUrl URL do broker. Se null, usa o valor persistido em DataStore/SharedPreferences.
     *                  Se nenhum valor estiver persistido, usa fallback do BuildConfig (não hardcoded na interface).
     */
    suspend fun connect(brokerUrl: String? = null)
    suspend fun acenderLed(estanteLedId: String, prateleiraLedId: String, posicaoLedId: String, cor: String = "VERDE"): Result<Unit>
    suspend fun apagarLed(estanteLedId: String, prateleiraLedId: String, posicaoLedId: String): Result<Unit>
    suspend fun testarLed(estanteLedId: String, prateleiraLedId: String?, posicaoLedId: String?, cor: String = "AZUL", duracaoMs: Int = 3000): Result<Unit>
    suspend fun enviarPing(): Result<Long> // Retorna latência em ms
    fun disconnect()
}
