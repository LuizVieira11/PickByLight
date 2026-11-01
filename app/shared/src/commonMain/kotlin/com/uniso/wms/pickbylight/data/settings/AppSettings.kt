package com.uniso.wms.pickbylight.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Configurações de aplicação compartilhadas (Seção 3.6 da especificação).
 * Gerencia preferências em tempo de execução como o Modo Debug NFC.
 */
object AppSettings {
    private val _modoDebugNfc = MutableStateFlow(false)
    val modoDebugNfc: StateFlow<Boolean> = _modoDebugNfc.asStateFlow()

    fun setModoDebugNfc(enabled: Boolean) {
        _modoDebugNfc.value = enabled
    }
}
