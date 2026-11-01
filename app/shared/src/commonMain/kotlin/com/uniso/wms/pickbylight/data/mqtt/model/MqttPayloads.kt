package com.uniso.wms.pickbylight.data.mqtt.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object MqttTopics {
    const val COMANDO = "wms/pickbylight/comando"
    const val ACK = "wms/pickbylight/ack"
    const val STATUS_APP = "wms/pickbylight/status/app"
}

@Serializable
data class MqttComandoAcender(
    val acao: String = "ACENDER",
    @SerialName("estante_led_id") val estanteLedId: String,
    @SerialName("prateleira_led_id") val prateleiraLedId: String,
    @SerialName("posicao_led_id") val posicaoLedId: String,
    val cor: String = "VERDE"
)

@Serializable
data class MqttComandoApagar(
    val acao: String = "APAGAR",
    @SerialName("estante_led_id") val estanteLedId: String,
    @SerialName("prateleira_led_id") val prateleiraLedId: String,
    @SerialName("posicao_led_id") val posicaoLedId: String
)

@Serializable
data class MqttComandoPing(
    val acao: String = "PING"
)

@Serializable
data class MqttComandoTesteLed(
    val acao: String = "TESTE_LED",
    @SerialName("estante_led_id") val estanteLedId: String,
    @SerialName("prateleira_led_id") val prateleiraLedId: String? = null,
    @SerialName("posicao_led_id") val posicaoLedId: String? = null,
    val cor: String = "AZUL",
    @SerialName("duracao_ms") val duracaoMs: Int = 3000
)

@Serializable
data class MqttAck(
    val acao: String = "ACK",
    @SerialName("posicao_led_id") val posicaoLedId: String? = null,
    val status: String // "OK" ou "ERRO"
) {
    val isOk: Boolean get() = status.equals("OK", ignoreCase = true)
}

@Serializable
data class MqttStatusApp(
    val client: String = "wms_mobile",
    val status: String // "ONLINE" ou "OFFLINE"
)
