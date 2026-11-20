package com.uniso.wms.pickbylight.data.mqtt

import app.cash.turbine.test
import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.esp.TEST_ESP
import com.uniso.wms.pickbylight.data.esp.valid
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Etapa 3a: caminho feliz do gerenciamento de ESPs no [FakeMqttService]. Cenários de erro ficam na 3b. */
class FakeMqttEspManagementTest {

    private val otherEsp = "esp-000001"
    private val rid = "123e4567-e89b-12d3-a456-426614174000"
    private val channels = listOf(
        EspChannel("CH01", 4, true),
        EspChannel("CH02", 13, true),
        EspChannel("CH03", 16, true)
    )
    private val posMapping = EspMapping("POS_A1_01", MappingKind.POSICAO, "CH03")

    private fun fake(vararg esps: VirtualEsp) = FakeMqttService().also { f -> esps.forEach { f.addVirtualEsp(it) } }

    private fun esp(id: String = TEST_ESP, revision: Long = 4L, mappings: List<EspMapping> = emptyList()) =
        VirtualEsp(espId = id, channels = channels, mappings = mappings, configRevision = revision)

    private fun <T> Result<T>.ok(): T = getOrElse { fail("esperado sucesso, veio: ${it.message}") }

    private fun <T> MqttOutcome<T>.value(): T = when (this) {
        is MqttOutcome.Ok -> value
        is MqttOutcome.Erro -> fail("esperado OK, veio ERRO($errorCode)")
        MqttOutcome.Timeout -> fail("esperado OK, veio TIMEOUT")
    }

    @Test
    fun discoveryListsVirtualEspsSortedAndPublishesDiscoveryRequest() = runTest {
        val service = fake(esp(TEST_ESP), esp(otherEsp, revision = 0L))

        service.espDevices.test {
            assertTrue(awaitItem().isEmpty(), "antes do discovery nenhum ESP é conhecido")

            val found = service.descobrirEsps().ok()

            assertEquals(listOf(otherEsp, TEST_ESP), found.map { it.espId })
            assertEquals(found, expectMostRecentItem())
            cancelAndIgnoreRemainingEvents()
        }

        val device = service.espDevices.value.first { it.espId == TEST_ESP }
        assertEquals(EspPresence.ONLINE, device.presence)
        assertEquals(4L, device.status?.configRevision)
        assertEquals("ab12cd34", device.status?.bootId)
        assertEquals(3, device.status?.capabilities?.channels?.size)

        val (topic, payload) = service.sentManagement.single()
        assertEquals(MqttTopics.V1_DISCOVERY_REQUEST, topic)
        EspContract.parseDiscoveryRequest(payload).valid()
        assertFalse(payload.contains("null"), payload)
    }

    @Test
    fun configGetReturnsStateWithRevisionBootIdAndEchoedRequestId() = runTest {
        val service = fake(esp(revision = 4L, mappings = listOf(posMapping)))

        val state = service.obterConfig(TEST_ESP).ok().value()

        assertEquals(TEST_ESP, state.espId)
        assertEquals(4L, state.configRevision)
        assertEquals("ab12cd34", state.bootId)
        assertEquals(listOf(posMapping), state.mappings)

        val (topic, payload) = service.sentManagement.single()
        assertEquals(MqttTopics.espConfigGet(TEST_ESP), topic)
        val sent = EspContract.parseConfigGet(payload, TEST_ESP).valid()
        assertEquals(sent.requestId, state.lastRequestId, "o ESP devolve o request_id do get em last_request_id")
        assertEquals(state, service.espDevices.value.single().config)
    }

    @Test
    fun configSetOkIncrementsRevisionAndStateReflectsIt() = runTest {
        val virtual = esp(revision = 4L)
        val service = fake(virtual)
        val set = ConfigSet(EspContract.SCHEMA_VERSION, TEST_ESP, rid, expectedRevision = 4L, mappings = listOf(posMapping))

        service.espDevices.test {
            awaitItem()

            val result = service.gravarConfig(set).ok().value()

            assertEquals(rid, result.requestId)
            assertEquals(5L, result.configRevision, "config_revision do result é a revisão nova")
            assertNull(result.errorCode)
            assertEquals(5L, virtual.configRevision)
            assertEquals(listOf(posMapping), virtual.mappings)
            assertEquals(5L, expectMostRecentItem().single().config?.configRevision)
            cancelAndIgnoreRemainingEvents()
        }

        val again = service.obterConfig(TEST_ESP).ok().value()
        assertEquals(5L, again.configRevision)
        assertEquals(listOf(posMapping), again.mappings)

        val (topic, payload) = service.sentManagement.first()
        assertEquals(MqttTopics.espConfigSet(TEST_ESP), topic)
        assertFalse(payload.contains("null"), payload)
        assertEquals(set, EspContract.parseConfigSet(payload, TEST_ESP).valid())
    }

    @Test
    fun configSetIsNeverQueuedWhenDisconnected() = runTest {
        val service = fake(esp())
        service.setConnectionState(MqttConnectionState.DISCONNECTED)
        val set = ConfigSet(EspContract.SCHEMA_VERSION, TEST_ESP, rid, expectedRevision = 4L, mappings = emptyList())

        assertTrue(service.gravarConfig(set).isFailure)

        assertEquals(0, service.pendingCommandsCount.value, "config/set não entra na fila de comandos pendentes")
        assertTrue(service.sentManagement.isEmpty())
    }

    @Test
    fun testarLedComConfirmacaoIsOkWhenTheOwnerEspAcks() = runTest {
        val service = fake(esp(mappings = listOf(posMapping)))

        val outcome = service.testarLedComConfirmacao("EST_A_LED1", "PRAT_A1_LED1", "POS_A1_01").ok()

        assertIs<MqttOutcome.Ok<Unit>>(outcome)
        assertEquals(
            """{"acao":"TESTE_LED","estante_led_id":"EST_A_LED1","prateleira_led_id":"PRAT_A1_LED1","posicao_led_id":"POS_A1_01","cor":"AZUL","duracao_ms":3000}""",
            service.sentCommands.single(),
            "continua o TESTE_LED legado, nunca config/set"
        )
        assertTrue(service.sentManagement.isEmpty())
    }

    @Test
    fun negotiatedProtocolAndCapabilitiesFollowTheConnection() = runTest {
        val service = FakeMqttService()

        service.negotiatedProtocol.test {
            assertEquals(MqttProtocol.V5_0, awaitItem())
            assertTrue(service.capabilities.value.responseTopic)

            service.disconnect()
            assertNull(awaitItem())
            assertEquals(MqttCapabilities.NONE, service.capabilities.value)

            service.brokerProtocol = MqttProtocol.V3_1_1
            service.setConnectionState(MqttConnectionState.CONNECTED)
            assertEquals(MqttProtocol.V3_1_1, awaitItem())
            assertFalse(service.capabilities.value.messageExpiry, "3.1.1 não oferece Message Expiry")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
