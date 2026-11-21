package com.uniso.wms.pickbylight.data.mqtt

import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttClientException
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.MqttSubscription as PahoV5Subscription
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import org.eclipse.paho.mqttv5.common.packet.MqttReturnCode
import org.eclipse.paho.mqttv5.common.packet.UserProperty
import java.io.EOFException

/**
 * Transporte MQTT 5.0 sobre `org.eclipse.paho.mqttv5.client:1.2.5`.
 * Clean Start = true, Session Expiry = 0, automaticReconnect = false (backoff fica no serviço).
 */
class PahoV5Transport : MqttTransport {

    @Volatile
    private var client: MqttAsyncClient? = null

    @Volatile
    private var callbacks: MqttTransportCallbacks? = null

    override val isConnected: Boolean
        get() = client?.isConnected == true

    override fun setCallbacks(callbacks: MqttTransportCallbacks?) {
        this.callbacks = callbacks
    }

    override suspend fun connect(config: MqttConnectConfig): Result<MqttConnectResult> {
        closeClient(quiesceTimeoutMs = 1_000L, disconnectTimeoutMs = 1_000L)

        var newClient: MqttAsyncClient? = null
        return try {
            val created = MqttAsyncClient(config.brokerUrl, config.clientId, MemoryPersistence())
            newClient = created

            val options = MqttConnectionOptions().apply {
                isCleanStart = config.cleanSession
                sessionExpiryInterval = 0L
                keepAliveInterval = config.keepAliveSeconds
                connectionTimeout = config.connectionTimeoutSeconds
                isAutomaticReconnect = false
                if (!config.username.isNullOrBlank()) {
                    userName = config.username
                    if (config.password != null) {
                        password = config.password.toByteArray(Charsets.UTF_8)
                    }
                }
                config.will?.let { will ->
                    val willProps = will.willDelaySeconds?.let { delaySec ->
                        MqttProperties().apply { willDelayInterval = delaySec.toLong() }
                    }
                    if (willProps != null) {
                        willMessageProperties = willProps
                    }
                    setWill(will.topic, MqttMessage(will.payload, will.qos, will.retained, willProps))
                }
            }

            created.setCallback(object : MqttCallback {
                override fun disconnected(disconnectResponse: MqttDisconnectResponse?) {
                    callbacks?.onConnectionLost(disconnectResponse?.exception)
                }

                override fun mqttErrorOccurred(exception: MqttException?) {}

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic != null && message != null) {
                        val props = message.properties
                        val userProps = props?.userProperties
                            ?.associate { it.key to it.value }
                            .orEmpty()
                        callbacks?.onMessageArrived(
                            MqttIncomingMessage(
                                topic = topic,
                                payload = message.payload,
                                retained = message.isRetained,
                                responseTopic = props?.responseTopic,
                                correlationData = props?.correlationData,
                                userProperties = userProps
                            )
                        )
                    }
                }

                override fun deliveryComplete(token: IMqttToken?) {
                    callbacks?.onDeliveryComplete(token?.messageId)
                }

                override fun connectComplete(reconnect: Boolean, serverURI: String?) {}

                override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) {}
            })

            val token = created.connect(options)
            token.waitForCompletion(config.connectionTimeoutSeconds * 1_000L)

            val rc = token.reasonCodes?.firstOrNull() ?: MqttReturnCode.RETURN_CODE_SUCCESS
            if (rc != MqttReturnCode.RETURN_CODE_SUCCESS) {
                throw MqttException(rc)
            }

            val respProps = token.responseProperties
            client = created
            Result.success(
                MqttConnectResult(
                    protocol = MqttProtocol.V5_0,
                    returnCode = rc,
                    serverKeepAliveSeconds = respProps?.serverKeepAlive,
                    maxPacketSize = respProps?.maximumPacketSize?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                    receiveMaximum = respProps?.receiveMaximum
                )
            )
        } catch (e: Exception) {
            try {
                newClient?.disconnectForcibly(500L, 500L, false)
                newClient?.close(true)
            } catch (_: Exception) {}
            client = null
            Result.failure(classifyConnectFailure(e))
        }
    }

    override suspend fun subscribe(
        subscriptions: List<MqttSubscription>,
        timeoutMs: Long
    ): Result<MqttSubscribeResult> {
        val currentClient = client
            ?: return Result.failure(IllegalStateException("Cliente MQTT não conectado"))
        if (subscriptions.isEmpty()) {
            return Result.success(MqttSubscribeResult(emptyMap()))
        }
        return try {
            val pahoSubs = subscriptions.map { sub ->
                PahoV5Subscription(sub.topicFilter, sub.qos).apply { isNoLocal = sub.noLocal }
            }.toTypedArray()
            val subToken = currentClient.subscribe(pahoSubs)
            subToken.waitForCompletion(timeoutMs)
            val codes = subToken.reasonCodes ?: IntArray(0)
            val byFilter = subscriptions.mapIndexed { index, sub ->
                val rc = if (index < codes.size) codes[index] else 0x80
                sub.topicFilter to rc
            }.toMap()
            Result.success(MqttSubscribeResult(byFilter))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun publish(
        topic: String,
        payload: ByteArray,
        qos: Int,
        retained: Boolean,
        properties: MqttMessageProperties?,
        timeoutMs: Long
    ): Result<MqttPublishResult> {
        val currentClient = client
            ?: return Result.failure(IllegalStateException("Cliente MQTT não conectado"))
        return try {
            val props = properties?.toPahoProperties()
            val message = MqttMessage(payload, qos, retained, props)
            val token = currentClient.publish(topic, message)
            token.waitForCompletion(timeoutMs)
            Result.success(MqttPublishResult(reasonCode = token.reasonCodes?.firstOrNull()))
        } catch (e: MqttException) {
            if (e.reasonCode in 0x80..0xFF) {
                Result.success(MqttPublishResult(reasonCode = e.reasonCode))
            } else {
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnectForcibly(quiesceTimeoutMs: Long, disconnectTimeoutMs: Long) {
        closeClient(quiesceTimeoutMs, disconnectTimeoutMs)
    }

    private fun closeClient(quiesceTimeoutMs: Long, disconnectTimeoutMs: Long) {
        val current = client
        client = null
        try {
            current?.disconnectForcibly(quiesceTimeoutMs, disconnectTimeoutMs, true)
            current?.close(true)
        } catch (_: Exception) {}
    }

    private fun MqttMessageProperties.toPahoProperties(): MqttProperties? {
        val src = this
        if (src.messageExpirySeconds == null && src.responseTopic == null && src.correlationData == null &&
            !src.payloadFormatUtf8 && src.contentType == null && src.userProperties.isEmpty()
        ) return null
        return MqttProperties().apply {
            src.messageExpirySeconds?.let { messageExpiryInterval = it }
            src.responseTopic?.let { responseTopic = it }
            src.correlationData?.let { correlationData = it }
            if (src.payloadFormatUtf8) payloadFormat = true
            src.contentType?.let { contentType = it }
            if (src.userProperties.isNotEmpty()) {
                userProperties = src.userProperties.entries.map { UserProperty(it.key, it.value) }
            }
        }
    }

    private fun classifyConnectFailure(e: Exception): Throwable {
        val rc = (e as? MqttException)?.reasonCode
        val isUnsupported = rc == MqttReturnCode.RETURN_CODE_UNSUPPORTED_PROTOCOL_VERSION ||
            rc == MqttClientException.REASON_CODE_INVALID_PROTOCOL_VERSION.toInt() ||
            rc == MqttException.REASON_CODE_INVALID_RETURN_CODE ||
            rc == MqttException.REASON_CODE_UNSUPPORTED_PROTOCOL_VERSION ||
            rc == MqttException.REASON_CODE_MALFORMED_PACKET ||
            hasCause<EOFException>(e)
        return if (isUnsupported) {
            MqttUnsupportedProtocolException(
                reasonCode = rc,
                message = "Broker não suporta MQTT 5.0 (reasonCode=$rc): ${e.message}",
                cause = e
            )
        } else {
            e
        }
    }

    private inline fun <reified T : Throwable> hasCause(t: Throwable?): Boolean {
        var cur = t
        while (cur != null) {
            if (cur is T) return true
            cur = cur.cause
        }
        return false
    }
}