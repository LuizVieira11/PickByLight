package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.esp.ContractResult
import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.esp.EspInventoryCodec
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigGet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResultStatus
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import com.uniso.wms.pickbylight.data.mqtt.model.DiscoveryRequest
import com.uniso.wms.pickbylight.data.mqtt.model.MqttAck
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoAcender
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoApagar
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoPing
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoTesteLed
import com.uniso.wms.pickbylight.data.mqtt.model.MqttStatusApp
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import com.uniso.wms.pickbylight.data.settings.AppSettings
import com.uniso.wms.pickbylight.util.AppBuildConfig
import com.uniso.wms.pickbylight.util.currentTimeMillis
import com.uniso.wms.pickbylight.util.generateUUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementação nativa Android sobre [MqttTransport] (Paho v3 na Etapa 4a; adapter v3/v5 na 4b).
 * Atende às Seções 4.1, 4.2 e 5.1/5.2 da especificação:
 * - QoS 1 em todos os comandos e confirmações
 * - LWT em 'wms/pickbylight/status/app' (Retained=true, QoS 1)
 * - Reconexão com Backoff Exponencial (1s, 2s, 4s, 8s, máx 16s) limitada a 5 tentativas
 * - Fila de retry (máx 3 tentativas)
 * - Timeout de ACK de 5 segundos
 * - Execução em [ioDispatcher] (Dispatchers.IO em produção) para prevenção de ANR
 */
