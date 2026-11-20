package com.uniso.wms.pickbylight.data.mqtt

import app.cash.turbine.test
import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.esp.TEST_ESP
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResultStatus
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Etapa 3b (itens 3 a 8 + diagnósticos 0x10): reason codes, QoS 1, obsoletos, erros semânticos, timeout e queda. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeMqttEspErrorsAndDiagnosticsTest {

    private val rid1 = "123e4567-e89b-12d3-a456-426614174000"
    private val rid2 = "223e4567-e89b-12d3-a456-426614174000"
    private val channels = listOf(
        EspChannel("CH01", 4, true),
        EspChannel("CH02", 13, true),
        EspChannel("CH03", 16, true)
    )
    private val map1 = EspMapping("POS_A1_01", MappingKind.POSICAO, "CH03")

    private fun esp(
        id: String = TEST_ESP,
        revision: Long = 4L,
        bootId: String = "1111aaaa",
        mappings: List<EspMapping> = emptyList()
    ) = VirtualEsp(id, channels, mappings, bootId, revision)

    private fun fake(vararg esps: VirtualEsp, mode: MqttProtocolMode = MqttProtocolMode.AUTO) =
        FakeMqttService(protocolMode = mode).also { f -> esps.forEach { f.addVirtualEsp(it) } }

    private fun set(revision: Long = 4L, requestId: String = rid1, mappings: List<EspMapping> = listOf(map1)) =
        ConfigSet(EspContract.SCHEMA_VERSION, TEST_ESP, requestId, expectedRevision = revision, mappings = mappings)

    @Test
    fun puback0x87And0x95FailImmediatelyWithoutWaitingTimeout() = runTest {
        val service = fake(esp())
        val setTopic = MqttTopics.espConfigSet(TEST_ESP)
        val set = set()

        service.simulatedPubackReasonCodes[setTopic] = 0x87
        val start87 = currentTime
        val res87 = service.gravarConfig(set)
        assertTrue(res87.isFailure)
        assertTrue(res87.exceptionOrNull()?.message?.contains("0x87") == true)
        assertTrue(currentTime - start87 < 1_000L, "0x87 não pode esperar timeout de 5 s")

        service.simulatedPubackReasonCodes[setTopic] = 0x95
        val start95 = currentTime
        val res95 = service.gravarConfig(set)
        assertTrue(res95.isFailure)
        assertTrue(res95.exceptionOrNull()?.message?.contains("0x95") == true)
        assertTrue(currentTime - start95 < 1_000L, "0x95 não pode esperar timeout de 5 s")
    }

    @Test
    fun puback0x10OnAbsentEspWaits5sAndReturnsTimeoutWithDiagnostic() = runTest {
        val service = fake() // nenhum ESP registrado
        val setTopic = MqttTopics.espConfigSet(TEST_ESP)
        service.simulatedPubackReasonCodes[setTopic] = 0x10
        val set = set()

        val start = currentTime
        val result = service.gravarConfig(set)
        val elapsed = currentTime - start

        val outcome = assertIs<MqttOutcome.Timeout>(result.getOrThrow())
        assertTrue(elapsed >= EspManagementTimings.CONFIG_TIMEOUT_MS, "0x10 não pode encurtar o timeout ($elapsed ms)")
        assertEquals(listOf(MqttDiagnostic(0x10, "No matching subscribers (0x10)", setTopic)), outcome.diagnostics)
    }

    @Test
    fun puback0x10OnResponsiveEspReturnsOkWithDiagnosticAttached() = runTest {
        val service = fake(esp(revision = 4L))
        val setTopic = MqttTopics.espConfigSet(TEST_ESP)
        service.simulatedPubackReasonCodes[setTopic] = 0x10
        val set = set()

        val outcome = assertIs<MqttOutcome.Ok<ConfigResult>>(service.gravarConfig(set).getOrThrow())
        assertEquals(5L, outcome.value.configRevision)
        assertEquals(listOf(MqttDiagnostic(0x10, "No matching subscribers (0x10)", setTopic)), outcome.diagnostics)
    }

    @Test
    fun mode311SameScenarioProducesNoDiagnosticsAndIdenticalOutcome() = runTest {
        val setTopic = MqttTopics.espConfigSet(TEST_ESP)
        val set = set()

        val presentService = fake(esp(revision = 4L), mode = MqttProtocolMode.V3_1_1).apply {
            simulatedPubackReasonCodes[setTopic] = 0x10
        }
        val okOutcome = assertIs<MqttOutcome.Ok<ConfigResult>>(presentService.gravarConfig(set).getOrThrow())
        assertEquals(5L, okOutcome.value.configRevision)
        assertTrue(okOutcome.diagnostics.isEmpty())

        val absentService = fake(mode = MqttProtocolMode.V3_1_1).apply {
            simulatedPubackReasonCodes[setTopic] = 0x10
        }
        val timeoutOutcome = assertIs<MqttOutcome.Timeout>(absentService.gravarConfig(set).getOrThrow())
        assertTrue(timeoutOutcome.diagnostics.isEmpty())
    }

    @Test
    fun qos1DuplicateDeliveryReturnsSameResultAndIncrementsRevisionOnce() = runTest {
        val virtual = esp(revision = 4L)
        val service = fake(virtual).apply { simulateQos1DuplicateDelivery = true }
        val set = set()

        val first = assertIs<MqttOutcome.Ok<ConfigResult>>(service.gravarConfig(set).getOrThrow())
        assertEquals(5L, first.value.configRevision)
        assertEquals(5L, virtual.configRevision)

        val retry = assertIs<MqttOutcome.Ok<ConfigResult>>(service.gravarConfig(set).getOrThrow())
        assertEquals(ConfigResultStatus.OK, retry.value.status)
        assertEquals(5L, retry.value.configRevision)
        assertEquals(5L, virtual.configRevision)
    }

    @Test
    fun unknownRequestIdResultIsIgnoredAndOldBootIdIsDiscarded() = runTest {
        val virtual = esp(revision = 4L, bootId = "1111aaaa")
        val service = fake(virtual)
        service.descobrirEsps().getOrThrow()

        service.onPublishConfigSet = { set, payload ->
            val unknown = ConfigResult(
                EspContract.SCHEMA_VERSION, TEST_ESP, virtual.bootId, rid2,
                ConfigResultStatus.ERRO, EspErrorCode.INTERNAL_ERROR, virtual.configRevision
            )
            simulateIncomingConfigResult(EspContract.encodeConfigResult(unknown), TEST_ESP)
            val ok = virtual.handleConfigSet(set.requestId, payload)
            simulateIncomingConfigResult(EspContract.encodeConfigResult(ok), TEST_ESP)
            simulateIncomingConfigState(EspContract.encodeConfigState(virtual.state(set.requestId)), TEST_ESP)
        }
        val out = assertIs<MqttOutcome.Ok<ConfigResult>>(service.gravarConfig(set()).getOrThrow())
        assertEquals(rid1, out.value.requestId)
        assertTrue(service.discardedObsoleteMessages.any { it.contains("unknown_request=$rid2") })

        service.onPublishConfigSet = null
        virtual.reboot("2222bbbb")
        service.descobrirEsps().getOrThrow()

        val staleStatusJson = EspContract.encodeStatusOnline(
            virtual.status().copy(bootId = "1111aaaa", configRevision = 1L)
        )
        val staleStateJson = EspContract.encodeConfigState(
            virtual.state().copy(bootId = "1111aaaa", configRevision = 1L, mappings = emptyList())
        )
        assertEquals(false, service.simulateIncomingStatus(staleStatusJson, TEST_ESP))
        assertEquals(false, service.simulateIncomingConfigState(staleStateJson, TEST_ESP))

        val current = service.espDevices.value.single()
        assertEquals("2222bbbb", current.status?.bootId)
        assertEquals(5L, current.config?.configRevision)
        assertTrue(service.discardedObsoleteMessages.any { it.contains("retired_boot=1111aaaa") })
    }

    @Test
    fun configSetWithWrongExpectedRevisionReturnsRevisionMismatch() = runTest {
        val virtual = esp(revision = 4L)
        val service = fake(virtual)

        val mismatch = assertIs<MqttOutcome.Erro>(service.gravarConfig(set(revision = 3L)).getOrThrow())
        assertEquals(EspErrorCode.REVISION_MISMATCH, mismatch.errorCode)
        assertEquals(4L, mismatch.config?.configRevision)
        assertEquals(4L, virtual.configRevision)
    }

    @Test
    fun configSetWithUnknownChannelReturnsUnknownChannel() = runTest {
        val virtual = esp(revision = 4L)
        val service = fake(virtual)

        val unknownCh = assertIs<MqttOutcome.Erro>(
            service.gravarConfig(set(mappings = listOf(EspMapping("POS_A1_01", MappingKind.POSICAO, "CH99")))).getOrThrow()
        )
        assertEquals(EspErrorCode.UNKNOWN_CHANNEL, unknownCh.errorCode)
        assertEquals(4L, virtual.configRevision)
    }

    @Test
    fun configSetWithDuplicatedChannelReturnsChannelConflict() = runTest {
        val virtual = esp(revision = 4L)
        val service = fake(virtual)

        val chConflict = assertIs<MqttOutcome.Erro>(
            service.gravarConfig(
                set(
                    mappings = listOf(
                        EspMapping("POS_A1_01", MappingKind.POSICAO, "CH01"),
                        EspMapping("POS_A1_02", MappingKind.POSICAO, "CH01")
                    )
                )
            ).getOrThrow()
        )
        assertEquals(EspErrorCode.CHANNEL_CONFLICT, chConflict.errorCode)
        assertEquals(4L, virtual.configRevision)
    }

    @Test
    fun configSetWithDuplicatedLedIdReturnsLedIdConflict() = runTest {
        val virtual = esp(revision = 4L)
        val service = fake(virtual)

        val ledConflict = assertIs<MqttOutcome.Erro>(
            service.gravarConfig(
                set(
                    mappings = listOf(
                        EspMapping("POS_A1_01", MappingKind.POSICAO, "CH01"),
                        EspMapping("POS_A1_01", MappingKind.POSICAO, "CH02")
                    )
                )
            ).getOrThrow()
        )
        assertEquals(EspErrorCode.LED_ID_CONFLICT, ledConflict.errorCode)
        assertEquals(4L, virtual.configRevision)
    }

    @Test
    fun silentEspTimesOutIn5sAndNeverBecomesEmptyConfig() = runTest {
        val virtual = esp(revision = 4L, mappings = listOf(map1))
        val service = fake(virtual)
        service.descobrirEsps().getOrThrow()
        service.silentEspIds.add(TEST_ESP)

        val start = currentTime
        val getOut = assertIs<MqttOutcome.Timeout>(service.obterConfig(TEST_ESP).getOrThrow())
        assertTrue(currentTime - start >= EspManagementTimings.CONFIG_TIMEOUT_MS)
        assertTrue(getOut.diagnostics.isEmpty())
        assertNull(service.espDevices.value.single().config, "timeout não vira lista vazia")

        assertIs<MqttOutcome.Timeout>(service.gravarConfig(set(mappings = emptyList())).getOrThrow())
        assertIs<MqttOutcome.Timeout>(service.testarLedComConfirmacao("EST_A_LED1", "PRAT_A1_LED1", "POS_A1_01").getOrThrow())
    }

    @Test
    fun connectionDropMidConfigSetFailsWithoutQueueOrAutoResendOnReconnect() = runTest {
        val service = fake(esp(revision = 4L))
        val set = set()

        service.onPublishConfigSet = { _, _ -> disconnect() }

        service.pendingCommandsCount.test {
            assertEquals(0, awaitItem())
            val res = service.gravarConfig(set)
            assertTrue(res.isFailure)
            assertEquals(0, service.pendingCommandsCount.value)
            val countBeforeReconnect = service.sentManagement.size
            service.connect()
            assertEquals(countBeforeReconnect, service.sentManagement.size, "config/set nunca é reenviado após reconectar")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
