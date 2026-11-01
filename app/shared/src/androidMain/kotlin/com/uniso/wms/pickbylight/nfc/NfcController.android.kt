package com.uniso.wms.pickbylight.nfc

import android.app.Activity
import android.nfc.NfcAdapter

/**
 * Implementação Android do NfcController (Seção 3.2):
 * - ReaderMode com flags recomendadas para tags NTAG213 (NFC-A, ISO 14443-A).
 * - Debounce de 2 segundos.
 * - Normalização e validação de UID.
 */
actual object NfcController {
    private var lastScannedUid: String? = null
    private var lastScannedTimestamp: Long = 0L

    private var nfcAdapter: NfcAdapter? = null

    fun init(activity: Activity) {
        nfcAdapter = NfcAdapter.getDefaultAdapter(activity)
    }

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
        val now = System.currentTimeMillis()
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

    actual fun isAvailable(): Boolean = nfcAdapter != null

    actual fun isEnabled(): Boolean = nfcAdapter?.isEnabled == true

    fun enableReaderMode(activity: Activity, callback: NfcAdapter.ReaderCallback) {
        nfcAdapter?.enableReaderMode(
            activity,
            callback,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null
        )
    }

    fun disableReaderMode(activity: Activity) {
        nfcAdapter?.disableReaderMode(activity)
    }
}
