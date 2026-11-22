package com.uniso.wms.pickbylight.data.esp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Estado imutável do lock global de configuração/separação na instância do app (seções 9.1 e 16.7).
 */
data class ConfigLockState(
    val configuringEspId: String? = null,
    val activeColetas: Int = 0
) {
    val isConfigActive: Boolean get() = configuringEspId != null
    val isColetaActive: Boolean get() = activeColetas > 0
}

sealed interface ConfigLockResult {
    data object Acquired : ConfigLockResult
    data class Blocked(val reason: String) : ConfigLockResult
}

/**
 * Lock global bidirecional (`commonMain` puro, thread-safe via CAS em [MutableStateFlow]):
 * - Enquanto um ESP está sendo configurado ([ConfigLockState.isConfigActive]), impede iniciar coleta e
 *   impede iniciar configuração de outro (ou do mesmo) ESP;
 * - Enquanto há coleta ativa ([ConfigLockState.isColetaActive]), impede gravar configuração de ESP.
 */
class ConfigLock {
    private val _state = MutableStateFlow(ConfigLockState())
    val state: StateFlow<ConfigLockState> = _state.asStateFlow()

    /** Tenta registrar início de coleta. Falha (`false`) se houver configuração de ESP em andamento. */
    fun tryStartColeta(): Boolean {
        while (true) {
            val current = _state.value
            if (current.isConfigActive) return false
            val next = current.copy(activeColetas = current.activeColetas + 1)
            if (_state.compareAndSet(current, next)) return true
        }
    }

    /** Libera uma coleta ativa. Idempotente quando `activeColetas == 0`. */
    fun endColeta() {
        _state.update { current ->
            if (current.activeColetas <= 0) current
            else current.copy(activeColetas = current.activeColetas - 1)
        }
    }

    /** Tenta adquirir o lock exclusivo para configurar [espId]. Falha se houver coleta ativa ou outra config. */
    fun tryStartConfig(espId: String): ConfigLockResult {
        while (true) {
            val current = _state.value
            if (current.isColetaActive) {
                return ConfigLockResult.Blocked("coleta ativa")
            }
            if (current.configuringEspId != null) {
                return ConfigLockResult.Blocked("outra configuração em andamento (${current.configuringEspId})")
            }
            val next = current.copy(configuringEspId = espId)
            if (_state.compareAndSet(current, next)) return ConfigLockResult.Acquired
        }
    }

    /** Libera o lock de configuração se o dono atual for [espId]. */
    fun endConfig(espId: String) {
        _state.update { current ->
            if (current.configuringEspId == espId) current.copy(configuringEspId = null) else current
        }
    }
}

object ConfigLockProvider {
    private val defaultLock = ConfigLock()
    private val holder = MutableStateFlow(defaultLock)

    fun getLock(): ConfigLock = holder.value

    fun setLock(lock: ConfigLock) {
        holder.value = lock
    }
}
