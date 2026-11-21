package com.uniso.wms.pickbylight.data.mqtt

internal data class RecordedPublish(
    val topic: String,
    val payload: String,
    val qos: Int,
    val retained: Boolean,
    val properties: MqttMessageProperties? = null
)

internal class FakeMqttTransport(
    val protocol: MqttProtocol = MqttProtocol.V3_1_1
) : MqttTransport {
    override var isConnected: Boolean = false
    var registeredCallbacks: MqttTransportCallbacks? = null
    val connectConfigs = mutableListOf<MqttConnectConfig>()
    val subscriptions = mutableListOf<MqttSubscription>()
    val publishes = mutableListOf<RecordedPublish>()
    val events = mutableListOf<String>()
    var failNextConnect: Boolean = false
    var failConnectWith: Throwable? = null
    var subackCodesOverride: Map<String, Int>? = null
    var v1SubackCodesOverride: Map<String, Int>? = null
    var publishReasonCodeOverride: Int? = null
    val publishReasonCodeByTopic = mutableMapOf<String, Int>()
    var closedCount: Int = 0
    var remainingFailuresOnCommand: Int = 0
    var onSubscribe: ((FakeMqttTransport, List<MqttSubscription>) -> Unit)? = null
    var onPublish: ((FakeMqttTransport, RecordedPublish) -> Unit)? = null

    override fun setCallbacks(callbacks: MqttTransportCallbacks?) {
        this.registeredCallbacks = callbacks
    }

    override suspend fun connect(config: MqttConnectConfig): Result<MqttConnectResult> {
        connectConfigs.add(config)
        failConnectWith?.let { err ->
            isConnected = false
            return Result.failure(err)
        }
        if (failNextConnect) {
            isConnected = false
            return Result.failure(IllegalStateException("Falha simulada ao conectar"))
        }
        isConnected = true
        events.add("CONNECT(${protocol.name})")
        return Result.success(MqttConnectResult(protocol, returnCode = 0))
    }

    override suspend fun subscribe(
        subscriptions: List<MqttSubscription>,
        timeoutMs: Long
    ): Result<MqttSubscribeResult> {
        this.subscriptions.addAll(subscriptions)
        val isV1 = subscriptions.any { it.topicFilter.startsWith("wms/pickbylight/v1/") }
        val overrideMap = if (isV1 && v1SubackCodesOverride != null) v1SubackCodesOverride else subackCodesOverride
        val codes = overrideMap ?: subscriptions.associate { it.topicFilter to 0 }
        events.add(if (isV1) "SUBACK_V1($codes)" else "SUBACK_ACK($codes)")
        onSubscribe?.invoke(this, subscriptions)
        return Result.success(MqttSubscribeResult(codes))
    }

    override suspend fun publish(
        topic: String,
        payload: ByteArray,
        qos: Int,
        retained: Boolean,
        properties: MqttMessageProperties?,
        timeoutMs: Long
    ): Result<MqttPublishResult> {
        val rec = RecordedPublish(topic, String(payload, Charsets.UTF_8), qos, retained, properties)
        if (topic == "wms/pickbylight/comando" && remainingFailuresOnCommand > 0) {
            remainingFailuresOnCommand--
            return Result.failure(IllegalStateException("Falha simulada de fila"))
        }
        events.add("PUBLISH($topic)")
        publishes.add(rec)
        onPublish?.invoke(this, rec)
        val rc = publishReasonCodeByTopic[topic]
            ?: if (topic == "wms/pickbylight/comando") publishReasonCodeOverride else null
        return Result.success(MqttPublishResult(reasonCode = rc))
    }

    override suspend fun disconnectForcibly(quiesceTimeoutMs: Long, disconnectTimeoutMs: Long) {
        isConnected = false
        closedCount++
    }

    fun deliverAck(json: String) {
        deliverMessage("wms/pickbylight/ack", json, retained = false)
    }

    fun deliverMessage(
        topic: String,
        json: String,
        retained: Boolean = false,
        correlationData: ByteArray? = null
    ) {
        registeredCallbacks?.onMessageArrived(
            MqttIncomingMessage(
                topic = topic,
                payload = json.toByteArray(Charsets.UTF_8),
                retained = retained,
                correlationData = correlationData
            )
        )
    }
}
