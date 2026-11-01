package com.uniso.wms.pickbylight.nfc

import com.uniso.wms.pickbylight.util.currentTimeMillis

/**
 * Implementação Web/Wasm do NfcController:
 * - Em navegadores, leitura física nativa via ReaderMode não está disponível.
 * - Suporta normalização de UID, validação de formato e debounce para o simulador.
 */
actual object NfcController {
    private var lastScannedUid: String? = null
    private var lastScannedTimestamp: Long = 0L

    actual fun normalizeUid(rawUid: String): String {
        val clean = rawUid.trim().replace("-", ":").replace(" ", ":").uppercase()
        if (!clean.contains(":") && clean.length % 2 == 0 && clean.matches(Regex("^[0-9A-F]+$"))) {
            return clean.chunked(2).joinToString(":")
        }
        return clean
    }

    actual fun isValidUid(uid: String): Boolean {
        val normalized = normalizeUid(uid)
        return normalized.matches(Regex("^([0-9A-F]{2}:){3,6}[0-9A-F]{2}$"))
    }

    actual fun checkDebounce(uid: String, debounceMs: Long): Boolean {
        val normalized = normalizeUid(uid)
        val now = currentTimeMillis()
        if (normalized == lastScannedUid && (now - lastScannedTimestamp) < debounceMs) {
            return false
        }
        lastScannedUid = normalized
        lastScannedTimestamp = now
        return true
    }

    actual fun resetDebounce() {
        lastScannedUid = null
        lastScannedTimestamp = 0L
    }

    actual fun isAvailable(): Boolean = false

    actual fun isEnabled(): Boolean = false
}
