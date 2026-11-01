package com.uniso.wms.pickbylight.nfc

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Gerenciador central de eventos de leitura NFC (Seção 3.2).
 * Recebe leituras do hardware físico Android ou do simulador UI.
 * Aplica normalização de UID e debounce de 2 segundos.
 */
object NfcManager {
    private val _scannedTagFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val scannedTagFlow: SharedFlow<String> = _scannedTagFlow.asSharedFlow()

    fun onTagScanned(uid: String, force: Boolean = false): Boolean {
        val normalized = NfcController.normalizeUid(uid)
        if (!force && !NfcController.checkDebounce(normalized, debounceMs = 2000L)) {
            return false // Ignorado pelo debounce de 2 segundos
        }
        _scannedTagFlow.tryEmit(normalized)
        return true
    }
}
