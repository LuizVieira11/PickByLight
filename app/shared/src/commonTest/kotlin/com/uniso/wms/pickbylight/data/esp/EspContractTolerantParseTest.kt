package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.OfflineReason
import kotlin.test.Test
import kotlin.test.assertEquals

/** MQTT5_E_ESP32.md seção 16, item 14: recebimento ESP -> app tolera campos novos; envio/espelho do ESP é estrito. */
class EspContractTolerantParseTest {

    private val esp = TEST_ESP
    private val rid = TEST_RID

    @Test
    fun statusOnlineToleratesUnknownTopLevelAndCapabilityFields() {
        val p = statusJson(extra = ""","campo_novo":1""").replace(
            "\"channels\":", "\"outra_capacidade\":true,\"channels\":"
        )
        assertEquals(4L, EspContract.parseStatusOnline(p, esp).valid().configRevision)
    }

    @Test
    fun statusOnlineToleratesUnknownChannelField() {
        val channels = """[{"channel_id":"CH01","gpio":4,"active_high":true,"label":"x"}]"""
        EspContract.parseStatusOnline(statusJson(channels = channels), esp).valid()
    }

    @Test
    fun statusOnlineStillRejectsMissingRequiredAndRequestId() {
        EspContract.parseStatusOnline(statusJson().replace("\"boot_id\":\"9f1a2b3c\",", ""), esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseStatusOnline(statusJson(extra = ""","request_id":"$rid""""), esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun offlineToleratesUnknownFieldButNotCapabilitiesOrRequestId() {
        val base = """{"schema_version":1,"esp_id":"$esp","boot_id":"ab12cd34","status":"OFFLINE","reason":"LWT""""
        assertEquals(OfflineReason.LWT, EspContract.parseStatusOffline("$base,\"campo_novo\":1}", esp).valid().reason)
        EspContract.parseStatusOffline("$base,\"capabilities\":{}}", esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseStatusOffline("$base,\"request_id\":\"$rid\"}", esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun configStateAndResultTolerateUnknownFields() {
        val state = """{"schema_version":1,"esp_id":"$esp","boot_id":"ab12cd34","config_revision":5,"mappings":[],"uptime_ms":1,"novo":"x"}"""
        assertEquals(5L, EspContract.parseConfigState(state, esp).valid().configRevision)
        val result = """{"schema_version":1,"esp_id":"$esp","boot_id":"ab12cd34","request_id":"$rid","status":"OK","config_revision":5,"novo":[1]}"""
        assertEquals(5L, EspContract.parseConfigResult(result, esp).valid().configRevision)
    }

    @Test
    fun configStateAndResultStillRejectBrokenPayloads() {
        EspContract.parseConfigState("""{"schema_version":1,"esp_id":"$esp","novo":1}""", esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseConfigResult("""{"schema_version":1,"esp_id":"$esp","boot_id":"ab12cd34","request_id":"$rid","status":"TALVEZ","config_revision":5}""", esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun mirrorOfEspSideStaysStrict() {
        EspContract.parseConfigGet("""{"schema_version":1,"esp_id":"$esp","request_id":"$rid","novo":1}""", esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseDiscoveryRequest("""{"schema_version":1,"request_id":"$rid","novo":1}""")
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseConfigSet("""{"schema_version":1,"esp_id":"$esp","request_id":"$rid","expected_revision":1,"mappings":[],"novo":1}""", esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }
}
