package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.esp.ContractResult
import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigGet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResultStatus
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import com.uniso.wms.pickbylight.data.mqtt.model.DiscoveryRequest
import com.uniso.wms.pickbylight.data.mqtt.model.EspStatusOffline
import com.uniso.wms.pickbylight.data.mqtt.model.EspStatusOnline
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoAcender
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoApagar
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoPing
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoTesteLed
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import com.uniso.wms.pickbylight.data.settings.AppSettings
import com.uniso.wms.pickbylight.util.AppBuildConfig
import com.uniso.wms.pickbylight.util.generateUUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Implementação em memória de MqttService para commonMain (WasmJs e testes automatizados).
 * Suporta simulação de conexão, latência, timeout de ACK de 5s, registro de comandos e
 * o ciclo v1 completo de gerenciamento de ESPs (presença STALE/ONLINE/OFFLINE, SUBACK,
 * reason codes v5, fallback 3.1.1, deduplicação e descarte de boot_id obsoleto).
 */
class FakeMqttService(
    initialState: MqttConnectionState = MqttConnectionState.CONNECTED,
    var protocolMode: MqttProtocolMode = MqttProtocolMode.AUTO
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
        val wasConnected = _connectionState.value == MqttConnectionState.CONNECTED
        _connectionState.value = state
        if (state != MqttConnectionState.CONNECTED) {
            isV1Subscribed = false
            failPendingManagement("Conexão MQTT perdida")
        } else if (!wasConnected) {
            isV1Subscribed = false
            demoteOnlineDevicesToStale()
        }
        syncProtocol()
    }

    fun clearSentCommands() {
        _sentCommands.clear()
    }

    override suspend fun connect(brokerUrl: String?) {
        val wasConnected = _connectionState.value == MqttConnectionState.CONNECTED
        if (wasConnected) {
            failPendingManagement("Conexão MQTT reiniciada")
        }
        _connectionState.value = MqttConnectionState.CONNECTING
        isV1Subscribed = false
        val explicitUrl = brokerUrl?.trim()?.takeIf { it.isNotBlank() }
        if (explicitUrl != null) {
            AppSettings.saveBrokerUrl(explicitUrl)
        }
        activeBrokerUrl = explicitUrl ?: AppSettings.resolveBrokerUrlOrFallback()
        delay(50)
        if (protocolMode == MqttProtocolMode.V5_0 && brokerProtocol != MqttProtocol.V5_0) {
            _connectionState.value = MqttConnectionState.ERROR
            failPendingManagement("Broker não suporta MQTT 5.0")
            syncProtocol()
            return
        }
        _connectionState.value = MqttConnectionState.CONNECTED
        demoteOnlineDevicesToStale()
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
        isV1Subscribed = false
        failPendingManagement("Conexão MQTT encerrada")
        syncProtocol()
    }

    // ---- Gerenciamento de ESPs (MQTT5_E_ESP32.md, seções 4, 5, 9, 14.1 e 16) ----

    /** Protocolo suportado pelo broker simulado. Mude antes de [connect]/[setConnectionState]. */
    var brokerProtocol: MqttProtocol = MqttProtocol.V5_0

    /** Simula reason code no SUBACK ao assinar tópicos v1 (ex.: `0x87` Not authorized). */
    var simulatedSubackReasonCode: Int? = null

    /** Simula reason code no PUBACK por tópico exato (ex.: `0x10`, `0x87`, `0x95`). */
    val simulatedPubackReasonCodes = mutableMapOf<String, Int>()

    /** ESPs registrados que ignoram requisições (simula timeout sem resposta). */
    val silentEspIds = mutableSetOf<String>()

    /** ESPs offline no broker (não respondem nem discovery nem get/set). */
    val offlineEspIds = mutableSetOf<String>()

    /** Quando `true`, cada `config/set` é entregue duas vezes ao [VirtualEsp] (reentrega QoS 1). */
    var simulateQos1DuplicateDelivery: Boolean = false

    /** Hook opcional disparado imediatamente após publicar `config/set`, antes da entrega automática. */
    var onPublishConfigSet: (suspend FakeMqttService.(set: ConfigSet, payload: String) -> Unit)? = null

    /** Hook opcional disparado imediatamente após publicar `config/get`, antes da entrega automática. */
    var onPublishConfigGet: (suspend FakeMqttService.(espId: String, requestId: String) -> Unit)? = null

    private val _negotiatedProtocol = MutableStateFlow<MqttProtocol?>(null)
    override val negotiatedProtocol: StateFlow<MqttProtocol?> = _negotiatedProtocol.asStateFlow()

    private val _capabilities = MutableStateFlow(MqttCapabilities.NONE)
    override val capabilities: StateFlow<MqttCapabilities> = _capabilities.asStateFlow()

    private val _espDevices = MutableStateFlow<List<EspDevice>>(emptyList())
    override val espDevices: StateFlow<List<EspDevice>> = _espDevices.asStateFlow()

    private val virtualEsps = linkedMapOf<String, VirtualEsp>()
    private val knownDevices = linkedMapOf<String, EspDevice>()
    private val retainedStatuses = linkedMapOf<String, EspStatusOnline>()
    private val retainedOfflines = linkedMapOf<String, EspStatusOffline>()
    private val retainedConfigs = linkedMapOf<String, ConfigState>()
    private val activeBootIdByEsp = mutableMapOf<String, String>()
    private val retiredBootIdsByEsp = mutableMapOf<String, MutableSet<String>>()
    private val pendingConfigGets = mutableMapOf<String, CompletableDeferred<ConfigState>>()
    private val pendingConfigSets = mutableMapOf<String, CompletableDeferred<ConfigResult>>()
    private var isV1Subscribed: Boolean = false

    // Mensagens v1 publicadas (tópico, payload). Separado de [sentCommands], que guarda só o legado.
    private val _sentManagement = mutableListOf<Pair<String, String>>()
    val sentManagement: List<Pair<String, String>> get() = _sentManagement.toList()

    // Trilha ordenada de eventos de protocolo (SUBSCRIBE_V1, SUBACK, RETAINED, PUBLISH, PUBACK, RECEIVE).
    private val _protocolEvents = mutableListOf<String>()
    val protocolEvents: List<String> get() = _protocolEvents.toList()

    private val _discardedObsoleteMessages = mutableListOf<String>()
    val discardedObsoleteMessages: List<String> get() = _discardedObsoleteMessages.toList()

    init {
        syncProtocol()
    }

    /** Registra um ESP virtual. Com [publishRetained] = true, grava status/state retidos no broker simulado. */
    fun addVirtualEsp(esp: VirtualEsp, publishRetained: Boolean = false) {
        virtualEsps[esp.espId] = esp
        if (publishRetained) {
            seedRetainedStatus(esp.status())
            seedRetainedConfig(esp.state())
        }
    }

    fun seedRetainedStatus(status: EspStatusOnline) {
        retainedOfflines.remove(status.espId)
        retainedStatuses[status.espId] = status
    }

    fun seedRetainedOffline(offline: EspStatusOffline) {
        retainedStatuses.remove(offline.espId)
        retainedOfflines[offline.espId] = offline
    }

    fun seedRetainedConfig(state: ConfigState) {
        retainedConfigs[state.espId] = state
    }

    fun clearProtocolEvents() {
        _protocolEvents.clear()
    }

    /** Assina explicitamente os tópicos v1 e recebe os retained do broker como STALE/OFFLINE. */
    suspend fun subscribeV1Topics(): Result<Unit> = ensureV1Subscribed()

    fun simulateIncomingStatus(payload: String, topicEspId: String, retained: Boolean = false): Boolean {
        val parsed = EspContract.parseStatusOnline(payload, topicEspId) as? ContractResult.Valid ?: return false
        _protocolEvents.add("RECEIVE(${MqttTopics.espStatus(topicEspId)})")
        return applyStatusOnline(parsed.value, fromRetained = retained)
    }

    fun simulateIncomingOffline(payload: String, topicEspId: String): Boolean {
        val parsed = EspContract.parseStatusOffline(payload, topicEspId) as? ContractResult.Valid ?: return false
        _protocolEvents.add("RECEIVE(${MqttTopics.espStatus(topicEspId)})")
        return applyStatusOffline(parsed.value)
    }

    fun simulateIncomingConfigState(payload: String, topicEspId: String, retained: Boolean = false): Boolean {
        val parsed = EspContract.parseConfigState(payload, topicEspId) as? ContractResult.Valid ?: return false
        _protocolEvents.add("RECEIVE(${MqttTopics.espConfigState(topicEspId)})")
        return applyConfigState(parsed.value, fromRetained = retained)
    }

    fun simulateIncomingConfigResult(payload: String, topicEspId: String): Boolean {
        val parsed = EspContract.parseConfigResult(payload, topicEspId) as? ContractResult.Valid ?: return false
        _protocolEvents.add("RECEIVE(${MqttTopics.espConfigResult(topicEspId)})")
        return applyConfigResult(parsed.value)
    }

    override suspend fun descobrirEsps(): Result<List<EspDevice>> {
        val payload = EspContract.encodeDiscoveryRequest(DiscoveryRequest(EspContract.SCHEMA_VERSION, generateUUID()))
            .toResult().getOrElse { return Result.failure(it) }
        ensureV1Subscribed().onFailure { return Result.failure(it) }
        publishManagement(MqttTopics.V1_DISCOVERY_REQUEST, payload).onFailure { return Result.failure(it) }
        virtualEsps.values
            .filter { it.espId !in silentEspIds && it.espId !in offlineEspIds }
            .forEach { esp ->
                val statusJson = EspContract.encodeStatusOnline(esp.status())
                simulateIncomingStatus(statusJson, esp.espId, retained = false)
            }
        return Result.success(_espDevices.value)
    }

    override suspend fun obterConfig(espId: String): Result<MqttOutcome<ConfigState>> {
        requireEspId(espId).onFailure { return Result.failure(it) }
        val requestId = generateUUID()
        val payload = EspContract.encodeConfigGet(ConfigGet(EspContract.SCHEMA_VERSION, espId, requestId))
            .toResult().getOrElse { return Result.failure(it) }

        val deferred = CompletableDeferred<ConfigState>()
        pendingConfigGets[requestId] = deferred
        try {
            ensureV1Subscribed().onFailure { return Result.failure(it) }
            val diagnostics = publishManagement(MqttTopics.espConfigGet(espId), payload)
                .getOrElse { return Result.failure(it) }

            onPublishConfigGet?.invoke(this, espId, requestId)

            val esp = virtualEsps[espId]
            if (esp != null && espId !in silentEspIds && espId !in offlineEspIds) {
                val stateJson = EspContract.encodeConfigState(esp.state(requestId))
                simulateIncomingConfigState(stateJson, espId, retained = false)
            }

            val state = withTimeoutOrNull(EspManagementTimings.CONFIG_TIMEOUT_MS) { deferred.await() }
                ?: return Result.success(MqttOutcome.Timeout(diagnostics))
            return Result.success(MqttOutcome.Ok(state, diagnostics))
        } catch (e: ConnectionLostException) {
            return Result.failure(e)
        } finally {
            pendingConfigGets.remove(requestId)
        }
    }

    override suspend fun gravarConfig(set: ConfigSet): Result<MqttOutcome<ConfigResult>> {
        requireEspId(set.espId).onFailure { return Result.failure(it) }
        // encodeConfigSet recusa mais de 14 mappings: o app nunca publica o que o ESP rejeitaria.
        val payload = EspContract.encodeConfigSet(set).toResult().getOrElse { return Result.failure(it) }

        val deferred = CompletableDeferred<ConfigResult>()
        pendingConfigSets[set.requestId] = deferred
        try {
            ensureV1Subscribed().onFailure { return Result.failure(it) }
            // config/set nunca vai para a fila de comandos pendentes: desconectado, falha sem enfileirar (9.2).
            val diagnostics = publishManagement(MqttTopics.espConfigSet(set.espId), payload)
                .getOrElse { return Result.failure(it) }

            val hook = onPublishConfigSet
            if (hook != null) {
                hook.invoke(this, set, payload)
            } else {
                val esp = virtualEsps[set.espId]
                if (esp != null && set.espId !in silentEspIds && set.espId !in offlineEspIds) {
                    val result = esp.handleConfigSet(set.requestId, payload)
                    if (simulateQos1DuplicateDelivery) {
                        esp.handleConfigSet(set.requestId, payload)
                    }
                    simulateIncomingConfigResult(EspContract.encodeConfigResult(result), set.espId)
                    if (result.status == ConfigResultStatus.OK) {
                        simulateIncomingConfigState(
                            EspContract.encodeConfigState(esp.state(set.requestId)),
                            set.espId,
                            retained = false
                        )
                    }
                }
            }

            val result = withTimeoutOrNull(EspManagementTimings.CONFIG_TIMEOUT_MS) { deferred.await() }
                ?: return Result.success(MqttOutcome.Timeout(diagnostics))
            return if (result.status != ConfigResultStatus.OK) {
                Result.success(MqttOutcome.Erro(result.errorCode, result, diagnostics))
            } else {
                Result.success(MqttOutcome.Ok(result, diagnostics))
            }
        } catch (e: ConnectionLostException) {
            return Result.failure(e)
        } finally {
            pendingConfigSets.remove(set.requestId)
        }
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
        val diagnostics = evaluatePubackReasonCode(MqttTopics.COMANDO).getOrElse { return Result.failure(it) }
        // Seção 5.1: responde o dono da posição; sem posição, o da prateleira; sem prateleira, o da estante.
        val ackId = posicaoLedId ?: prateleiraLedId ?: estanteLedId
        val activeOwner = virtualEsps.values.firstOrNull {
            it.espId !in silentEspIds && it.espId !in offlineEspIds && it.owns(ackId)
        }
        if (activeOwner == null) return noAnswer(EspManagementTimings.ACK_TIMEOUT_MS, diagnostics)
        return Result.success(MqttOutcome.Ok(Unit, diagnostics))
    }

    private fun syncProtocol() {
        val protocol = if (_connectionState.value != MqttConnectionState.CONNECTED) {
            null
        } else {
            when (protocolMode) {
                MqttProtocolMode.AUTO -> brokerProtocol
                MqttProtocolMode.V5_0 -> if (brokerProtocol == MqttProtocol.V5_0) MqttProtocol.V5_0 else null
                MqttProtocolMode.V3_1_1 -> MqttProtocol.V3_1_1
            }
        }
        _negotiatedProtocol.value = protocol
        _capabilities.value = MqttCapabilities.forProtocol(protocol)
    }

    private suspend fun ensureV1Subscribed(): Result<Unit> {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("Broker MQTT desconectado"))
        }
        if (isV1Subscribed) return Result.success(Unit)
        _protocolEvents.add("SUBSCRIBE_V1")
        delay(1)
        val subackCode = if (_capabilities.value.reasonCodes) simulatedSubackReasonCode else null
        if (subackCode != null && subackCode != 0x00) {
            _protocolEvents.add("SUBACK(0x${subackCode.toString(16)})")
            return Result.failure(IllegalStateException("SUBACK recusado pelo broker (0x${subackCode.toString(16)})"))
        }
        _protocolEvents.add("SUBACK(0x00)")
        isV1Subscribed = true
        deliverRetainedMessages()
        return Result.success(Unit)
    }

    private fun deliverRetainedMessages() {
        retainedStatuses.values.forEach { st ->
            _protocolEvents.add("RETAINED_STATUS(${st.espId})")
            applyStatusOnline(st, fromRetained = true)
        }
        retainedOfflines.values.forEach { off ->
            _protocolEvents.add("RETAINED_OFFLINE(${off.espId})")
            applyStatusOffline(off)
        }
        retainedConfigs.values.forEach { cfg ->
            _protocolEvents.add("RETAINED_STATE(${cfg.espId})")
            applyConfigState(cfg, fromRetained = true)
        }
    }

    private fun publishManagement(topic: String, payload: String): Result<List<MqttDiagnostic>> {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("Broker MQTT desconectado"))
        }
        _protocolEvents.add("PUBLISH($topic)")
        _sentManagement.add(topic to payload)
        return evaluatePubackReasonCode(topic)
    }

    private fun evaluatePubackReasonCode(topic: String): Result<List<MqttDiagnostic>> {
        if (!_capabilities.value.reasonCodes) {
            // MQTT 3.1.1 não possui reason codes em PUBACK; diagnostics sempre vazio.
            return Result.success(emptyList())
        }
        val code = simulatedPubackReasonCodes[topic] ?: return Result.success(emptyList())
        _protocolEvents.add("PUBACK(0x${code.toString(16)})")
        return when (code) {
            REASON_NO_MATCHING_SUBSCRIBERS -> Result.success(
                listOf(MqttDiagnostic(code, "No matching subscribers (0x10)", topic))
            )
            REASON_NOT_AUTHORIZED -> Result.failure(
                IllegalStateException("Publicação recusada pelo broker: Not authorized (0x87) em $topic")
            )
            REASON_PACKET_TOO_LARGE -> Result.failure(
                IllegalStateException("Publicação recusada pelo broker: Packet too large (0x95) em $topic")
            )
            else -> Result.failure(
                IllegalStateException("Publicação recusada pelo broker (0x${code.toString(16)}) em $topic")
            )
        }
    }

    private fun applyStatusOnline(status: EspStatusOnline, fromRetained: Boolean): Boolean {
        if (!acceptBootAndRevision(status.espId, status.bootId, status.configRevision, "status")) return false
        val previous = knownDevices[status.espId]
        val presence = if (fromRetained) EspPresence.STALE else EspPresence.ONLINE
        knownDevices[status.espId] = EspDevice(
            espId = status.espId,
            presence = presence,
            status = status,
            config = previous?.config
        )
        _espDevices.value = knownDevices.values.sortedBy { it.espId }
        return true
    }

    private fun applyStatusOffline(offline: EspStatusOffline): Boolean {
        if (!acceptBootAndRevision(offline.espId, offline.bootId, revision = null, source = "offline")) return false
        val previous = knownDevices[offline.espId]
        knownDevices[offline.espId] = EspDevice(
            espId = offline.espId,
            presence = EspPresence.OFFLINE,
            status = previous?.status,
            config = previous?.config
        )
        _espDevices.value = knownDevices.values.sortedBy { it.espId }
        return true
    }

    private fun applyConfigState(state: ConfigState, fromRetained: Boolean): Boolean {
        if (!acceptBootAndRevision(state.espId, state.bootId, state.configRevision, "config/state")) return false
        val previous = knownDevices[state.espId]
        val presence = when {
            !fromRetained -> EspPresence.ONLINE
            previous != null -> previous.presence
            else -> EspPresence.STALE
        }
        knownDevices[state.espId] = EspDevice(
            espId = state.espId,
            presence = presence,
            status = previous?.status,
            config = state
        )
        _espDevices.value = knownDevices.values.sortedBy { it.espId }
        if (!fromRetained) {
            state.lastRequestId?.let { reqId ->
                pendingConfigGets[reqId]?.complete(state)
            }
        }
        return true
    }

    private fun applyConfigResult(result: ConfigResult): Boolean {
        if (!acceptBootAndRevision(result.espId, result.bootId, revision = null, source = "config/result")) return false
        val deferred = pendingConfigSets[result.requestId]
        if (deferred == null) {
            _discardedObsoleteMessages.add("config/result:${result.espId}:unknown_request=${result.requestId}")
            return false
        }
        return deferred.complete(result)
    }

    private fun acceptBootAndRevision(espId: String, bootId: String, revision: Long?, source: String): Boolean {
        val retired = retiredBootIdsByEsp[espId]
        if (retired != null && bootId in retired) {
            _discardedObsoleteMessages.add("$source:$espId:retired_boot=$bootId")
            return false
        }
        val activeBoot = activeBootIdByEsp[espId]
        if (activeBoot != null && activeBoot != bootId) {
            retiredBootIdsByEsp.getOrPut(espId) { mutableSetOf() }.add(activeBoot)
            activeBootIdByEsp[espId] = bootId
            return true
        }
        if (activeBoot == null) {
            activeBootIdByEsp[espId] = bootId
        }
        if (revision != null) {
            val previous = knownDevices[espId]
            val maxKnownRev = listOfNotNull(previous?.config?.configRevision, previous?.status?.configRevision).maxOrNull()
            if (maxKnownRev != null && revision < maxKnownRev) {
                _discardedObsoleteMessages.add("$source:$espId:old_revision=$revision<$maxKnownRev")
                return false
            }
        }
        return true
    }

    private fun demoteOnlineDevicesToStale() {
        var changed = false
        for ((id, dev) in knownDevices.entries.toList()) {
            if (dev.presence == EspPresence.ONLINE) {
                knownDevices[id] = dev.copy(presence = EspPresence.STALE)
                changed = true
            }
        }
        if (changed) {
            _espDevices.value = knownDevices.values.sortedBy { it.espId }
        }
    }

    private fun failPendingManagement(reason: String) {
        val ex = ConnectionLostException(reason)
        pendingConfigGets.values.toList().forEach { it.completeExceptionally(ex) }
        pendingConfigGets.clear()
        pendingConfigSets.values.toList().forEach { it.completeExceptionally(ex) }
        pendingConfigSets.clear()
    }

    private fun requireEspId(espId: String): Result<Unit> =
        if (EspContract.ESP_ID_REGEX.matches(espId)) Result.success(Unit)
        else Result.failure(IllegalArgumentException("esp_id inválido: $espId"))

    /** Ninguém respondeu: espera o timeout e devolve [MqttOutcome.Timeout] (nunca "configuração vazia"). */
    private suspend fun <T> noAnswer(
        timeoutMs: Long,
        diagnostics: List<MqttDiagnostic> = emptyList()
    ): Result<MqttOutcome<T>> {
        delay(timeoutMs)
        return Result.success(MqttOutcome.Timeout(diagnostics))
    }

    private fun ContractResult<String>.toResult(): Result<String> = when (this) {
        is ContractResult.Valid -> Result.success(value)
        is ContractResult.Invalid -> Result.failure(IllegalArgumentException("$code: $detail"))
    }

    private class ConnectionLostException(message: String) : IllegalStateException(message)

    companion object {
        const val REASON_NO_MATCHING_SUBSCRIBERS = 0x10
        const val REASON_NOT_AUTHORIZED = 0x87
        const val REASON_PACKET_TOO_LARGE = 0x95
    }
}
