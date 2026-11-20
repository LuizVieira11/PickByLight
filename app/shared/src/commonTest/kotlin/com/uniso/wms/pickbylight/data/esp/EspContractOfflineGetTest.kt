package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.OfflineReason
import kotlin.test.Test
import kotlin.test.assertEquals

/** Seções 6.3 (OFFLINE) e 6.4 (config/get) de MQTT5_E_ESP32.md. */
class EspContractOfflineGetTest {

    private val esp = TEST_ESP
    private val rid = TEST_RID

    @Test
    fun offlineLwtAndShutdownAreValid() {
        val p = """{"schema_version":1,"esp_id":"$esp","boot_id":"ab12cd34","status":"OFFLINE","reason":"LWT"}"""
        assertEquals(OfflineReason.LWT, EspContract.parseStatusOffline(p, esp).valid().reason)
        assertEquals(OfflineReason.SHUTDOWN, EspContract.parseStatusOffline(p.replace("LWT", "SHUTDOWN"), esp).valid().reason)
    }

    @Test
    fun offlineWithCapabilitiesOrRequestIdIsInvalidSchema() {
        val base = """{"schema_version":1,"esp_id":"$esp","boot_id":"ab12cd34","status":"OFFLINE","reason":"LWT""""
        EspContract.parseStatusOffline("$base,\"capabilities\":{}}", esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseStatusOffline("$base,\"request_id\":\"$rid\"}", esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun offlineWithUnknownReasonIsInvalidSchema() {
        val p = """{"schema_version":1,"esp_id":"$esp","boot_id":"ab12cd34","status":"OFFLINE","reason":"OUTRO"}"""
        EspContract.parseStatusOffline(p, esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun appBuiltOfflineEncodesWithoutDynamicFields() {
        val status = com.uniso.wms.pickbylight.data.mqtt.model.EspStatusOffline(
            schemaVersion = 1,
            espId = esp,
            bootId = "ab12cd34",
            status = com.uniso.wms.pickbylight.data.mqtt.model.EspOfflineStatus.OFFLINE,
            reason = OfflineReason.LWT
        )
        assertEquals(
            """{"schema_version":1,"esp_id":"$esp","boot_id":"ab12cd34","status":"OFFLINE","reason":"LWT"}""",
            EspContract.encodeStatusOffline(status)
        )
    }

    @Test
    fun configGetValid() {
        val p = """{"schema_version":1,"esp_id":"$esp","request_id":"$rid"}"""
        assertEquals(rid, EspContract.parseConfigGet(p, esp).valid().requestId)
    }

    @Test
    fun configGetEspIdDifferentFromTopicIsMismatch() {
        val p = """{"schema_version":1,"esp_id":"$esp","request_id":"$rid"}"""
        EspContract.parseConfigGet(p, "esp-ffffff").assertInvalid(EspErrorCode.ESP_ID_MISMATCH)
    }

    @Test
    fun configGetWithInvalidRequestIdOrVersionIsInvalidSchema() {
        EspContract.parseConfigGet("""{"schema_version":1,"esp_id":"$esp","request_id":"nao-uuid"}""", esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseConfigGet("""{"schema_version":2,"esp_id":"$esp","request_id":"$rid"}""", esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun malformedJsonIsInvalidSchema() {
        EspContract.parseConfigGet("{nao e json", esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun payloadOver512BytesIsTooLargeForNonConfigPayloads() {
        val big = """{"schema_version":1,"esp_id":"$esp","request_id":"$rid","x":"${"a".repeat(600)}"}"""
        EspContract.parseConfigGet(big, esp).assertInvalid(EspErrorCode.PAYLOAD_TOO_LARGE)
    }
}
