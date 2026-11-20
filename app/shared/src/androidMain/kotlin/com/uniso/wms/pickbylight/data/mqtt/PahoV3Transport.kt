package com.uniso.wms.pickbylight.data.mqtt

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Transporte MQTT 3.1.1 sobre `org.eclipse.paho.client.mqttv3`.
 * Preserva exatamente os parâmetros e a ordem de chamadas da implementação original de [PahoMqttServiceImpl].
 */
class PahoV3Transport : MqttTransport {

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

            val options = MqttConnectOptions().apply {
                isCleanSession = config.cleanSession
                connectionTimeout = config.connectionTimeoutSeconds
                keepAliveInterval = config.keepAliveSeconds
                isAutomaticReconnect = false // Backoff gerenciado manualmente em PahoMqttServiceImpl
                if (!config.username.isNullOrBlank()) {
                    userName = config.username
                    if (config.password != null) {
                        password = config.password.toCharArray()
                    }
                }
                config.will?.let { will ->
                    setWill(will.topic, will.payload, will.qos, will.retained)
                }
            }

            created.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    callbacks?.onConnectionLost(cause)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic != null && message != null) {
                        callbacks?.onMessageArrived(
                            MqttIncomingMessage(
                                topic = topic,
                                payload = message.payload,
                                retained = message.isRetained
                            )
                        )
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    callbacks?.onDeliveryComplete(token?.messageId)
                }
            })

            val token = created.connect(options)
            token.waitForCompletion(config.connectionTimeoutSeconds * 1_000L)

            client = created
            Result.success(MqttConnectResult(protocol = MqttProtocol.V3_1_1, returnCode = 0))
        } catch (e: Exception) {
            try {
                newClient?.disconnectForcibly(500, 500)
                newClient?.close()
            } catch (_: Exception) {}
            client = null
            Result.failure(e)
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
            val filters = subscriptions.map { it.topicFilter }.toTypedArray()
            val qoss = subscriptions.map { it.qos }.toIntArray()
            val subToken = currentClient.subscribe(filters, qoss)
            subToken.waitForCompletion(timeoutMs)
            Result.success(MqttSubscribeResult(subscriptions.associate { it.topicFilter to 0 }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun publish(
        topic: String,
        payload: ByteArray,
        qos: Int,
        retained: Boolean,
        properties: MqttMessageProperties?, // Ignorado no transporte MQTT 3.1.1
        timeoutMs: Long
    ): Result<MqttPublishResult> {
        val currentClient = client
            ?: return Result.failure(IllegalStateException("Cliente MQTT não conectado"))
        return try {
            val message = MqttMessage(payload).apply {
                this.qos = qos
                this.isRetained = retained
            }
            val token = currentClient.publish(topic, message)
            token.waitForCompletion(timeoutMs)
            Result.success(MqttPublishResult(reasonCode = null))
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
            current?.disconnectForcibly(quiesceTimeoutMs, disconnectTimeoutMs)
            current?.close()
        } catch (_: Exception) {}
    }
}
