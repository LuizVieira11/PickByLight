package com.uniso.wms.pickbylight

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/** ADR-6: gerenciamento de ESPs ativo no Android e indisponível na versão Web/Wasm. */
expect val isEspManagementSupported: Boolean
