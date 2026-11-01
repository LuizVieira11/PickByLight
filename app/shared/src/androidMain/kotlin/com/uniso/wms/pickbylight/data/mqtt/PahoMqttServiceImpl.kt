package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.mqtt.model.MqttAck
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoAcender
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoApagar
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoPing
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoTesteLed
import com.uniso.wms.pickbylight.data.mqtt.model.MqttStatusApp
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import com.uniso.wms.pickbylight.util.AppBuildConfig
import com.uniso.wms.pickbylight.util.currentTimeMillis
import kotlinx.coroutines.CompletableDeferred
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
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementação nativa Android com Eclipse Paho MQTT.
 * Atende às Seções 4.1, 4.2 e 5.1/5.2 da especificação:
 * - QoS 1 em todos os comandos e confirmações
 * - LWT em 'wms/pickbylight/status/app' (Retained=true, QoS 1)
 * - Reconexão com Backoff Exponencial (1s, 2s, 4s, 8s, máx 16s) limitada a 5 tentativas
 * - Fila de retry (máx 3 tentativas)
 * - Timeout de ACK de 5 segundos
 * - Execução estritamente em Dispatchers.IO para prevenção de ANR
 */
class PahoMqttServiceImpl(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : MqttService {

    private val _connectionState = MutableStateFlow(MqttConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _pendingCommandsCount = MutableStateFlow(0)
    override val pendingCommandsCount: StateFlow<Int> = _pendingCommandsCount.asStateFlow()

    private var client: MqttAsyncClient? = null
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
        var tentativas: Int = 0
    )
    private val pendingCommandsQueue = mutableListOf<ComandoPendente>()

    // Controle de reconexão automática com backoff exponencial (1s, 2s, 4s, 8s, 16s)
    private var reconnectJob: Job? = null
    private val backoffIntervals = listOf(1000L, 2000L, 4000L, 8000L, 16000L)
    private val maxReconnectAttempts = 5
    private var isManualDisconnect = false

    override suspend fun connect(brokerUrl: String?): Unit = withContext(Dispatchers.IO) {
        val targetUrl = brokerUrl?.takeIf { it.isNotBlank() } ?: AppBuildConfig.MQTT_BROKER_URL
        currentBrokerUrl = targetUrl
        isManualDisconnect = false

        // Cancela qualquer job de reconexão anterior ao disparar conexão manual ou inicial
        reconnectJob?.cancel()

        executeConnect(targetUrl, isRetry = false)
        Unit
    }

    private suspend fun executeConnect(targetUrl: String, isRetry: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                _connectionState.value = MqttConnectionState.CONNECTING

                try {
                    client?.disconnectForcibly(1000, 1000)
                    client?.close()
                } catch (_: Exception) {}
                client = null

                val clientId = "WmsMobile_${currentTimeMillis()}"
                val newClient = MqttAsyncClient(targetUrl, clientId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 5
                    keepAliveInterval = 30
                    isAutomaticReconnect = false // Explicitamente falso: backoff gerenciado manualmente

                    // Last Will and Testament (LWT)
                    val lwtPayload = json.encodeToString(
                        MqttStatusApp(client = "wms_mobile", status = "OFFLINE")
                    )
                    setWill(MqttTopics.STATUS_APP, lwtPayload.toByteArray(Charsets.UTF_8), 1, true)
                }

                newClient.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        _connectionState.value = MqttConnectionState.DISCONNECTED
                        if (!isManualDisconnect) {
                            startReconnectBackoff()
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (topic == MqttTopics.ACK && message != null) {
                            val payloadString = String(message.payload, Charsets.UTF_8)
                            handleAckMessage(payloadString)
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                val token = newClient.connect(options)
                token.waitForCompletion(5000)

                // Inscrever no tópico de ACK com QoS 1
                val subToken = newClient.subscribe(MqttTopics.ACK, 1)
                subToken.waitForCompletion(3000)

                // Notificar status ONLINE do aplicativo (Retained = true, QoS 1)
                val onlinePayload = json.encodeToString(
                    MqttStatusApp(client = "wms_mobile", status = "ONLINE")
                )
                val onlineMessage = MqttMessage(onlinePayload.toByteArray(Charsets.UTF_8)).apply {
                    qos = 1
                    isRetained = true
                }
                val pubToken = newClient.publish(MqttTopics.STATUS_APP, onlineMessage)
                pubToken.waitForCompletion(3000)

                client = newClient
                _connectionState.value = MqttConnectionState.CONNECTED
                reconnectJob?.cancel()

                // Processar comandos pendentes da fila
                processPendingQueue()
                true
            } catch (e: Exception) {
                _connectionState.value = if (isRetry) MqttConnectionState.CONNECTING else MqttConnectionState.DISCONNECTED
                try {
                    client?.disconnectForcibly(500, 500)
                    client?.close()
                } catch (_: Exception) {}
                client = null

                if (!isManualDisconnect && !isRetry) {
                    startReconnectBackoff()
                }
                false
            }
        }
    }

    private fun startReconnectBackoff() {
        if (reconnectJob?.isActive == true || isManualDisconnect) return

        reconnectJob = scope.launch(Dispatchers.IO) {
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

    private suspend fun publishWithQos1(topico: String, payloadString: String): Result<Unit> = withContext(Dispatchers.IO) {
        val currentClient = client
        if (currentClient == null || !currentClient.isConnected) {
            enqueuePendingCommand(topico, payloadString)
            return@withContext Result.failure(IllegalStateException("Broker MQTT desconectado. Comando enfileirado."))
        }

        try {
            val message = MqttMessage(payloadString.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
            }
            val token = currentClient.publish(topico, message)
            token.waitForCompletion(5000)
            Result.success(Unit)
        } catch (e: Exception) {
            enqueuePendingCommand(topico, payloadString)
            Result.failure(e)
        }
    }

    private fun enqueuePendingCommand(topico: String, payloadString: String) {
        synchronized(pendingCommandsQueue) {
            pendingCommandsQueue.add(ComandoPendente(topico, payloadString, tentativas = 0))
            _pendingCommandsCount.value = pendingCommandsQueue.size
        }
    }

    private fun processPendingQueue() {
        scope.launch(Dispatchers.IO) {
            synchronized(pendingCommandsQueue) {
                val iterator = pendingCommandsQueue.iterator()
                while (iterator.hasNext()) {
                    val cmd = iterator.next()
                    if (cmd.tentativas < 3) {
                        cmd.tentativas++
                        try {
                            val msg = MqttMessage(cmd.payload.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
                            client?.publish(cmd.topico, msg)?.waitForCompletion(2000)
                            iterator.remove()
                        } catch (_: Exception) {}
                    } else {
                        iterator.remove()
                    }
                }
                _pendingCommandsCount.value = pendingCommandsQueue.size
            }
        }
    }

    override suspend fun acenderLed(
        estanteLedId: String,
        prateleiraLedId: String,
        posicaoLedId: String,
        cor: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = MqttComandoAcender(
            estanteLedId = estanteLedId,
            prateleiraLedId = prateleiraLedId,
            posicaoLedId = posicaoLedId,
            cor = cor
        )
        val payloadString = json.encodeToString(payload)

        val ackDeferred = CompletableDeferred<MqttAck>()
        pendingAcks[posicaoLedId] = ackDeferred

        val publishResult = publishWithQos1(MqttTopics.COMANDO, payloadString)
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = MqttComandoApagar(
            estanteLedId = estanteLedId,
            prateleiraLedId = prateleiraLedId,
            posicaoLedId = posicaoLedId
        )
        val payloadString = json.encodeToString(payload)
        publishWithQos1(MqttTopics.COMANDO, payloadString)
    }

    override suspend fun testarLed(
        estanteLedId: String,
        prateleiraLedId: String?,
        posicaoLedId: String?,
        cor: String,
        duracaoMs: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
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

        val publishResult = publishWithQos1(MqttTopics.COMANDO, payloadString)
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

    override suspend fun enviarPing(): Result<Long> = withContext(Dispatchers.IO) {
        val startTime = currentTimeMillis()
        val payload = MqttComandoPing()
        val payloadString = json.encodeToString(payload)

        val ackDeferred = CompletableDeferred<MqttAck>()
        pendingAcks["PING"] = ackDeferred

        val publishResult = publishWithQos1(MqttTopics.COMANDO, payloadString)
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

    override fun disconnect() {
        isManualDisconnect = true
        reconnectJob?.cancel()
        scope.launch(Dispatchers.IO) {
            try {
                client?.disconnectForcibly(1000, 1000)
                client?.close()
            } catch (_: Exception) {}
            client = null
            _connectionState.value = MqttConnectionState.DISCONNECTED
        }
    }
}
