package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.esp.ContractResult
import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigRestore
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.util.generateUUID
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResultStatus
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import com.uniso.wms.pickbylight.data.mqtt.model.EspCapabilities
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.EspMqttProtocol
import com.uniso.wms.pickbylight.data.mqtt.model.EspOfflineStatus
import com.uniso.wms.pickbylight.data.mqtt.model.EspOnlineStatus
import com.uniso.wms.pickbylight.data.mqtt.model.EspStatusOffline
import com.uniso.wms.pickbylight.data.mqtt.model.EspStatusOnline
import com.uniso.wms.pickbylight.data.mqtt.model.OfflineReason

/**
 * ESP simulado para o [FakeMqttService] (testes e Web). Aplica `config/set` com as mesmas regras do firmware
 * (seção 10.5), reaproveitando [EspContract.parseConfigSet]: revisão esperada, canais existentes, unicidade,
 * e deduplicação em RAM dos últimos [DEDUP_CACHE_SIZE] `request_id`.
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
    var uptimeMs: Long = 120_000L,
    var reportedStatus: EspOnlineStatus = EspOnlineStatus.ONLINE,
    var statusErrorCode: EspErrorCode? = null
) {
    init {
        require(EspContract.ESP_ID_REGEX.matches(espId)) { "esp_id inválido: $espId" }
        require(EspContract.BOOT_ID_REGEX.matches(bootId)) { "boot_id inválido: $bootId" }
        if (reportedStatus == EspOnlineStatus.CONFIG_INVALID) {
            require(statusErrorCode != null) { "CONFIG_INVALID exige error_code" }
        }
    }

    var mappings: List<EspMapping> = mappings
        private set

    /** Seção 10.5: em RESTORE_PENDING (após reboot sem NVS), o ESP não publica nem responde config/state. */
    var restorePending: Boolean = false
        private set

    // Cache em RAM dos últimos 8 request_id (seção 10.5); limpo no reboot().
    private val recentResults = ArrayDeque<Pair<String, ConfigResult>>()

    fun owns(ledId: String): Boolean = mappings.any { it.ledId == ledId }

    fun reboot(newBootId: String) {
        require(EspContract.BOOT_ID_REGEX.matches(newBootId)) { "boot_id inválido: $newBootId" }
        bootId = newBootId
        uptimeMs = 1_000L
        recentResults.clear()
    }

    /** Simula reinício sem persistência (10.5): perde mappings, zera revisão, entra em RESTORE_PENDING e gera ConfigRestore. */
    fun rebootForRestore(newBootId: String, requestId: String = generateUUID()): ConfigRestore {
        reboot(newBootId)
        mappings = emptyList()
        configRevision = 0L
        restorePending = true
        return ConfigRestore(
            schemaVersion = EspContract.SCHEMA_VERSION,
            espId = espId,
            bootId = newBootId,
            requestId = requestId
        )
    }

    fun status(): EspStatusOnline = EspStatusOnline(
        schemaVersion = EspContract.SCHEMA_VERSION,
        espId = espId,
        bootId = bootId,
        status = reportedStatus,
        configRevision = configRevision,
        capabilities = EspCapabilities(
            firmwareVersion,
            mqttProtocol,
            if (reportedStatus == EspOnlineStatus.CONFIG_INVALID && statusErrorCode == EspErrorCode.INVALID_CHANNEL_TABLE) {
                emptyList()
            } else {
                channels
            }
        ),
        errorCode = if (reportedStatus == EspOnlineStatus.CONFIG_INVALID) statusErrorCode else null,
        uptimeMs = uptimeMs
    )

    fun offlineStatus(reason: OfflineReason = OfflineReason.LWT): EspStatusOffline = EspStatusOffline(
        schemaVersion = EspContract.SCHEMA_VERSION,
        espId = espId,
        bootId = bootId,
        status = EspOfflineStatus.OFFLINE,
        reason = reason
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
    fun handleConfigSet(requestId: String, payload: String): ConfigResult {
        recentResults.firstOrNull { it.first == requestId }?.let { return it.second }
        val res = when (val parsed = EspContract.parseConfigSet(payload, espId, configRevision, channels)) {
            is ContractResult.Valid -> {
                mappings = parsed.value.mappings
                configRevision += 1
                restorePending = false
                result(requestId, ConfigResultStatus.OK)
            }
            is ContractResult.Invalid -> result(requestId, ConfigResultStatus.ERRO, parsed)
        }
        if (recentResults.size >= DEDUP_CACHE_SIZE) recentResults.removeFirst()
        recentResults.addLast(requestId to res)
        return res
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

    companion object {
        const val DEDUP_CACHE_SIZE = 8
    }
}
