package com.uniso.wms.pickbylight.nfc

/**
 * Controlador nativo de NFC (Seção 3.2 da especificação):
 * - Leitura exclusiva de UID via ReaderMode (nunca escreve na tag NTAG213).
 * - Normalização de UID em formato Hexadecimal (XX:XX:XX:XX:XX:XX:XX).
 * - Debounce de 2 segundos para leituras físicas consecutivas da mesma tag.
 * - Suporte multiplataforma (Android nativo + fallback Web).
 */
expect object NfcController {
    fun normalizeUid(rawUid: String): String
    fun isValidUid(uid: String): Boolean
    fun checkDebounce(uid: String, debounceMs: Long = 2000L): Boolean
    fun resetDebounce()
    fun isAvailable(): Boolean
    fun isEnabled(): Boolean
}
