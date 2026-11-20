package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Seção 6.6 de MQTT5_E_ESP32.md: valido e cada invalido com o error_code esperado. */
class EspContractConfigSetTest {

    private val esp = TEST_ESP
    private val rid = TEST_RID
    private val channels = (1..3).map { EspChannel("CH0$it", listOf(4, 13, 16)[it - 1], true) }

    private fun set(mappings: String, esp: String = this.esp, revision: Int = 4, extra: String = "") =
        """{"schema_version":1,"esp_id":"$esp","request_id":"$rid","expected_revision":$revision$extra,"mappings":$mappings}"""

    private val specMappings = """[
        {"led_id":"EST_A_LED1","kind":"ESTANTE","channel_id":"CH01"},
        {"led_id":"PRAT_A1_LED1","kind":"PRATELEIRA","channel_id":"CH02"},
        {"led_id":"POS_A1_01","kind":"POSICAO","channel_id":"CH03"}]"""

    @Test
    fun validExampleFromSpec() {
        val s = EspContract.parseConfigSet(set(specMappings), esp, activeRevision = 4, channels = channels).valid()
        assertEquals(3, s.mappings.size)
        assertEquals(MappingKind.POSICAO, s.mappings[2].kind)
        assertEquals(4L, s.expectedRevision)
    }

    @Test
    fun emptyMappingsIsValid() {
        assertTrue(EspContract.parseConfigSet(set("[]"), esp, 4, channels).valid().mappings.isEmpty())
    }

    @Test
    fun optionalIssuedAtAndTtlAreAccepted() {
        val p = set("[]", extra = ""","issued_at_ms":1000,"ttl_ms":5000""")
        assertEquals(5000L, EspContract.parseConfigSet(p, esp).valid().ttlMs)
    }

    @Test
    fun unknownChannelIsUnknownChannel() {
        val p = set("""[{"led_id":"POS_A1_01","kind":"POSICAO","channel_id":"CH99"}]""")
        EspContract.parseConfigSet(p, esp, 4, channels).assertInvalid(EspErrorCode.UNKNOWN_CHANNEL)
    }

    @Test
    fun duplicatedChannelIsChannelConflict() {
        val p = set("""[{"led_id":"A","kind":"POSICAO","channel_id":"CH03"},{"led_id":"B","kind":"POSICAO","channel_id":"CH03"}]""")
        EspContract.parseConfigSet(p, esp, 4, channels).assertInvalid(EspErrorCode.CHANNEL_CONFLICT)
    }

    @Test
    fun duplicatedLedIdIsLedIdConflict() {
        val p = set("""[{"led_id":"POS_A1_01","kind":"POSICAO","channel_id":"CH01"},{"led_id":"POS_A1_01","kind":"POSICAO","channel_id":"CH02"}]""")
        EspContract.parseConfigSet(p, esp, 4, channels).assertInvalid(EspErrorCode.LED_ID_CONFLICT)
    }

    @Test
    fun espIdDifferentFromTopicIsEspIdMismatch() {
        EspContract.parseConfigSet(set("[]", esp = "esp-ffffff"), esp).assertInvalid(EspErrorCode.ESP_ID_MISMATCH)
    }

    @Test
    fun wrongExpectedRevisionIsRevisionMismatch() {
        EspContract.parseConfigSet(set(specMappings, revision = 3), esp, activeRevision = 4, channels = channels)
            .assertInvalid(EspErrorCode.REVISION_MISMATCH)
    }

    @Test
    fun payloadOver2048BytesIsPayloadTooLarge() {
        val padding = ""","issued_at_ms":1,"x":"${"a".repeat(2100)}""""
        EspContract.parseConfigSet(set("[]", extra = padding), esp).assertInvalid(EspErrorCode.PAYLOAD_TOO_LARGE)
    }

    @Test
    fun payloadUpTo2048BytesIsNotTooLarge() {
        // 14 mappings no limite do schema continuam abaixo de 2048 bytes.
        val many = (1..14).joinToString(",", "[", "]") {
            """{"led_id":"POS_A1_${it.toString().padStart(2, '0')}","kind":"POSICAO","channel_id":"CH${it.toString().padStart(2, '0')}"}"""
        }
        val p = set(many)
        assertTrue(EspContract.utf8Size(p) <= EspContract.MAX_CONFIG_BYTES)
        EspContract.parseConfigSet(p, esp).valid()
    }

