package com.uniso.wms.pickbylight

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform