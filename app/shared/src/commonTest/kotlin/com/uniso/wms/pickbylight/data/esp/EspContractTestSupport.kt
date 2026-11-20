package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import kotlin.test.assertEquals
import kotlin.test.fail

internal const val TEST_ESP = "esp-a1b2c3"
internal const val TEST_RID = "123e4567-e89b-12d3-a456-426614174000"

internal fun <T> ContractResult<T>.valid(): T = when (this) {
    is ContractResult.Valid -> value
    is ContractResult.Invalid -> fail("esperado válido, veio $code: $detail")
}

internal fun ContractResult<*>.assertInvalid(code: EspErrorCode) {
    when (this) {
        is ContractResult.Valid -> fail("esperado inválido ($code), veio válido: $value")
        is ContractResult.Invalid -> assertEquals(code, this.code, detail)
    }
}

internal fun statusJson(
    espId: String = TEST_ESP,
    status: String = "ONLINE",
    extra: String = "",
    channels: String = """[{"channel_id":"CH01","gpio":4,"active_high":true},{"channel_id":"CH02","gpio":13,"active_high":true}]"""
) = """{"schema_version":1,"esp_id":"$espId","boot_id":"9f1a2b3c","status":"$status","config_revision":4,""" +
    """"capabilities":{"firmware_version":"1.0.0","mqtt_protocol":"3.1.1","channels":$channels}$extra}"""
