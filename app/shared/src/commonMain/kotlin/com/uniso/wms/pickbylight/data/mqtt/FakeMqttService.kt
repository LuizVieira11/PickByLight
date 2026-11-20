package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.esp.ContractResult
import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigGet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResultStatus
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import com.uniso.wms.pickbylight.data.mqtt.model.DiscoveryRequest
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoAcender
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoApagar
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoPing
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoTesteLed
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import com.uniso.wms.pickbylight.data.settings.AppSettings
import com.uniso.wms.pickbylight.util.AppBuildConfig
import com.uniso.wms.pickbylight.util.generateUUID
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
        syncProtocol()
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
        syncProtocol()
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
        syncProtocol()
    }

    // ---- Gerenciamento de ESPs (Etapa 3a: só o caminho feliz; cenários de erro ficam na 3b) ----

    /** Protocolo que o broker simulado negocia ao conectar. Mude antes de [connect]/[setConnectionState]. */
    var brokerProtocol: MqttProtocol = MqttProtocol.V5_0

    private val _negotiatedProtocol = MutableStateFlow<MqttProtocol?>(null)
    override val negotiatedProtocol: StateFlow<MqttProtocol?> = _negotiatedProtocol.asStateFlow()

    private val _capabilities = MutableStateFlow(MqttCapabilities.NONE)
    override val capabilities: StateFlow<MqttCapabilities> = _capabilities.asStateFlow()

    private val _espDevices = MutableStateFlow<List<EspDevice>>(emptyList())
    override val espDevices: StateFlow<List<EspDevice>> = _espDevices.asStateFlow()

    private val virtualEsps = linkedMapOf<String, VirtualEsp>()
    private val knownDevices = linkedMapOf<String, EspDevice>()

    // Mensagens v1 publicadas (tópico, payload). Separado de [sentCommands], que guarda só o legado.
    private val _sentManagement = mutableListOf<Pair<String, String>>()
    val sentManagement: List<Pair<String, String>> get() = _sentManagement.toList()

    init {
        syncProtocol()
    }

    /** Registra um ESP virtual. Ele só aparece em [espDevices] depois de [descobrirEsps] ou [obterConfig]. */
    fun addVirtualEsp(esp: VirtualEsp) {
        virtualEsps[esp.espId] = esp
    }

    override suspend fun descobrirEsps(): Result<List<EspDevice>> {
        val payload = EspContract.encodeDiscoveryRequest(DiscoveryRequest(EspContract.SCHEMA_VERSION, generateUUID()))
            .toResult().getOrElse { return Result.failure(it) }
        publishManagement(MqttTopics.V1_DISCOVERY_REQUEST, payload).onFailure { return Result.failure(it) }
        virtualEsps.values.forEach { refreshDevice(it) }
        return Result.success(_espDevices.value)
    }

    override suspend fun obterConfig(espId: String): Result<MqttOutcome<ConfigState>> {
        requireEspId(espId).onFailure { return Result.failure(it) }
        val requestId = generateUUID()
        val payload = EspContract.encodeConfigGet(ConfigGet(EspContract.SCHEMA_VERSION, espId, requestId))
            .toResult().getOrElse { return Result.failure(it) }
        publishManagement(MqttTopics.espConfigGet(espId), payload).onFailure { return Result.failure(it) }
        val esp = virtualEsps[espId] ?: return noAnswer(EspManagementTimings.CONFIG_TIMEOUT_MS)
        val state = esp.state(requestId)
        refreshDevice(esp, state)
        return Result.success(MqttOutcome.Ok(state))
    }

    override suspend fun gravarConfig(set: ConfigSet): Result<MqttOutcome<ConfigResult>> {
        requireEspId(set.espId).onFailure { return Result.failure(it) }
        // encodeConfigSet recusa mais de 14 mappings: o app nunca publica o que o ESP rejeitaria.
        val payload = EspContract.encodeConfigSet(set).toResult().getOrElse { return Result.failure(it) }
        // config/set nunca vai para a fila de comandos pendentes: desconectado, falha sem enfileirar (9.2).
        publishManagement(MqttTopics.espConfigSet(set.espId), payload).onFailure { return Result.failure(it) }
        val esp = virtualEsps[set.espId] ?: return noAnswer(EspManagementTimings.CONFIG_TIMEOUT_MS)
        val result = esp.handleConfigSet(set.requestId, payload)
        if (result.status != ConfigResultStatus.OK) {
            return Result.success(MqttOutcome.Erro(result.errorCode, result))
        }
        refreshDevice(esp, esp.state(set.requestId))
        return Result.success(MqttOutcome.Ok(result))
    }

    override suspend fun testarLedComConfirmacao(
        estanteLedId: String,
        prateleiraLedId: String?,
        posicaoLedId: String?,
        cor: String,
        duracaoMs: Int
    ): Result<MqttOutcome<Unit>> {
        val payload = MqttComandoTesteLed(
            estanteLedId = estanteLedId,
            prateleiraLedId = prateleiraLedId,
            posicaoLedId = posicaoLedId,
            cor = cor,
            duracaoMs = duracaoMs
        )
        _sentCommands.add(json.encodeToString(payload))
        // Método novo para a tela de ESPs: sem fila de pendentes (um teste de LED tardio não faz sentido).
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("Broker MQTT desconectado"))
        }
        // Seção 5.1: responde o dono da posição; sem posição, o da prateleira; sem prateleira, o da estante.
        val ackId = posicaoLedId ?: prateleiraLedId ?: estanteLedId
        if (virtualEsps.values.none { it.owns(ackId) }) return noAnswer(EspManagementTimings.ACK_TIMEOUT_MS)
        return Result.success(MqttOutcome.Ok(Unit))
    }

    private fun syncProtocol() {
        val protocol = if (_connectionState.value == MqttConnectionState.CONNECTED) brokerProtocol else null
        _negotiatedProtocol.value = protocol
        _capabilities.value = MqttCapabilities.forProtocol(protocol)
    }

    private fun publishManagement(topic: String, payload: String): Result<Unit> {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("Broker MQTT desconectado"))
        }
        _sentManagement.add(topic to payload)
        return Result.success(Unit)
    }

    private fun refreshDevice(esp: VirtualEsp, state: ConfigState? = null) {
        knownDevices[esp.espId] = EspDevice(
            espId = esp.espId,
            presence = EspPresence.ONLINE,
            status = esp.status(),
            config = state ?: knownDevices[esp.espId]?.config
        )
        _espDevices.value = knownDevices.values.sortedBy { it.espId }
    }

    private fun requireEspId(espId: String): Result<Unit> =
        if (EspContract.ESP_ID_REGEX.matches(espId)) Result.success(Unit)
        else Result.failure(IllegalArgumentException("esp_id inválido: $espId"))

    /** Ninguém respondeu: espera o timeout e devolve [MqttOutcome.Timeout] (nunca "configuração vazia"). */
    private suspend fun <T> noAnswer(timeoutMs: Long): Result<MqttOutcome<T>> {
        delay(timeoutMs)
        return Result.success(MqttOutcome.Timeout)
    }

    private fun ContractResult<String>.toResult(): Result<String> = when (this) {
        is ContractResult.Valid -> Result.success(value)
        is ContractResult.Invalid -> Result.failure(IllegalArgumentException("$code: $detail"))
    }
}
