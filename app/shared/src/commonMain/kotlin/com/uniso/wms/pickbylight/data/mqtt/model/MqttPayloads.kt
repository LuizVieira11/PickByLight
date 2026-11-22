package com.uniso.wms.pickbylight.data.mqtt.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object MqttTopics {
    const val COMANDO = "wms/pickbylight/comando"
    const val ACK = "wms/pickbylight/ack"
    const val STATUS_APP = "wms/pickbylight/status/app"

    // Namespace de gerenciamento v1 (MQTT5_E_ESP32.md, seção 5). Tópicos acima são legados e não mudam.
    const val V1_ROOT = "wms/pickbylight/v1"
    const val V1_DISCOVERY_REQUEST = "$V1_ROOT/discovery/request"
    const val V1_ESP_STATUS_FILTER = "$V1_ROOT/esp/+/status"
    const val V1_ESP_CONFIG_STATE_FILTER = "$V1_ROOT/esp/+/config/state"
    const val V1_ESP_CONFIG_RESULT_FILTER = "$V1_ROOT/esp/+/config/result"
    const val V1_ESP_CONFIG_RESTORE_FILTER = "$V1_ROOT/esp/+/config/restore"

    fun espStatus(espId: String) = "$V1_ROOT/esp/$espId/status"
    fun espConfigGet(espId: String) = "$V1_ROOT/esp/$espId/config/get"
    fun espConfigState(espId: String) = "$V1_ROOT/esp/$espId/config/state"
    fun espConfigSet(espId: String) = "$V1_ROOT/esp/$espId/config/set"
    fun espConfigResult(espId: String) = "$V1_ROOT/esp/$espId/config/result"
    fun espConfigRestore(espId: String) = "$V1_ROOT/esp/$espId/config/restore"

    private val ESP_TOPIC_REGEX =
        Regex("""^wms/pickbylight/v1/esp/(esp-[0-9a-f]{6})/(status|config/(get|state|set|result|restore))$""")

    /** Extrai o `esp_id` de um tópico v1; null se o tópico não segue o contrato. */
    fun espIdFromTopic(topic: String): String? = ESP_TOPIC_REGEX.matchEntire(topic)?.groupValues?.get(1)
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
    val status: String, // "OK" ou "ERRO"
    // Aditivos (MQTT5_E_ESP32.md 6.9). Opcionais: ACK legado continua válido. PONG também decodifica aqui.
    @SerialName("esp_id") val espId: String? = null,
    @SerialName("boot_id") val bootId: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    // String (não enum) de propósito: código desconhecido não pode derrubar o parse e perder o ACK.
    @SerialName("error_code") val errorCode: String? = null
) {
    val isOk: Boolean get() = status.equals("OK", ignoreCase = true)
    val isPong: Boolean get() = acao.equals("PONG", ignoreCase = true)
    val errorCodeEnum: EspErrorCode? get() = EspErrorCode.entries.firstOrNull { it.name == errorCode }
}

@Serializable
data class MqttStatusApp(
    val client: String = "wms_mobile",
    val status: String // "ONLINE" ou "OFFLINE"
)
