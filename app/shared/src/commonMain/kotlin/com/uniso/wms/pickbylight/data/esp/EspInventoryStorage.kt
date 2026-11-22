package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.EspDevice
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import com.uniso.wms.pickbylight.data.mqtt.model.EspStatusOnline
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Formato JSON versionado do inventário de ESPs e último snapshot conhecido (ADR-5, seção 9.1).
 * A presença nunca é serializada: todo ESP carregado do disco entra como STALE até confirmação ao vivo.
 */
@Serializable
data class EspInventorySnapshot(
    val v: Int = CURRENT_VERSION,
    val esps: List<PersistedEspSnapshot> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class PersistedEspSnapshot(
    val espId: String,
    val status: EspStatusOnline? = null,
    val config: ConfigState? = null
)

object EspInventoryCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(devices: List<EspDevice>): String {
        val entries = devices
            .sortedBy { it.espId }
            .map { dev ->
                PersistedEspSnapshot(
                    espId = dev.espId,
                    status = dev.status,
                    config = dev.config
                )
            }
        return json.encodeToString(EspInventorySnapshot(v = EspInventorySnapshot.CURRENT_VERSION, esps = entries))
    }

    fun decode(rawJson: String?): List<PersistedEspSnapshot> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val decoded = json.decodeFromString<EspInventorySnapshot>(rawJson)
            if (decoded.v != EspInventorySnapshot.CURRENT_VERSION) {
                println("[ESP_STORAGE] Versão de inventário desconhecida ignorada: v=${decoded.v}")
                return emptyList()
            }
            decoded.esps.filter { entry ->
                val validId = EspContract.ESP_ID_REGEX.matches(entry.espId)
                val statusOk = entry.status == null || entry.status.espId == entry.espId
                val configOk = entry.config == null || entry.config.espId == entry.espId
                if (!validId || !statusOk || !configOk) {
                    println("[ESP_STORAGE] Entrada de inventário inválida ignorada: espId=${entry.espId}")
                }
                validId && statusOk && configOk
            }
        } catch (e: Exception) {
            println("[ESP_STORAGE] JSON de inventário corrompido ignorado: ${e.message}")
            emptyList()
        }
    }
}
