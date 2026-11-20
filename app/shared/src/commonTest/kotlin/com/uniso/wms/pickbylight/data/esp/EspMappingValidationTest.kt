package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Validadores puros do app antes de publicar `config/set` (seção 3.4, regras 1 a 6). */
class EspMappingValidationTest {

    private val channels = listOf(
        EspChannel("CH01", 4, true),
        EspChannel("CH02", 13, true),
        EspChannel("CH03", 16, true)
    )
    private val estante = Estante(id = "e1", codigo = "A", nome = "Estante A", ledId = "EST_A_LED1")
    private val prateleira = Prateleira(id = "p1", estanteId = "e1", codigo = "A1", ledIdPonta = "PRAT_A1_LED1")
    private val posicao = Posicao(id = "x1", prateleiraId = "p1", codigo = "A1-01", ledId = "POS_A1_01")
    private val domain = DomainLedIds.from(listOf(estante), listOf(prateleira), listOf(posicao))

    private fun m(led: String, kind: MappingKind, ch: String) = EspMapping(led, kind, ch)

    private fun violations(
        mappings: List<EspMapping>,
        known: Map<String, List<EspMapping>> = emptyMap()
    ) = EspContract.validateMappingsForPublish("esp-a1b2c3", mappings, channels, domain, known)

    @Test
    fun validMappingsHaveNoViolations() {
        val ok = listOf(
            m("EST_A_LED1", MappingKind.ESTANTE, "CH01"),
            m("PRAT_A1_LED1", MappingKind.PRATELEIRA, "CH02"),
            m("POS_A1_01", MappingKind.POSICAO, "CH03")
        )
        assertTrue(violations(ok).isEmpty())
        assertTrue(violations(emptyList()).isEmpty())
    }

    @Test
    fun domainIdsAreBuiltFromEstanteLedIdPrateleiraLedIdPontaAndPosicaoLedId() {
        assertEquals(setOf("EST_A_LED1"), domain.estantes)
        assertEquals(setOf("PRAT_A1_LED1"), domain.prateleiras)
        assertEquals(setOf("POS_A1_01"), domain.posicoes)
    }

    @Test
    fun unknownChannelIsReported() {
        val v = violations(listOf(m("POS_A1_01", MappingKind.POSICAO, "CH99")))
        assertEquals(listOf(MappingViolationType.UNKNOWN_CHANNEL), v.map { it.type })
        assertEquals("CH99", v.single().channelId)
    }

    @Test
    fun duplicatedChannelIsReported() {
        val v = violations(listOf(m("POS_A1_01", MappingKind.POSICAO, "CH01"), m("EST_A_LED1", MappingKind.ESTANTE, "CH01")))
        assertEquals(listOf(MappingViolationType.CHANNEL_DUPLICATED), v.map { it.type })
    }

    @Test
    fun duplicatedLedIdIsReported() {
        val v = violations(listOf(m("POS_A1_01", MappingKind.POSICAO, "CH01"), m("POS_A1_01", MappingKind.POSICAO, "CH02")))
        assertEquals(listOf(MappingViolationType.LED_ID_DUPLICATED), v.map { it.type })
    }

    @Test
    fun ledIdMissingFromDomainIsUnknownLedId() {
        val v = violations(listOf(m("POS_INEXISTENTE", MappingKind.POSICAO, "CH01")))
        assertEquals(listOf(MappingViolationType.UNKNOWN_LED_ID), v.map { it.type })
    }

    @Test
    fun ledIdOfAnotherKindIsKindMismatch() {
        val v = violations(listOf(m("POS_A1_01", MappingKind.ESTANTE, "CH01")))
        assertEquals(listOf(MappingViolationType.KIND_MISMATCH), v.map { it.type })
    }

    @Test
    fun ledIdOwnedByAnotherKnownEspIsConflict() {
        val known = mapOf("esp-ffffff" to listOf(m("POS_A1_01", MappingKind.POSICAO, "CH05")))
        val v = violations(listOf(m("POS_A1_01", MappingKind.POSICAO, "CH01")), known)
        assertEquals(listOf(MappingViolationType.LED_ID_OWNED_BY_OTHER_ESP), v.map { it.type })
        assertEquals("esp-ffffff", v.single().otherEspId)
    }

    @Test
    fun ownMappingsAreNotAConflictWithItself() {
        val own = listOf(m("POS_A1_01", MappingKind.POSICAO, "CH01"))
        assertTrue(violations(own, mapOf("esp-a1b2c3" to own)).isEmpty())
    }

    @Test
    fun moreThan14MappingsIsTooMany() {
        val many = (1..15).map { m("POS_$it", MappingKind.POSICAO, "CH01") }
        assertTrue(violations(many).any { it.type == MappingViolationType.TOO_MANY_MAPPINGS })
    }

    @Test
    fun espIdAndBootIdAndChannelIdFormats() {
        assertTrue(EspContract.ESP_ID_REGEX.matches("esp-a1b2c3"))
        assertTrue(!EspContract.ESP_ID_REGEX.matches("esp-A1B2C3"))
        assertTrue(!EspContract.ESP_ID_REGEX.matches("esp-a1b2c"))
        assertTrue(!EspContract.ESP_ID_REGEX.matches("esp-a1b2c3d"))
        assertTrue(EspContract.BOOT_ID_REGEX.matches("9f1a2b3c"))
        assertTrue(!EspContract.BOOT_ID_REGEX.matches("9f1a2b3"))
        assertTrue(!EspContract.BOOT_ID_REGEX.matches("ZZZZZZZZ"))
        assertTrue(EspContract.CHANNEL_ID_REGEX.matches("CH14"))
        assertTrue(!EspContract.CHANNEL_ID_REGEX.matches("CH1"))
        assertTrue(!EspContract.CHANNEL_ID_REGEX.matches("ch01"))
    }

    @Test
    fun allowedGpiosExcludeEveryForbiddenPinAndGpio2() {
        val forbidden = setOf(0, 1, 2, 3, 6, 7, 8, 9, 10, 11, 12, 34, 35, 36, 39)
        assertTrue(EspContract.ALLOWED_CHANNEL_GPIOS.intersect(forbidden).isEmpty())
        assertEquals(setOf(4, 13, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33), EspContract.ALLOWED_CHANNEL_GPIOS - setOf(5, 14, 15))
    }
}
