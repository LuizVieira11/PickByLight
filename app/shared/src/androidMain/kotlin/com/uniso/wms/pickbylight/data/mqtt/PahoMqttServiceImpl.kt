package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MqttService {

    private val _connectionState = MutableStateFlow(MqttConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _pendingCommandsCount = MutableStateFlow(0)
    override val pendingCommandsCount: StateFlow<Int> = _pendingCommandsCount.asStateFlow()

    private val _negotiatedProtocol = MutableStateFlow<MqttProtocol?>(null)
    override val negotiatedProtocol: StateFlow<MqttProtocol?> = _negotiatedProtocol.asStateFlow()

    private val _capabilities = MutableStateFlow(MqttCapabilities.NONE)
    override val capabilities: StateFlow<MqttCapabilities> = _capabilities.asStateFlow()

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
        mutex.withLock {
            _connectionState.value = MqttConnectionState.CONNECTING
            _negotiatedProtocol.value = null
            _capabilities.value = MqttCapabilities.NONE

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
                    if (!isManualDisconnect) {
                        startReconnectBackoff()
                    }
                }

                override fun onMessageArrived(message: MqttIncomingMessage) {
                    if (message.topic == MqttTopics.ACK) {
                        val payloadString = String(message.payload, Charsets.UTF_8)
                        handleAckMessage(payloadString)
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
            val key = ack.posicaoLedId ?: "GLOBAL"
            pendingAcks[key]?.complete(ack)
            pendingAcks["PING"]?.complete(ack)
        } catch (_: Exception) {}
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

    // ---- Gerenciamento de ESPs: stubs explícitos. A Etapa 4b2 os substitui. ----

    override val espDevices: StateFlow<List<EspDevice>> = MutableStateFlow<List<EspDevice>>(emptyList()).asStateFlow()

    override suspend fun descobrirEsps(): Result<List<EspDevice>> = notImplementedYet()

    override suspend fun obterConfig(espId: String): Result<MqttOutcome<ConfigState>> = notImplementedYet()

    override suspend fun gravarConfig(set: ConfigSet): Result<MqttOutcome<ConfigResult>> = notImplementedYet()

    override suspend fun testarLedComConfirmacao(
        estanteLedId: String,
        prateleiraLedId: String?,
        posicaoLedId: String?,
        cor: String,
        duracaoMs: Int
    ): Result<MqttOutcome<Unit>> = notImplementedYet()

    private fun <T> notImplementedYet(): Result<T> =
        Result.failure(UnsupportedOperationException("implementado na Etapa 4"))

    override fun disconnect() {
        isManualDisconnect = true
        reconnectJob?.cancel()
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
        private const val REASON_NO_MATCHING_SUBSCRIBERS = 0x10
        private const val REASON_NOT_AUTHORIZED = 0x87
        private const val REASON_PACKET_TOO_LARGE = 0x95
    }
}
