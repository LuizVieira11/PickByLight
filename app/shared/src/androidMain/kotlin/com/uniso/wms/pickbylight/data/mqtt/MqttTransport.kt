package com.uniso.wms.pickbylight.data.mqtt

/**
 * Contrato interno de transporte MQTT no Android (`androidMain`).
 * Isola a biblioteca cliente (Paho v3 nesta etapa; Paho v5 na Etapa 4b) das regras do [PahoMqttServiceImpl]:
 * fila offline, retry, backoff, correlação de ACK e estados continuam no serviço.
 */
data class MqttWillConfig(
    val topic: String,
    val payload: ByteArray,
    val qos: Int,
    val retained: Boolean,
    val willDelaySeconds: Int? = null
)

/** [username] e [password] são opcionais (conexão anônima quando nulos/vazios; seção 16 item 15). */
data class MqttConnectConfig(
    val brokerUrl: String,
    val clientId: String,
    val cleanSession: Boolean,
    val connectionTimeoutSeconds: Int,
    val keepAliveSeconds: Int,
    val will: MqttWillConfig? = null,
    val username: String? = null,
    val password: String? = null
)

data class MqttConnectResult(
    val protocol: MqttProtocol,
    val returnCode: Int,
    val serverKeepAliveSeconds: Int? = null,
    val maxPacketSize: Int? = null,
    val receiveMaximum: Int? = null
)

/** Propriedades MQTT 5 (Etapa 4b). Ignoradas pelo transporte v3 na Etapa 4a. */
data class MqttMessageProperties(
    val messageExpirySeconds: Long? = null,
    val responseTopic: String? = null,
    val correlationData: ByteArray? = null,
    val payloadFormatUtf8: Boolean = false,
    val contentType: String? = null,
    val userProperties: Map<String, String> = emptyMap()
)

data class MqttPublishResult(
    val reasonCode: Int? = null
)

data class MqttSubscription(
    val topicFilter: String,
    val qos: Int,
    val noLocal: Boolean = false
)

data class MqttSubscribeResult(
    val reasonCodesByFilter: Map<String, Int>
)

data class MqttIncomingMessage(
    val topic: String,
    val payload: ByteArray,
    val retained: Boolean = false,
    val responseTopic: String? = null,
    val correlationData: ByteArray? = null,
    val userProperties: Map<String, String> = emptyMap()
)

interface MqttTransportCallbacks {
    fun onConnectionLost(cause: Throwable?)
    fun onMessageArrived(message: MqttIncomingMessage)
    fun onDeliveryComplete(messageId: Int?)
}

interface MqttTransport {
    val isConnected: Boolean
    fun setCallbacks(callbacks: MqttTransportCallbacks?)
    suspend fun connect(config: MqttConnectConfig): Result<MqttConnectResult>
    suspend fun subscribe(subscriptions: List<MqttSubscription>, timeoutMs: Long = 3_000L): Result<MqttSubscribeResult>
    suspend fun publish(
        topic: String,
        payload: ByteArray,
        qos: Int,
        retained: Boolean,
        properties: MqttMessageProperties? = null,
        timeoutMs: Long = 5_000L
    ): Result<MqttPublishResult>
    suspend fun disconnectForcibly(quiesceTimeoutMs: Long = 1_000L, disconnectTimeoutMs: Long = 1_000L)
}
