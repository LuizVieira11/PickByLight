package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.esp.PersistedEspSnapshot
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import com.uniso.wms.pickbylight.data.mqtt.model.EspStatusOffline
import com.uniso.wms.pickbylight.data.mqtt.model.EspStatusOnline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Exceção sinalizando queda ou reinício da conexão MQTT durante uma operação de gerenciamento pendente. */
class MqttConnectionLostException(message: String) : IllegalStateException(message)

/**
 * Registro em memória do estado conhecido de ESPs, filtro de `boot_id`/revisão obsoletos e pendências
 * de `config/get` (por `last_request_id`) e `config/set` (por `request_id`).
 *
 * Não é thread-safe por si só (`commonMain` puro, sem `java.*`/`android.*`).
 * Em ambiente multithread (callbacks Paho em `androidMain`), o chamador serializa o acesso com lock externo.
 */
class EspRegistry {

    private val _espDevices = MutableStateFlow<List<EspDevice>>(emptyList())
    val espDevices: StateFlow<List<EspDevice>> = _espDevices.asStateFlow()

    private val knownDevices = linkedMapOf<String, EspDevice>()
    private val activeBootIdByEsp = mutableMapOf<String, String>()
    private val retiredBootIdsByEsp = mutableMapOf<String, MutableSet<String>>()
    private val pendingConfigGets = mutableMapOf<String, CompletableDeferred<ConfigState>>()
    private val pendingConfigSets = mutableMapOf<String, CompletableDeferred<ConfigResult>>()

    private val _discardedObsoleteMessages = mutableListOf<String>()
    val discardedObsoleteMessages: List<String> get() = _discardedObsoleteMessages.toList()

    fun registerConfigGet(requestId: String): CompletableDeferred<ConfigState> =
        CompletableDeferred<ConfigState>().also { pendingConfigGets[requestId] = it }

    fun unregisterConfigGet(requestId: String) {
        pendingConfigGets.remove(requestId)
    }

    fun registerConfigSet(requestId: String): CompletableDeferred<ConfigResult> =
        CompletableDeferred<ConfigResult>().also { pendingConfigSets[requestId] = it }

    fun unregisterConfigSet(requestId: String) {
        pendingConfigSets.remove(requestId)
    }

    fun getDevice(espId: String): EspDevice? = knownDevices[espId]

    /**
     * Remove todo histórico conhecido de [espId] (entrada na lista, snapshot e filtros de `boot_id`/revisão).
     * Se o mesmo `esp_id` voltar a publicar no futuro, será aceito como dispositivo novo.
     */
    fun forget(espId: String): Boolean {
        val removed = knownDevices.remove(espId) != null
        activeBootIdByEsp.remove(espId)
        retiredBootIdsByEsp.remove(espId)
        if (removed) {
            _espDevices.value = knownDevices.values.sortedBy { it.espId }
        }
        return removed
    }

    /**
     * Semeia o registro a partir do inventário salvo em disco (seção 9.1).
     * Todo ESP carregado entra como [EspPresence.STALE] ("possivelmente antigo") e
     * um `espId` já visto na sessão atual nunca é sobrescrito pelo snapshot persistido.
     */
    fun seedFromPersisted(snapshots: List<PersistedEspSnapshot>): Int {
        var added = 0
        for (snap in snapshots) {
            if (snap.espId in knownDevices) continue
            knownDevices[snap.espId] = EspDevice(
                espId = snap.espId,
                presence = EspPresence.STALE,
                status = snap.status,
                config = snap.config
            )
            val bootId = snap.status?.bootId ?: snap.config?.bootId
            if (bootId != null) {
                activeBootIdByEsp[snap.espId] = bootId
            }
            added++
        }
        if (added > 0) {
            _espDevices.value = knownDevices.values.sortedBy { it.espId }
        }
        return added
    }

