package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoAcender
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoApagar
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoPing
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoTesteLed
import com.uniso.wms.pickbylight.data.settings.AppSettings
import com.uniso.wms.pickbylight.util.AppBuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Implementação em memória de MqttService para commonMain (WasmJs e testes automatizados).
 * Suporta simulação de conexão, latência, timeout de ACK de 5s e registro de comandos.
 */
class FakeMqttService(
    initialState: MqttConnectionState = MqttConnectionState.CONNECTED
) : MqttService {

    private val _connectionState = MutableStateFlow(initialState)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _pendingCommandsCount = MutableStateFlow(0)
    override val pendingCommandsCount: StateFlow<Int> = _pendingCommandsCount.asStateFlow()

    // Configurações de simulação
    var simulateAckTimeout: Boolean = false
    var simulateAckError: Boolean = false
    var pingLatencyMs: Long = 25L
    var activeBrokerUrl: String = AppBuildConfig.MQTT_BROKER_URL
        private set

    // Histórico de comandos enviados em JSON
    private val _sentCommands = mutableListOf<String>()
    val sentCommands: List<String> get() = _sentCommands.toList()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun setConnectionState(state: MqttConnectionState) {
        _connectionState.value = state
    }

    fun clearSentCommands() {
        _sentCommands.clear()
    }

    override suspend fun connect(brokerUrl: String?) {
        _connectionState.value = MqttConnectionState.CONNECTING
        val explicitUrl = brokerUrl?.trim()?.takeIf { it.isNotBlank() }
        if (explicitUrl != null) {
            AppSettings.saveBrokerUrl(explicitUrl)
        }
        activeBrokerUrl = explicitUrl ?: AppSettings.resolveBrokerUrlOrFallback()
        delay(50)
        _connectionState.value = MqttConnectionState.CONNECTED
    }

    override suspend fun acenderLed(
        estanteLedId: String,
        prateleiraLedId: String,
        posicaoLedId: String,
        cor: String
    ): Result<Unit> {
        val payload = MqttComandoAcender(
            estanteLedId = estanteLedId,
            prateleiraLedId = prateleiraLedId,
            posicaoLedId = posicaoLedId,
            cor = cor
        )
        val jsonPayload = json.encodeToString(payload)
        _sentCommands.add(jsonPayload)

        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            _pendingCommandsCount.value += 1
            return Result.failure(IllegalStateException("Broker MQTT desconectado"))
        }

        if (simulateAckTimeout) {
            // Simula o timeout de ACK de 5 segundos
            delay(5000)
            return Result.failure(IllegalStateException("Hardware não respondeu (timeout ACK 5s)"))
        }

        if (simulateAckError) {
            return Result.failure(IllegalStateException("ESP32 respondeu com status ERRO"))
        }

        return Result.success(Unit)
    }

    override suspend fun apagarLed(
        estanteLedId: String,
        prateleiraLedId: String,
        posicaoLedId: String
    ): Result<Unit> {
        val payload = MqttComandoApagar(
            estanteLedId = estanteLedId,
            prateleiraLedId = prateleiraLedId,
            posicaoLedId = posicaoLedId
        )
        val jsonPayload = json.encodeToString(payload)
        _sentCommands.add(jsonPayload)

        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            _pendingCommandsCount.value += 1
            return Result.failure(IllegalStateException("Broker MQTT desconectado"))
        }

        return Result.success(Unit)
    }

    override suspend fun testarLed(
        estanteLedId: String,
        prateleiraLedId: String?,
        posicaoLedId: String?,
        cor: String,
        duracaoMs: Int
    ): Result<Unit> {
        val payload = MqttComandoTesteLed(
            estanteLedId = estanteLedId,
            prateleiraLedId = prateleiraLedId,
            posicaoLedId = posicaoLedId,
            cor = cor,
            duracaoMs = duracaoMs
        )
        val jsonPayload = json.encodeToString(payload)
        _sentCommands.add(jsonPayload)

        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            _pendingCommandsCount.value += 1
            return Result.failure(IllegalStateException("Broker MQTT desconectado"))
        }

        if (simulateAckTimeout) {
            delay(5000)
            return Result.failure(IllegalStateException("Timeout de resposta ao TESTE_LED"))
        }

        return Result.success(Unit)
    }

    override suspend fun enviarPing(): Result<Long> {
        val payload = MqttComandoPing()
        val jsonPayload = json.encodeToString(payload)
        _sentCommands.add(jsonPayload)

        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("Broker MQTT desconectado"))
        }

        delay(pingLatencyMs)
        return Result.success(pingLatencyMs)
    }

    override fun disconnect() {
        _connectionState.value = MqttConnectionState.DISCONNECTED
    }
}
