package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResultStatus
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Seções 6.7 (config/state) e 6.8 (config/result) de MQTT5_E_ESP32.md. */
class EspContractStateResultTest {

    private val esp = TEST_ESP
    private val rid = TEST_RID
    private val mapping = """{"led_id":"POS_A1_01","kind":"POSICAO","channel_id":"CH03"}"""

    private fun state(
        espId: String = esp,
        drop: String? = null,
        extra: String = "",
        mappings: String = "[$mapping]"
    ): String {
        val fields = linkedMapOf(
            "schema_version" to "1",
            "esp_id" to "\"$espId\"",
            "boot_id" to "\"ab12cd34\"",
            "config_revision" to "5",
            "mappings" to mappings,
            "uptime_ms" to "9000"
        )
        drop?.let { fields.remove(it) }
        return fields.entries.joinToString(",", "{", "$extra}") { "\"${it.key}\":${it.value}" }
    }

    @Test
    fun configStateValid() {
        val s = EspContract.parseConfigState(state(extra = ""","last_request_id":"$rid""""), esp).valid()
        assertEquals(5L, s.configRevision)
        assertEquals(rid, s.lastRequestId)
        assertEquals(1, s.mappings.size)
    }

    @Test
    fun configStateWithoutLastRequestIdIsValid() {
        assertNull(EspContract.parseConfigState(state(), esp).valid().lastRequestId)
    }

    @Test
    fun configStateWithoutBootIdOrRevisionIsInvalidSchema() {
        EspContract.parseConfigState(state(drop = "boot_id"), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseConfigState(state(drop = "config_revision"), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun configStateEspIdDifferentFromTopicIsMismatch() {
        EspContract.parseConfigState(state(espId = "esp-ffffff"), esp).assertInvalid(EspErrorCode.ESP_ID_MISMATCH)
    }

    @Test
    fun configStateOver2048BytesIsTooLarge() {
        EspContract.parseConfigState(state(extra = ""","x":"${"a".repeat(2100)}""""), esp)
            .assertInvalid(EspErrorCode.PAYLOAD_TOO_LARGE)
    }

    @Test
    fun configStateWithDuplicatedChannelIsChannelConflict() {
        val dup = """[{"led_id":"A","kind":"POSICAO","channel_id":"CH01"},{"led_id":"B","kind":"POSICAO","channel_id":"CH01"}]"""
        EspContract.parseConfigState(state(mappings = dup), esp).assertInvalid(EspErrorCode.CHANNEL_CONFLICT)
    }

    private fun result(status: String, errorCode: String? = null, espId: String = esp, requestId: String = rid): String {
        val err = errorCode?.let { ""","error_code":"$it"""" } ?: ""
        return """{"schema_version":1,"esp_id":"$espId","boot_id":"ab12cd34","request_id":"$requestId","status":"$status"$err,"config_revision":5}"""
    }

    @Test
    fun configResultOkValid() {
        val r = EspContract.parseConfigResult(result("OK"), esp).valid()
        assertEquals(ConfigResultStatus.OK, r.status)
        assertNull(r.errorCode)
        assertEquals(5L, r.configRevision)
    }

    @Test
    fun configResultErroWithRevisionMismatchValid() {
        val r = EspContract.parseConfigResult(result("ERRO", "REVISION_MISMATCH"), esp).valid()
        assertEquals(ConfigResultStatus.ERRO, r.status)
        assertEquals(EspErrorCode.REVISION_MISMATCH, r.errorCode)
    }

    @Test
    fun configResultAcceptsEveryResultErrorCode() {
        val codes = EspErrorCode.entries.filter { it != EspErrorCode.INVALID_CHANNEL_TABLE }
        assertEquals(10, codes.size)
        codes.forEach { EspContract.parseConfigResult(result("ERRO", it.name), esp).valid() }
    }

    @Test
    fun configResultErroWithoutErrorCodeIsInvalidSchema() {
        EspContract.parseConfigResult(result("ERRO"), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun configResultOkWithErrorCodeIsInvalidSchema() {
        EspContract.parseConfigResult(result("OK", "INTERNAL_ERROR"), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun invalidChannelTableIsOnlyForStatus() {
        EspContract.parseConfigResult(result("ERRO", "INVALID_CHANNEL_TABLE"), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun configResultUnknownErrorCodeOrBadRequestIdIsInvalidSchema() {
        EspContract.parseConfigResult(result("ERRO", "UNSUPPORTED_COLOR"), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseConfigResult(result("OK", requestId = "x"), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun configResultEspIdDifferentFromTopicIsMismatch() {
        EspContract.parseConfigResult(result("OK", espId = "esp-ffffff"), esp).assertInvalid(EspErrorCode.ESP_ID_MISMATCH)
    }

    @Test
    fun errorCodeEnumMatchesSpecSection7() {
        assertEquals(
            setOf(
                "REVISION_MISMATCH", "INVALID_SCHEMA", "LED_ID_CONFLICT", "UNKNOWN_CHANNEL", "CHANNEL_CONFLICT",
                "PAYLOAD_TOO_LARGE", "STORAGE_FAILURE", "EXPIRED", "ESP_ID_MISMATCH", "INTERNAL_ERROR", "INVALID_CHANNEL_TABLE"
            ),
            EspErrorCode.entries.map { it.name }.toSet()
        )
    }
}
