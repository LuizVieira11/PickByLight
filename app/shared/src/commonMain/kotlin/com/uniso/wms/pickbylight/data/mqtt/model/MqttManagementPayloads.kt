package com.uniso.wms.pickbylight.data.mqtt.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Payloads do namespace v1 (especificacao/MQTT5_E_ESP32.md, seções 5 a 7).
 * Contratos legados (comando/ack/status-app) ficam em MqttPayloads.kt e não mudam.
 * Campos sem default são obrigatórios no schema; validação semântica em data/esp/EspContract.kt.
 */

/** Códigos de erro (seção 7). INVALID_CHANNEL_TABLE só aparece em status CONFIG_INVALID. */
@Serializable
enum class EspErrorCode {
    REVISION_MISMATCH,
    INVALID_SCHEMA,
    LED_ID_CONFLICT,
    UNKNOWN_CHANNEL,
    CHANNEL_CONFLICT,
    PAYLOAD_TOO_LARGE,
    STORAGE_FAILURE,
    EXPIRED,
    ESP_ID_MISMATCH,
    INTERNAL_ERROR,
    INVALID_CHANNEL_TABLE
}

@Serializable
enum class EspOnlineStatus { ONLINE, CONFIG_INVALID }

@Serializable
enum class EspOfflineStatus { OFFLINE }

@Serializable
enum class OfflineReason { LWT, SHUTDOWN }

@Serializable
enum class ConfigResultStatus { OK, ERRO }

@Serializable
enum class MappingKind { ESTANTE, PRATELEIRA, POSICAO }

@Serializable
enum class EspMqttProtocol {
    @SerialName("5.0") V5,
    @SerialName("3.1.1") V3_1_1
}

@Serializable
data class DiscoveryRequest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("request_id") val requestId: String
)

@Serializable
data class EspChannel(
    @SerialName("channel_id") val channelId: String,
    val gpio: Int,
    @SerialName("active_high") val activeHigh: Boolean
)

@Serializable
data class EspCapabilities(
    @SerialName("firmware_version") val firmwareVersion: String,
    @SerialName("mqtt_protocol") val mqttProtocol: EspMqttProtocol,
    val channels: List<EspChannel>
)

/** Status retained ONLINE/CONFIG_INVALID (6.2). Nunca carrega request_id. */
@Serializable
data class EspStatusOnline(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("esp_id") val espId: String,
    @SerialName("boot_id") val bootId: String,
    val status: EspOnlineStatus,
    @SerialName("config_revision") val configRevision: Long,
    val capabilities: EspCapabilities,
    @SerialName("error_code") val errorCode: EspErrorCode? = null,
    @SerialName("uptime_ms") val uptimeMs: Long? = null,
    val rssi: Int? = null,
    @SerialName("free_heap") val freeHeap: Long? = null
)

/** Will (reason = LWT) e desligamento controlado (reason = SHUTDOWN), sem campos dinâmicos (6.3). */
@Serializable
data class EspStatusOffline(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("esp_id") val espId: String,
    @SerialName("boot_id") val bootId: String,
    val status: EspOfflineStatus,
    val reason: OfflineReason
)

@Serializable
data class ConfigGet(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("esp_id") val espId: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
data class EspMapping(
    @SerialName("led_id") val ledId: String,
    val kind: MappingKind,
    @SerialName("channel_id") val channelId: String
)

/** Snapshot completo desejado (nunca patch). O app não envia config_revision, boot_id nem uptime_ms (6.6). */
@Serializable
data class ConfigSet(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("esp_id") val espId: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("issued_at_ms") val issuedAtMs: Long? = null,
    @SerialName("ttl_ms") val ttlMs: Long? = null,
    val mappings: List<EspMapping>
)

@Serializable
data class ConfigState(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("esp_id") val espId: String,
    @SerialName("boot_id") val bootId: String,
    @SerialName("config_revision") val configRevision: Long,
    val mappings: List<EspMapping>,
    @SerialName("uptime_ms") val uptimeMs: Long,
    @SerialName("last_request_id") val lastRequestId: String? = null
)

/** config_revision é sempre a revisão ativa após o processamento (6.8). */
@Serializable
data class ConfigResult(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("esp_id") val espId: String,
    @SerialName("boot_id") val bootId: String,
    @SerialName("request_id") val requestId: String,
    val status: ConfigResultStatus,
    @SerialName("error_code") val errorCode: EspErrorCode? = null,
    @SerialName("config_revision") val configRevision: Long,
    val message: String? = null
)

/**
 * Resposta do ESP a PING (6.9). Trafega em `wms/pickbylight/ack` e decodifica como [MqttAck]
 * (status obrigatório). Existe aqui para simuladores/testes montarem o payload exato do firmware.
 */
@Serializable
data class MqttPong(
    val acao: String = "PONG",
    val status: String = "OK",
    @SerialName("esp_id") val espId: String,
    @SerialName("boot_id") val bootId: String
)
