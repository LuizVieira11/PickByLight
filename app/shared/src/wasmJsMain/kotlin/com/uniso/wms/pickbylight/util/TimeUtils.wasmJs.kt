package com.uniso.wms.pickbylight.util

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun jsDateNow(): Double = js("Date.now()")

actual fun currentTimeMillis(): Long = jsDateNow().toLong()
