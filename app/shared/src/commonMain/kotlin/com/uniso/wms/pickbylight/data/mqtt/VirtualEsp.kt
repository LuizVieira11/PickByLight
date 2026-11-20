package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.esp.ContractResult
import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResultStatus
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import com.uniso.wms.pickbylight.data.mqtt.model.EspCapabilities
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.EspMqttProtocol
import com.uniso.wms.pickbylight.data.mqtt.model.EspOnlineStatus
import com.uniso.wms.pickbylight.data.mqtt.model.EspStatusOnline

/**
 * ESP simulado para o [FakeMqttService] (testes e Web). Aplica `config/set` com as mesmas regras do firmware
 * (seção 10.5), reaproveitando [EspContract.parseConfigSet]: revisão esperada, canais existentes, unicidade.
 * Estado mutável de propósito: o teste lê `configRevision`/`mappings` depois da operação.
 */
class VirtualEsp(
    val espId: String,
    val channels: List<EspChannel>,
    mappings: List<EspMapping> = emptyList(),
    var bootId: String = "ab12cd34",
    var configRevision: Long = 0L,
    val firmwareVersion: String = "1.0.0",
    val mqttProtocol: EspMqttProtocol = EspMqttProtocol.V3_1_1,
    var uptimeMs: Long = 120_000L
) {
    init {
        require(EspContract.ESP_ID_REGEX.matches(espId)) { "esp_id inválido: $espId" }
    }

    var mappings: List<EspMapping> = mappings
        private set

    fun owns(ledId: String): Boolean = mappings.any { it.ledId == ledId }

    fun status(): EspStatusOnline = EspStatusOnline(
        schemaVersion = EspContract.SCHEMA_VERSION,
        espId = espId,
        bootId = bootId,
        status = EspOnlineStatus.ONLINE,
        configRevision = configRevision,
        capabilities = EspCapabilities(firmwareVersion, mqttProtocol, channels),
        uptimeMs = uptimeMs
    )

    fun state(lastRequestId: String? = null): ConfigState = ConfigState(
        schemaVersion = EspContract.SCHEMA_VERSION,
        espId = espId,
        bootId = bootId,
        configRevision = configRevision,
        mappings = mappings,
        uptimeMs = uptimeMs,
        lastRequestId = lastRequestId
    )

    /** [payload] é o JSON exato que o app publicou em `config/set`. `config_revision` do resultado é a revisão ativa. */
    fun handleConfigSet(requestId: String, payload: String): ConfigResult =
        when (val parsed = EspContract.parseConfigSet(payload, espId, configRevision, channels)) {
            is ContractResult.Valid -> {
                mappings = parsed.value.mappings
                configRevision += 1
                result(requestId, ConfigResultStatus.OK)
            }
            is ContractResult.Invalid -> result(requestId, ConfigResultStatus.ERRO, parsed)
        }

    private fun result(requestId: String, status: ConfigResultStatus, invalid: ContractResult.Invalid? = null) = ConfigResult(
        schemaVersion = EspContract.SCHEMA_VERSION,
        espId = espId,
        bootId = bootId,
        requestId = requestId,
        status = status,
        errorCode = invalid?.code,
        configRevision = configRevision,
        message = invalid?.detail?.take(160)
    )
}
