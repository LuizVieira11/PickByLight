package com.uniso.wms.pickbylight.util

import kotlin.random.Random

/**
 * Gera identificadores UUID v4 compatíveis com RFC 4122 em formato lowercase multiplataforma.
 */
fun generateUUID(): String {
    val randomBytes = Random.nextBytes(16)
    randomBytes[6] = ((randomBytes[6].toInt() and 0x0f) or 0x40).toByte()
    randomBytes[8] = ((randomBytes[8].toInt() and 0x3f) or 0x80).toByte()

    val hex = randomBytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}