    fun applyStatusOnline(status: EspStatusOnline, fromRetained: Boolean): Boolean {
        if (!acceptBootAndRevision(status.espId, status.bootId, status.configRevision, "status")) return false
        val previous = knownDevices[status.espId]
        knownDevices[status.espId] = EspDevice(
            espId = status.espId,
            presence = if (fromRetained) EspPresence.STALE else EspPresence.ONLINE,
            status = status,
            config = previous?.config
        )
        _espDevices.value = knownDevices.values.sortedBy { it.espId }
        return true
    }

    fun applyStatusOffline(offline: EspStatusOffline): Boolean {
        if (!acceptBootAndRevision(offline.espId, offline.bootId, revision = null, source = "offline")) return false
        val previous = knownDevices[offline.espId]
        knownDevices[offline.espId] = EspDevice(
            espId = offline.espId,
            presence = EspPresence.OFFLINE,
            status = previous?.status,
            config = previous?.config
        )
        _espDevices.value = knownDevices.values.sortedBy { it.espId }
        return true
    }

    fun applyConfigState(state: ConfigState, fromRetained: Boolean): Boolean {
        if (!acceptBootAndRevision(state.espId, state.bootId, state.configRevision, "config/state")) return false
        val previous = knownDevices[state.espId]
        val presence = when {
            !fromRetained -> EspPresence.ONLINE
            previous != null -> previous.presence
            else -> EspPresence.STALE
        }
        knownDevices[state.espId] = EspDevice(
            espId = state.espId,
            presence = presence,
            status = previous?.status,
            config = state
        )
        _espDevices.value = knownDevices.values.sortedBy { it.espId }
        if (!fromRetained) {
            state.lastRequestId?.let { reqId -> pendingConfigGets[reqId]?.complete(state) }
        }
        return true
    }

    fun applyConfigResult(result: ConfigResult): Boolean {
        if (!acceptBootAndRevision(result.espId, result.bootId, revision = null, source = "config/result")) return false
        val deferred = pendingConfigSets[result.requestId]
        if (deferred == null) {
            _discardedObsoleteMessages.add("config/result:${result.espId}:unknown_request=${result.requestId}")
            return false
        }
        if (deferred.isCompleted) {
            _discardedObsoleteMessages.add("config/result:${result.espId}:duplicate_request=${result.requestId}")
            return false
        }
        return deferred.complete(result)
    }

    fun demoteOnlineDevicesToStale() {
        var changed = false
        for ((id, dev) in knownDevices.entries.toList()) {
            if (dev.presence == EspPresence.ONLINE) {
                knownDevices[id] = dev.copy(presence = EspPresence.STALE)
                changed = true
            }
        }
        if (changed) {
            _espDevices.value = knownDevices.values.sortedBy { it.espId }
        }
    }

    fun failAllPending(reason: String) {
        val ex = MqttConnectionLostException(reason)
        pendingConfigGets.values.toList().forEach { it.completeExceptionally(ex) }
        pendingConfigGets.clear()
        pendingConfigSets.values.toList().forEach { it.completeExceptionally(ex) }
        pendingConfigSets.clear()
    }

    private fun acceptBootAndRevision(espId: String, bootId: String, revision: Long?, source: String): Boolean {
        val retired = retiredBootIdsByEsp[espId]
        if (retired != null && bootId in retired) {
            _discardedObsoleteMessages.add("$source:$espId:retired_boot=$bootId")
            return false
        }
        val activeBoot = activeBootIdByEsp[espId]
        if (activeBoot != null && activeBoot != bootId) {
            retiredBootIdsByEsp.getOrPut(espId) { mutableSetOf() }.add(activeBoot)
            activeBootIdByEsp[espId] = bootId
            return true
        }
        if (activeBoot == null) {
            activeBootIdByEsp[espId] = bootId
        }
        if (revision != null) {
            val previous = knownDevices[espId]
            val maxKnownRev = listOfNotNull(previous?.config?.configRevision, previous?.status?.configRevision).maxOrNull()
            if (maxKnownRev != null && revision < maxKnownRev) {
                _discardedObsoleteMessages.add("$source:$espId:old_revision=$revision<$maxKnownRev")
                return false
            }
        }
        return true
    }
}