class PahoMqttServiceImpl(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val transportFactory: (MqttProtocol) -> MqttTransport = { protocol ->
        when (protocol) {
            MqttProtocol.V5_0 -> PahoV5Transport()
            MqttProtocol.V3_1_1 -> PahoV3Transport()
        }
    },
    private val clientIdProvider: suspend () -> String = { AppSettings.getOrCreateClientId() },
    private val protocolModeProvider: suspend () -> MqttProtocolMode = { AppSettings.resolveProtocolMode() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val inventoryLoader: (suspend () -> String?)? = null,
    private val inventorySaver: (suspend (String) -> Unit)? = null,
    private val inventorySaveDebounceMs: Long = 500L
) : MqttService {

    private val _connectionState = MutableStateFlow(MqttConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _pendingCommandsCount = MutableStateFlow(0)
    override val pendingCommandsCount: StateFlow<Int> = _pendingCommandsCount.asStateFlow()

    private val _negotiatedProtocol = MutableStateFlow<MqttProtocol?>(null)
    override val negotiatedProtocol: StateFlow<MqttProtocol?> = _negotiatedProtocol.asStateFlow()

    private val _capabilities = MutableStateFlow(MqttCapabilities.NONE)
    override val capabilities: StateFlow<MqttCapabilities> = _capabilities.asStateFlow()

    private val espRegistry = EspRegistry()
    private val registryLock = Any()
    override val espDevices: StateFlow<List<EspDevice>> = espRegistry.espDevices

    internal val discardedObsoleteMessages: List<String>
        get() = synchronized(registryLock) { espRegistry.discardedObsoleteMessages }

    private val v1SubscribeMutex = Mutex()
    @Volatile
    private var isV1Subscribed: Boolean = false
    @Volatile
    private var v1SubscriptionsRequested: Boolean = false

    private val inventoryLoadMutex = Mutex()
    private val inventorySaveMutex = Mutex()
    @Volatile
    private var inventoryLoaded: Boolean = false
    @Volatile
    private var lastSavedInventoryJson: String? = null
    private var inventorySaveJob: Job? = null

    private var transport: MqttTransport? = null
    private var currentBrokerUrl: String = AppBuildConfig.MQTT_BROKER_URL

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val mutex = Mutex()

    // Resolução de ACK: mapeia posicaoLedId -> CompletableDeferred<MqttAck>
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<MqttAck>>()

    // Fila de comandos pendentes com retry (máximo 3 tentativas)
    private data class ComandoPendente(
        val topico: String,
        val payload: String,
        val properties: MqttMessageProperties? = null,
        var tentativas: Int = 0
    )
    private val pendingCommandsQueue = mutableListOf<ComandoPendente>()

    // Controle de reconexão automática com backoff exponencial (1s, 2s, 4s, 8s, 16s)
    private var reconnectJob: Job? = null
    private val backoffIntervals = listOf(1000L, 2000L, 4000L, 8000L, 16000L)
    private val maxReconnectAttempts = 5
    private var isManualDisconnect = false

    init {
        if (inventoryLoader != null || inventorySaver != null) {
            scope.launch(ioDispatcher) {
                ensureInventoryLoaded()
                val saver = inventorySaver ?: return@launch
                espRegistry.espDevices
                    .map { devices -> EspInventoryCodec.encode(devices) }
                    .distinctUntilChanged()
                    .collect { encoded ->
                        inventorySaveJob?.cancel()
                        if (encoded == lastSavedInventoryJson) return@collect
                        inventorySaveJob = scope.launch(ioDispatcher) {
                            if (inventorySaveDebounceMs > 0) {
                                delay(inventorySaveDebounceMs)
                            }
                            inventorySaveMutex.withLock {
                                withContext(NonCancellable) {
                                    val latest = synchronized(registryLock) {
                                        EspInventoryCodec.encode(espRegistry.espDevices.value)
                                    }
                                    if (latest == lastSavedInventoryJson) return@withContext
                                    try {
                                        saver(latest)
                                        lastSavedInventoryJson = latest
                                    } catch (e: Exception) {
                                        println("[MQTT] Falha ao salvar inventário de ESPs: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    private suspend fun ensureInventoryLoaded() {
        val loader = inventoryLoader
        if (loader == null) {
            if (!inventoryLoaded) {
                lastSavedInventoryJson = synchronized(registryLock) {
                    EspInventoryCodec.encode(espRegistry.espDevices.value)
                }
                inventoryLoaded = true
            }
            return
        }
        if (inventoryLoaded) return
        inventoryLoadMutex.withLock {
            if (inventoryLoaded) return@withLock
            val rawJson = try {
                loader()
            } catch (e: Exception) {
                println("[MQTT] Falha ao carregar inventário de ESPs: ${e.message}")
                null
            }
            val snapshots = EspInventoryCodec.decode(rawJson)
            synchronized(registryLock) {
                espRegistry.seedFromPersisted(snapshots)
                lastSavedInventoryJson = EspInventoryCodec.encode(espRegistry.espDevices.value)
            }
            inventoryLoaded = true
        }
    }

    override suspend fun connect(brokerUrl: String?): Unit = withContext<Unit>(ioDispatcher) {
        val explicitUrl = brokerUrl?.trim()?.takeIf { it.isNotBlank() }
        if (explicitUrl != null) {
            AppSettings.saveBrokerUrl(explicitUrl)
        }
        val targetUrl = explicitUrl ?: AppSettings.resolveBrokerUrlOrFallback()
        currentBrokerUrl = targetUrl
        isManualDisconnect = false

        // Cancela qualquer job de reconexão anterior ao disparar conexão manual ou inicial
        reconnectJob?.cancel()

        executeConnect(targetUrl, isRetry = false)
    }

    private suspend fun executeConnect(targetUrl: String, isRetry: Boolean): Boolean = withContext(ioDispatcher) {
        if (inventoryLoader != null) {
            ensureInventoryLoaded()
        }
        mutex.withLock {
            _connectionState.value = MqttConnectionState.CONNECTING
            _negotiatedProtocol.value = null
            _capabilities.value = MqttCapabilities.NONE
            isV1Subscribed = false

            if (transport != null) {
                synchronized(registryLock) { espRegistry.failAllPending("Conexão MQTT reiniciada") }
            }
            try {
                transport?.setCallbacks(null)
                transport?.disconnectForcibly(1000, 1000)
            } catch (_: Exception) {}
            transport = null

            val mode = protocolModeProvider()
            val clientId = clientIdProvider()

            return@withLock try {
                val (created, connectResult) = when (mode) {
                    MqttProtocolMode.AUTO -> {
                        try {
                            connectWithProtocol(MqttProtocol.V5_0, targetUrl, clientId)
                        } catch (unsupported: MqttUnsupportedProtocolException) {
                            println("[MQTT] Fallback v5->3.1.1: reasonCode=${unsupported.reasonCode} causa=${unsupported.message}")
                            connectWithProtocol(MqttProtocol.V3_1_1, targetUrl, clientId)
                        }
                    }
                    MqttProtocolMode.V5_0 -> connectWithProtocol(MqttProtocol.V5_0, targetUrl, clientId)
                    MqttProtocolMode.V3_1_1 -> connectWithProtocol(MqttProtocol.V3_1_1, targetUrl, clientId)
                }

                transport = created
                _negotiatedProtocol.value = connectResult.protocol
                _capabilities.value = MqttCapabilities.forProtocol(connectResult.protocol)
                _connectionState.value = MqttConnectionState.CONNECTED
                reconnectJob?.cancel()

                // Processar comandos pendentes da fila
                processPendingQueue()
                true
            } catch (e: Exception) {
                _connectionState.value = if (isRetry) MqttConnectionState.CONNECTING else MqttConnectionState.DISCONNECTED
                _negotiatedProtocol.value = null
                _capabilities.value = MqttCapabilities.NONE
                transport = null

                if (!isManualDisconnect && !isRetry) {
                    startReconnectBackoff()
                }
                false
            }
        }
    }

    private suspend fun connectWithProtocol(
        protocol: MqttProtocol,
        targetUrl: String,
        clientId: String
    ): Pair<MqttTransport, MqttConnectResult> {
        val created = transportFactory(protocol)
        try {
            created.setCallbacks(object : MqttTransportCallbacks {
                override fun onConnectionLost(cause: Throwable?) {
                    _connectionState.value = MqttConnectionState.DISCONNECTED
                    _negotiatedProtocol.value = null
                    _capabilities.value = MqttCapabilities.NONE
                    isV1Subscribed = false
                    synchronized(registryLock) { espRegistry.failAllPending("Conexão MQTT perdida") }
                    if (!isManualDisconnect) {
                        startReconnectBackoff()
                    }
                }

                override fun onMessageArrived(message: MqttIncomingMessage) {
                    if (message.topic == MqttTopics.ACK) {
                        val payloadString = String(message.payload, Charsets.UTF_8)
                        handleAckMessage(payloadString)
                    } else {
                        handleV1Message(message)
                    }
                }

                override fun onDeliveryComplete(messageId: Int?) {}
            })

            val lwtPayload = json.encodeToString(
                MqttStatusApp(client = "wms_mobile", status = "OFFLINE")
            )
            val connectConfig = MqttConnectConfig(
                brokerUrl = targetUrl,
                clientId = clientId,
                cleanSession = true,
                connectionTimeoutSeconds = 5,
                keepAliveSeconds = 15,
                will = MqttWillConfig(
                    topic = MqttTopics.STATUS_APP,
                    payload = lwtPayload.toByteArray(Charsets.UTF_8),
                    qos = 1,
                    retained = true,
                    willDelaySeconds = 5
                )
            )

            val connectResult = created.connect(connectConfig).getOrThrow()

            // Inscrever no tópico de ACK com QoS 1 e No Local (seção 4.2)
            val subResult = created.subscribe(
                listOf(MqttSubscription(MqttTopics.ACK, qos = 1, noLocal = true)),
                timeoutMs = 3000L
            ).getOrThrow()
            val failedSub = subResult.reasonCodesByFilter.filterValues { it >= 0x80 }
            if (failedSub.isNotEmpty()) {
                throw IllegalStateException("SUBACK recusado pelo broker: $failedSub")
            }

            synchronized(registryLock) { espRegistry.demoteOnlineDevicesToStale() }
            if (v1SubscriptionsRequested) {
                val v1Sub = created.subscribe(v1Subscriptions, timeoutMs = 3000L)
                val v1Failed = v1Sub.getOrNull()?.reasonCodesByFilter?.filterValues { it >= 0x80 }
                if (v1Sub.isSuccess && v1Failed != null && v1Failed.isEmpty()) {
                    isV1Subscribed = true
                } else {
                    // Ajuste 2: falha na reassinatura v1 não derruba a conexão legada; próxima operação v1 tenta de novo.
                    isV1Subscribed = false
                    println("[MQTT] Reassinatura v1 recusada na reconexão: ${v1Sub.exceptionOrNull()?.message ?: v1Failed}")
                }
            }

            // Notificar status ONLINE do aplicativo (Retained = true, QoS 1)
            val onlinePayload = json.encodeToString(
                MqttStatusApp(client = "wms_mobile", status = "ONLINE")
            )
            created.publish(
                topic = MqttTopics.STATUS_APP,
                payload = onlinePayload.toByteArray(Charsets.UTF_8),
                qos = 1,
                retained = true,
                properties = MqttMessageProperties(
                    payloadFormatUtf8 = true,
                    contentType = JSON_CONTENT_TYPE
                ),
                timeoutMs = 3000L
            ).getOrThrow()

            return created to connectResult
        } catch (e: Exception) {
            try {
                created.setCallbacks(null)
                created.disconnectForcibly(500, 500)
            } catch (_: Exception) {}
            throw e
        }
    }

    private fun startReconnectBackoff() {
        if (reconnectJob?.isActive == true || isManualDisconnect) return

        reconnectJob = scope.launch(ioDispatcher) {
            var attempt = 1
            while (_connectionState.value != MqttConnectionState.CONNECTED && !isManualDisconnect && attempt <= maxReconnectAttempts) {
                _connectionState.value = MqttConnectionState.CONNECTING
                val delayMs = backoffIntervals[(attempt - 1).coerceAtMost(backoffIntervals.lastIndex)]
                delay(delayMs)
                if (isManualDisconnect) break

                val success = executeConnect(currentBrokerUrl, isRetry = true)
                if (success) {
                    break
                }
                attempt++
            }

            // Regra da especificação: limitar a 5 tentativas e depois parar. Retomada apenas manual.
            if (_connectionState.value != MqttConnectionState.CONNECTED && !isManualDisconnect) {
                _connectionState.value = MqttConnectionState.ERROR
            }
        }
    }

    private fun handleAckMessage(payloadString: String) {
        try {
            val ack = json.decodeFromString<MqttAck>(payloadString)
            if (ack.isPong) {
                pendingAcks[KEY_PING]?.complete(ack)
                return
            }
            if (ack.acao.equals("ACK", ignoreCase = true)) {
                val key = ack.posicaoLedId ?: KEY_TESTE_LED_NO_POS
                pendingAcks[key]?.complete(ack)
            }
        } catch (_: Exception) {}
    }

    private fun handleV1Message(message: MqttIncomingMessage) {
        val espId = MqttTopics.espIdFromTopic(message.topic) ?: return
        // Em MQTT 3.1.1 (sem noLocal) ou limpeza de retained, payload vazio chega no tópico: ignorar silenciosamente.
        if (message.payload.isEmpty()) return
        val payloadString = try {
            String(message.payload, Charsets.UTF_8)
        } catch (_: Exception) {
            return
        }
        when (message.topic) {
            MqttTopics.espStatus(espId) -> {
                when (val online = EspContract.parseStatusOnline(payloadString, espId)) {
                    is ContractResult.Valid -> synchronized(registryLock) {
                        espRegistry.applyStatusOnline(online.value, fromRetained = message.retained)
                    }
                    is ContractResult.Invalid -> {
                        when (val offline = EspContract.parseStatusOffline(payloadString, espId)) {
                            is ContractResult.Valid -> synchronized(registryLock) {
                                espRegistry.applyStatusOffline(offline.value)
                            }
                            is ContractResult.Invalid -> {
                                println("[MQTT] Status v1 inválido em ${message.topic}: ${online.code} / ${offline.code}")
                            }
                        }
                    }
                }
            }
            MqttTopics.espConfigState(espId) -> {
                when (val state = EspContract.parseConfigState(payloadString, espId)) {
                    is ContractResult.Valid -> synchronized(registryLock) {
                        espRegistry.applyConfigState(state.value, fromRetained = message.retained)
                    }
                    is ContractResult.Invalid -> {
                        println("[MQTT] config/state v1 inválido em ${message.topic}: ${state.code} (${state.detail})")
                    }
                }
            }
            MqttTopics.espConfigResult(espId) -> {
                when (val res = EspContract.parseConfigResult(payloadString, espId)) {
                    is ContractResult.Valid -> {
                        message.correlationData?.let { corrBytes ->
                            val corrId = String(corrBytes, Charsets.UTF_8)
                            if (corrId != res.value.requestId) {
                                println("[MQTT] Correlation Data divergente do payload em ${message.topic}: corr=$corrId payload=${res.value.requestId}")
                            }
                        }
                        synchronized(registryLock) {
                            espRegistry.applyConfigResult(res.value)
                        }
                    }
                    is ContractResult.Invalid -> {
                        println("[MQTT] config/result v1 inválido em ${message.topic}: ${res.code} (${res.detail})")
                    }
                }
            }
        }
    }

    private suspend fun publishWithQos1(
        topico: String,
        payloadString: String,
        properties: MqttMessageProperties
    ): Result<Unit> = withContext(ioDispatcher) {
        val currentTransport = transport
        if (currentTransport == null || !currentTransport.isConnected) {
            enqueuePendingCommand(topico, payloadString, properties)
            return@withContext Result.failure(IllegalStateException("Broker MQTT desconectado. Comando enfileirado."))
        }

        val publishResult = currentTransport.publish(
            topic = topico,
            payload = payloadString.toByteArray(Charsets.UTF_8),
            qos = 1,
            retained = false,
            properties = properties,
            timeoutMs = 5000L
        )
        if (publishResult.isFailure) {
            enqueuePendingCommand(topico, payloadString, properties)
            return@withContext Result.failure(
                publishResult.exceptionOrNull() ?: IllegalStateException("Erro ao publicar comando MQTT")
            )
        }

        val rc = publishResult.getOrNull()?.reasonCode
        when {
            rc == null || rc == 0x00 -> Result.success(Unit)
            rc == REASON_NO_MATCHING_SUBSCRIBERS -> {
                println("[MQTT] Diagnóstico PUBACK 0x10 (No matching subscribers) em $topico")
                Result.success(Unit)
            }
            rc == REASON_NOT_AUTHORIZED || rc == REASON_PACKET_TOO_LARGE -> {
                // Reenviar 0x87/0x95 não resolve: falha sem enfileirar.
                Result.failure(IllegalStateException("Publicação recusada pelo broker (reasonCode=0x${rc.toString(16)})"))
            }
            rc >= 0x80 -> {
                enqueuePendingCommand(topico, payloadString, properties)
                Result.failure(IllegalStateException("Falha PUBACK (reasonCode=0x${rc.toString(16)}). Comando enfileirado."))
            }
            else -> Result.success(Unit)
        }
    }

    private fun enqueuePendingCommand(topico: String, payloadString: String, properties: MqttMessageProperties?) {
        synchronized(pendingCommandsQueue) {
            pendingCommandsQueue.add(ComandoPendente(topico, payloadString, properties, tentativas = 0))
            _pendingCommandsCount.value = pendingCommandsQueue.size
        }
    }

    private fun processPendingQueue() {
        scope.launch(ioDispatcher) {
            val snapshot = synchronized(pendingCommandsQueue) {
                pendingCommandsQueue.toList()
            }
            val toRemove = mutableListOf<ComandoPendente>()
            for (cmd in snapshot) {
                if (cmd.tentativas < 3) {
                    cmd.tentativas++
                    val result = transport?.publish(
                        topic = cmd.topico,
                        payload = cmd.payload.toByteArray(Charsets.UTF_8),
                        qos = 1,
                        retained = false,
                        properties = cmd.properties,
                        timeoutMs = 2000L
                    )
                    val rc = result?.getOrNull()?.reasonCode
                    if (result != null && result.isSuccess && (rc == null || rc < 0x80)) {
                        toRemove.add(cmd)
                    }
                } else {
                    toRemove.add(cmd)
                }
            }
            synchronized(pendingCommandsQueue) {
                pendingCommandsQueue.removeAll { candidate -> toRemove.any { it === candidate } }
                _pendingCommandsCount.value = pendingCommandsQueue.size
            }
        }
    }

    override suspend fun acenderLed(
        estanteLedId: String,
        prateleiraLedId: String,
        posicaoLedId: String,
        cor: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val payload = MqttComandoAcender(
            estanteLedId = estanteLedId,
            prateleiraLedId = prateleiraLedId,
            posicaoLedId = posicaoLedId,
            cor = cor
        )
        val payloadString = json.encodeToString(payload)

        val ackDeferred = CompletableDeferred<MqttAck>()
        pendingAcks[posicaoLedId] = ackDeferred

        val publishResult = publishWithQos1(
            topico = MqttTopics.COMANDO,
            payloadString = payloadString,
            properties = jsonProperties(messageExpirySeconds = 5L)
        )
        if (publishResult.isFailure) {
            pendingAcks.remove(posicaoLedId)
            return@withContext publishResult
        }

        // Timeout de ACK de 5 segundos (Seção 5.2)
        val ack = withTimeoutOrNull(5000) {
            ackDeferred.await()
        }
        pendingAcks.remove(posicaoLedId)

        if (ack != null && ack.isOk) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Hardware não respondeu (timeout ACK 5s ou erro)"))
        }
    }

    override suspend fun apagarLed(
        estanteLedId: String,
        prateleiraLedId: String,
        posicaoLedId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val payload = MqttComandoApagar(
            estanteLedId = estanteLedId,
            prateleiraLedId = prateleiraLedId,
            posicaoLedId = posicaoLedId
        )
        val payloadString = json.encodeToString(payload)
        publishWithQos1(
            topico = MqttTopics.COMANDO,
            payloadString = payloadString,
            properties = jsonProperties(messageExpirySeconds = 65L)
        )
    }

    override suspend fun testarLed(
        estanteLedId: String,
        prateleiraLedId: String?,
        posicaoLedId: String?,
        cor: String,
        duracaoMs: Int
    ): Result<Unit> = withContext(ioDispatcher) {
        val payload = MqttComandoTesteLed(
            estanteLedId = estanteLedId,
            prateleiraLedId = prateleiraLedId,
            posicaoLedId = posicaoLedId,
            cor = cor,
            duracaoMs = duracaoMs
        )
        val payloadString = json.encodeToString(payload)

        val key = posicaoLedId ?: "TESTE_LED"
        val ackDeferred = CompletableDeferred<MqttAck>()
        pendingAcks[key] = ackDeferred

        val expirySeconds = ((duracaoMs.coerceAtLeast(0) + 999) / 1000).toLong() + 5L
        val publishResult = publishWithQos1(
            topico = MqttTopics.COMANDO,
            payloadString = payloadString,
            properties = jsonProperties(messageExpirySeconds = expirySeconds)
        )
        if (publishResult.isFailure) {
            pendingAcks.remove(key)
            return@withContext publishResult
        }

        val ack = withTimeoutOrNull(5000) {
            ackDeferred.await()
        }
        pendingAcks.remove(key)

        if (ack != null && ack.isOk) {
            Result.success(Unit)
        } else {
            Result.success(Unit)
        }
    }

    override suspend fun enviarPing(): Result<Long> = withContext(ioDispatcher) {
        val startTime = currentTimeMillis()
        val payload = MqttComandoPing()
        val payloadString = json.encodeToString(payload)

        val ackDeferred = CompletableDeferred<MqttAck>()
        pendingAcks["PING"] = ackDeferred

        val publishResult = publishWithQos1(
            topico = MqttTopics.COMANDO,
            payloadString = payloadString,
            properties = jsonProperties(messageExpirySeconds = 5L)
        )
        if (publishResult.isFailure) {
            pendingAcks.remove("PING")
            return@withContext Result.failure(publishResult.exceptionOrNull() ?: IllegalStateException("Erro ao enviar PING"))
        }

        val ack = withTimeoutOrNull(5000) {
            ackDeferred.await()
        }
        pendingAcks.remove("PING")

        if (ack != null) {
            val latency = (currentTimeMillis() - startTime).coerceAtLeast(1L)
            Result.success(latency)
        } else {
            Result.failure(IllegalStateException("Timeout de resposta ao PING"))
        }
    }

    // ---- Gerenciamento de ESPs (MQTT5_E_ESP32.md, seções 4, 5, 6, 8, 9 e 16) ----

    override suspend fun descobrirEsps(): Result<List<EspDevice>> = withContext(ioDispatcher) {
        val payload = EspContract.encodeDiscoveryRequest(DiscoveryRequest(EspContract.SCHEMA_VERSION, generateUUID()))
            .toResult().getOrElse { return@withContext Result.failure(it) }
        ensureV1Subscribed().onFailure { return@withContext Result.failure(it) }
        publishManagement(
            topic = MqttTopics.V1_DISCOVERY_REQUEST,
            payloadString = payload,
            properties = jsonProperties(messageExpirySeconds = 3L)
        ).onFailure { return@withContext Result.failure(it) }
        delay(EspManagementTimings.DISCOVERY_WINDOW_MS)
        Result.success(espDevices.value)
    }

    override suspend fun obterConfig(espId: String): Result<MqttOutcome<ConfigState>> = withContext(ioDispatcher) {
        requireEspId(espId).onFailure { return@withContext Result.failure(it) }
        val requestId = generateUUID()
        val payload = EspContract.encodeConfigGet(ConfigGet(EspContract.SCHEMA_VERSION, espId, requestId))
            .toResult().getOrElse { return@withContext Result.failure(it) }

        val deferred = synchronized(registryLock) { espRegistry.registerConfigGet(requestId) }
        try {
            ensureV1Subscribed().onFailure { return@withContext Result.failure(it) }
            val diagnostics = publishManagement(
                topic = MqttTopics.espConfigGet(espId),
                payloadString = payload,
                properties = jsonProperties(messageExpirySeconds = 5L)
            ).getOrElse { return@withContext Result.failure(it) }

            val state = withTimeoutOrNull(EspManagementTimings.CONFIG_TIMEOUT_MS) { deferred.await() }
                ?: return@withContext Result.success(MqttOutcome.Timeout(diagnostics))
            Result.success(MqttOutcome.Ok(state, diagnostics))
        } catch (e: MqttConnectionLostException) {
            Result.failure(e)
        } finally {
            synchronized(registryLock) { espRegistry.unregisterConfigGet(requestId) }
        }
    }

    override suspend fun gravarConfig(set: ConfigSet): Result<MqttOutcome<ConfigResult>> = withContext(ioDispatcher) {
        requireEspId(set.espId).onFailure { return@withContext Result.failure(it) }
        val payload = EspContract.encodeConfigSet(set).toResult().getOrElse { return@withContext Result.failure(it) }

        val deferred = synchronized(registryLock) { espRegistry.registerConfigSet(set.requestId) }
        try {
            ensureV1Subscribed().onFailure { return@withContext Result.failure(it) }
            val caps = _capabilities.value
            val properties = MqttMessageProperties(
                messageExpirySeconds = 5L,
                responseTopic = if (caps.responseTopic) MqttTopics.espConfigResult(set.espId) else null,
                correlationData = if (caps.correlationData) set.requestId.toByteArray(Charsets.UTF_8) else null,
                payloadFormatUtf8 = true,
                contentType = JSON_CONTENT_TYPE
            )
            // config/set nunca entra na fila de pendentes: desconectado ou PUBACK >= 0x80 falha sem enfileirar.
            val diagnostics = publishManagement(
                topic = MqttTopics.espConfigSet(set.espId),
                payloadString = payload,
                properties = properties
            ).getOrElse { return@withContext Result.failure(it) }

            val result = withTimeoutOrNull(EspManagementTimings.CONFIG_TIMEOUT_MS) { deferred.await() }
                ?: return@withContext Result.success(MqttOutcome.Timeout(diagnostics))
            if (result.status != ConfigResultStatus.OK) {
                Result.success(MqttOutcome.Erro(result.errorCode, result, diagnostics))
            } else {
                Result.success(MqttOutcome.Ok(result, diagnostics))
            }
        } catch (e: MqttConnectionLostException) {
            Result.failure(e)
        } finally {
            synchronized(registryLock) { espRegistry.unregisterConfigSet(set.requestId) }
        }
    }

    override suspend fun testarLedComConfirmacao(
        estanteLedId: String,
        prateleiraLedId: String?,
        posicaoLedId: String?,
        cor: String,
        duracaoMs: Int
    ): Result<MqttOutcome<Unit>> = withContext(ioDispatcher) {
        val payload = MqttComandoTesteLed(
            estanteLedId = estanteLedId,
            prateleiraLedId = prateleiraLedId,
            posicaoLedId = posicaoLedId,
            cor = cor,
            duracaoMs = duracaoMs
        )
        val payloadString = json.encodeToString(payload)
        val key = posicaoLedId ?: KEY_TESTE_LED_NO_POS
        val ackDeferred = CompletableDeferred<MqttAck>()
        pendingAcks[key] = ackDeferred
        try {
            val expirySeconds = ((duracaoMs.coerceAtLeast(0) + 999) / 1000).toLong() + 5L
            val diagnostics = publishManagement(
                topic = MqttTopics.COMANDO,
                payloadString = payloadString,
                properties = jsonProperties(messageExpirySeconds = expirySeconds)
            ).getOrElse { return@withContext Result.failure(it) }

            val ack = withTimeoutOrNull(EspManagementTimings.ACK_TIMEOUT_MS) { ackDeferred.await() }
                ?: return@withContext Result.success(MqttOutcome.Timeout(diagnostics))
            if (ack.isOk) {
                Result.success(MqttOutcome.Ok(Unit, diagnostics))
            } else {
                Result.success(MqttOutcome.Erro(ack.errorCodeEnum, diagnostics = diagnostics))
            }
        } finally {
            pendingAcks.remove(key)
        }
    }

    override suspend fun esquecerEsp(espId: String): Result<Unit> = withContext(ioDispatcher) {
        requireEspId(espId).onFailure { return@withContext Result.failure(it) }
        val currentTransport = transport
        if (currentTransport == null || !currentTransport.isConnected) {
            return@withContext Result.failure(IllegalStateException("Broker MQTT desconectado"))
        }
        val dev = synchronized(registryLock) { espRegistry.getDevice(espId) }
            ?: return@withContext Result.failure(IllegalStateException("ESP desconhecido: $espId"))
        if (dev.presence == EspPresence.ONLINE) {
            return@withContext Result.failure(IllegalStateException("Desligue o ESP antes de esquecer"))
        }

        // 1. Limpar primeiro os retidos do broker (status e config/state). Em falha/recusa, não remove localmente.
        publishRetainedClear(currentTransport, MqttTopics.espStatus(espId))
            .onFailure { return@withContext Result.failure(it) }
        publishRetainedClear(currentTransport, MqttTopics.espConfigState(espId))
            .onFailure { return@withContext Result.failure(it) }

        // 2. Remover do registro local e persistir imediatamente sob o mesmo mutex do debounce.
        inventorySaveJob?.cancel()
        val encodedWithoutEsp = synchronized(registryLock) {
            espRegistry.forget(espId)
            EspInventoryCodec.encode(espRegistry.espDevices.value)
        }
        val saver = inventorySaver
        if (saver != null) {
            inventorySaveMutex.withLock {
                val latestEncoded = synchronized(registryLock) {
                    EspInventoryCodec.encode(espRegistry.espDevices.value)
                }
                try {
                    saver(latestEncoded)
                    lastSavedInventoryJson = latestEncoded
                } catch (e: Exception) {
                    println("[MQTT] Falha ao gravar inventário após esquecer $espId: ${e.message}")
                    lastSavedInventoryJson = null
                }
            }
        } else {
            lastSavedInventoryJson = encodedWithoutEsp
        }

        Result.success(Unit)
    }

    private suspend fun publishRetainedClear(
        currentTransport: MqttTransport,
        topic: String
    ): Result<Unit> {
        val publishResult = currentTransport.publish(
            topic = topic,
            payload = ByteArray(0),
            qos = 1,
            retained = true,
            properties = null,
            timeoutMs = 5000L
        )
        if (publishResult.isFailure) {
            return Result.failure(
                publishResult.exceptionOrNull() ?: IllegalStateException("Erro ao limpar retido em $topic")
            )
        }
        val rc = publishResult.getOrNull()?.reasonCode
        return when {
            rc == null || rc == 0x00 || rc == REASON_NO_MATCHING_SUBSCRIBERS -> Result.success(Unit)
            rc == REASON_NOT_AUTHORIZED -> Result.failure(
                IllegalStateException("Limpeza recusada pelo broker: Not authorized (0x87) em $topic")
            )
            rc == REASON_PACKET_TOO_LARGE -> Result.failure(
                IllegalStateException("Limpeza recusada pelo broker: Packet too large (0x95) em $topic")
            )
            rc >= 0x80 -> Result.failure(
                IllegalStateException("Limpeza recusada pelo broker (0x${rc.toString(16)}) em $topic")
            )
            else -> Result.success(Unit)
        }
    }

    private suspend fun ensureV1Subscribed(): Result<Unit> = v1SubscribeMutex.withLock {
        val currentTransport = transport
        if (currentTransport == null || !currentTransport.isConnected) {
            return@withLock Result.failure(IllegalStateException("Broker MQTT desconectado"))
        }
        if (isV1Subscribed) return@withLock Result.success(Unit)
        val subResult = currentTransport.subscribe(v1Subscriptions, timeoutMs = 3000L)
            .getOrElse { return@withLock Result.failure(it) }
        val failed = subResult.reasonCodesByFilter.filterValues { it >= 0x80 }
        if (failed.isNotEmpty()) {
            return@withLock Result.failure(IllegalStateException("SUBACK v1 recusado pelo broker: $failed"))
        }
        isV1Subscribed = true
        v1SubscriptionsRequested = true
        Result.success(Unit)
    }

    private suspend fun publishManagement(
        topic: String,
        payloadString: String,
        properties: MqttMessageProperties
    ): Result<List<MqttDiagnostic>> {
        val currentTransport = transport
        if (currentTransport == null || !currentTransport.isConnected) {
            return Result.failure(IllegalStateException("Broker MQTT desconectado"))
        }
        val publishResult = currentTransport.publish(
            topic = topic,
            payload = payloadString.toByteArray(Charsets.UTF_8),
            qos = 1,
            retained = false,
            properties = properties,
            timeoutMs = 5000L
        )
        if (publishResult.isFailure) {
            return Result.failure(
                publishResult.exceptionOrNull() ?: IllegalStateException("Erro ao publicar em $topic")
            )
        }
        val rc = publishResult.getOrNull()?.reasonCode
        return when {
            rc == null || rc == 0x00 -> Result.success(emptyList())
            rc == REASON_NO_MATCHING_SUBSCRIBERS -> {
                if (_capabilities.value.reasonCodes) {
                    Result.success(listOf(MqttDiagnostic(rc, "No matching subscribers (0x10)", topic)))
                } else {
                    Result.success(emptyList())
                }
            }
            rc == REASON_NOT_AUTHORIZED -> Result.failure(
                IllegalStateException("Publicação recusada pelo broker: Not authorized (0x87) em $topic")
            )
            rc == REASON_PACKET_TOO_LARGE -> Result.failure(
                IllegalStateException("Publicação recusada pelo broker: Packet too large (0x95) em $topic")
            )
            rc >= 0x80 -> Result.failure(
                IllegalStateException("Publicação recusada pelo broker (0x${rc.toString(16)}) em $topic")
            )
            else -> Result.success(emptyList())
        }
    }

    private fun requireEspId(espId: String): Result<Unit> =
        if (EspContract.ESP_ID_REGEX.matches(espId)) Result.success(Unit)
        else Result.failure(IllegalArgumentException("esp_id inválido: $espId"))

    private fun ContractResult<String>.toResult(): Result<String> = when (this) {
        is ContractResult.Valid -> Result.success(value)
        is ContractResult.Invalid -> Result.failure(IllegalArgumentException("$code: $detail"))
    }

    override fun disconnect() {
        isManualDisconnect = true
        reconnectJob?.cancel()
        isV1Subscribed = false
        synchronized(registryLock) { espRegistry.failAllPending("Conexão MQTT encerrada") }
        _connectionState.value = MqttConnectionState.DISCONNECTED
        _negotiatedProtocol.value = null
        _capabilities.value = MqttCapabilities.NONE
        scope.launch(ioDispatcher) {
            try {
                transport?.setCallbacks(null)
                transport?.disconnectForcibly(1000, 1000)
            } catch (_: Exception) {}
            transport = null
            _connectionState.value = MqttConnectionState.DISCONNECTED
            _negotiatedProtocol.value = null
            _capabilities.value = MqttCapabilities.NONE
        }
    }

    private fun jsonProperties(messageExpirySeconds: Long) = MqttMessageProperties(
        messageExpirySeconds = messageExpirySeconds,
        payloadFormatUtf8 = true,
        contentType = JSON_CONTENT_TYPE
    )

    companion object {
        private const val JSON_CONTENT_TYPE = "application/json"
        private const val KEY_PING = "PING"
        private const val KEY_TESTE_LED_NO_POS = "TESTE_LED"
        private const val REASON_NO_MATCHING_SUBSCRIBERS = 0x10
        private const val REASON_NOT_AUTHORIZED = 0x87
        private const val REASON_PACKET_TOO_LARGE = 0x95

        private val v1Subscriptions = listOf(
            MqttSubscription(MqttTopics.V1_ESP_STATUS_FILTER, qos = 1, noLocal = true),
            MqttSubscription(MqttTopics.V1_ESP_CONFIG_STATE_FILTER, qos = 1, noLocal = true),
            MqttSubscription(MqttTopics.V1_ESP_CONFIG_RESULT_FILTER, qos = 1, noLocal = true)
        )
    }
}
