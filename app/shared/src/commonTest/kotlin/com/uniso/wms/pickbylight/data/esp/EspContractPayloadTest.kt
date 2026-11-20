package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspMqttProtocol
import com.uniso.wms.pickbylight.data.mqtt.model.EspOnlineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Exemplos válidos e inválidos das seções 6.1 e 6.2 de MQTT5_E_ESP32.md, com o error_code esperado. */
class EspContractPayloadTest {

    private val esp = TEST_ESP
    private val rid = TEST_RID

    @Test
    fun discoveryRequestValid() {
        val r = EspContract.parseDiscoveryRequest("""{"schema_version":1,"request_id":"$rid"}""").valid()
        assertEquals(rid, r.requestId)
    }

    @Test
    fun discoveryRequestWithoutRequestIdIsInvalidSchema() {
        EspContract.parseDiscoveryRequest("""{"schema_version":1}""").assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun statusOnlineValidFromSpec() {
        val payload = statusJson(extra = ""","uptime_ms":120000,"rssi":-61,"free_heap":182000""")
        val s = EspContract.parseStatusOnline(payload, esp).valid()
        assertEquals(EspOnlineStatus.ONLINE, s.status)
        assertEquals(EspMqttProtocol.V3_1_1, s.capabilities.mqttProtocol)
        assertEquals(2, s.capabilities.channels.size)
        assertEquals(4L, s.configRevision)
        assertNull(s.errorCode)
    }

    @Test
    fun statusWithMqtt5ProtocolDecodes() {
        val payload = statusJson().replace("\"3.1.1\"", "\"5.0\"")
        assertEquals(EspMqttProtocol.V5, EspContract.parseStatusOnline(payload, esp).valid().capabilities.mqttProtocol)
    }

    @Test
    fun statusWithUppercaseEspIdIsInvalidSchema() {
        EspContract.parseStatusOnline(statusJson(espId = "esp-A1B2C3"), "esp-A1B2C3")
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun statusConfigInvalidWithoutErrorCodeIsInvalidSchema() {
        EspContract.parseStatusOnline(statusJson(status = "CONFIG_INVALID", channels = "[]"), esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun statusConfigInvalidWithErrorCodeAndEmptyChannelsIsValid() {
        val invalidTable = statusJson(status = "CONFIG_INVALID", channels = "[]", extra = ""","error_code":"INVALID_CHANNEL_TABLE"""")
        val s = EspContract.parseStatusOnline(invalidTable, esp).valid()
        assertEquals(EspOnlineStatus.CONFIG_INVALID, s.status)
        assertEquals(EspErrorCode.INVALID_CHANNEL_TABLE, s.errorCode)
        val storage = statusJson(status = "CONFIG_INVALID", channels = "[]", extra = ""","error_code":"STORAGE_FAILURE"""")
        assertEquals(EspErrorCode.STORAGE_FAILURE, EspContract.parseStatusOnline(storage, esp).valid().errorCode)
    }

    @Test
    fun statusOnlineRejectsForbiddenGpioAsChannel() {
        for (gpio in listOf(0, 1, 2, 3, 6, 11, 12, 34, 35, 36, 39)) {
            val channels = """[{"channel_id":"CH01","gpio":$gpio,"active_high":true}]"""
            EspContract.parseStatusOnline(statusJson(channels = channels), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
        }
    }

    @Test
    fun statusOnlineRejectsRepeatedChannelOrGpio() {
        val sameId = """[{"channel_id":"CH01","gpio":4,"active_high":true},{"channel_id":"CH01","gpio":13,"active_high":true}]"""
        val sameGpio = """[{"channel_id":"CH01","gpio":4,"active_high":true},{"channel_id":"CH02","gpio":4,"active_high":true}]"""
        EspContract.parseStatusOnline(statusJson(channels = sameId), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseStatusOnline(statusJson(channels = sameGpio), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun statusWithRequestIdIsInvalidSchema() {
        EspContract.parseStatusOnline(statusJson(extra = ""","request_id":"$rid""""), esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun statusEspIdDifferentFromTopicIsMismatch() {
        EspContract.parseStatusOnline(statusJson(), "esp-ffffff").assertInvalid(EspErrorCode.ESP_ID_MISMATCH)
    }
}