    @Test
    fun malformedJsonAndWrongVersionAreInvalidSchema() {
        EspContract.parseConfigSet("{", esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseConfigSet(set("[]").replace("\"schema_version\":1", "\"schema_version\":2"), esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun appMustNotSendBootIdOrConfigRevision() {
        EspContract.parseConfigSet(set("[]", extra = ""","boot_id":"ab12cd34""""), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseConfigSet(set("[]", extra = ""","config_revision":4"""), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun missingRequiredFieldsAreInvalidSchema() {
        EspContract.parseConfigSet("""{"schema_version":1,"esp_id":"$esp","mappings":[]}""", esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
        EspContract.parseConfigSet("""{"schema_version":1,"esp_id":"$esp","request_id":"$rid","expected_revision":1}""", esp)
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun encodeConfigSetOmitsNullOptionalsAndRoundTrips() {
        val cs = ConfigSet(1, esp, rid, 4, mappings = listOf(EspMapping("POS_A1_01", MappingKind.POSICAO, "CH03")))
        val encoded = EspContract.encodeConfigSet(cs).valid()
        assertFalse(encoded.contains("null"), encoded)
        assertFalse(encoded.contains("issued_at_ms"))
        assertEquals(cs, EspContract.parseConfigSet(encoded, esp).valid())
    }

    // Espaços finais são JSON válido: permitem fixar o tamanho exato sem criar chave desconhecida.
    private fun padTo(payload: String, bytes: Int) = payload + " ".repeat(bytes - EspContract.utf8Size(payload))

    @Test
    fun parseAcceptsExactly2048BytesAndRejects2049() {
        val base = set("[]")
        EspContract.parseConfigSet(padTo(base, 2048), esp).valid()
        EspContract.parseConfigSet(padTo(base, 2049), esp).assertInvalid(EspErrorCode.PAYLOAD_TOO_LARGE)
    }

    @Test
    fun sizeLimitCountsUtf8BytesNotCharacters() {
        // 1100 caracteres, mas 2 bytes cada: 2200 bytes. Se o limite contasse chars, passaria.
        val payload = set("[]", extra = ""","x":"${"é".repeat(1100)}"""")
        assertTrue(payload.length < EspContract.MAX_CONFIG_BYTES)
        assertTrue(EspContract.utf8Size(payload) > EspContract.MAX_CONFIG_BYTES)
        EspContract.parseConfigSet(payload, esp).assertInvalid(EspErrorCode.PAYLOAD_TOO_LARGE)
    }

    private fun mappings(n: Int, ledLen: Int = 64) = List(n) {
        EspMapping("L".repeat(ledLen - 2) + it.toString().padStart(2, '0'), MappingKind.PRATELEIRA, "CH" + (it + 1).toString().padStart(2, '0'))
    }

    @Test
    fun encodeAcceptsWorstCaseOf14MappingsBelow2048Bytes() {
        // Pior caso do schema: 14 mappings, led_id de 64 caracteres, kind PRATELEIRA (o mais longo), com opcionais.
        val worst = EspContract.encodeConfigSet(ConfigSet(1, esp, rid, 4, 1L, 65000L, mappings(14))).valid()
        val size = EspContract.utf8Size(worst)
        println("pior caso config/set (14 mappings, led_id de 64): $size bytes de ${EspContract.MAX_CONFIG_BYTES}")
        assertTrue(size < EspContract.MAX_CONFIG_BYTES, "pior caso = $size bytes")
    }

    @Test
    fun encodeRejects15MappingsWithInvalidSchema() {
        EspContract.encodeConfigSet(ConfigSet(1, esp, rid, 4, mappings = mappings(15)))
            .assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }

    @Test
    fun parseRejects15MappingsWithInvalidSchema() {
        val many = (1..15).joinToString(",", "[", "]") {
            """{"led_id":"POS_$it","kind":"POSICAO","channel_id":"CH${it.toString().padStart(2, '0')}"}"""
        }
        EspContract.parseConfigSet(set(many), esp).assertInvalid(EspErrorCode.INVALID_SCHEMA)
    }
}