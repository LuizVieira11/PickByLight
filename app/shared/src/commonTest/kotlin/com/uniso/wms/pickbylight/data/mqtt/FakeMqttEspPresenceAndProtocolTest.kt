package com.uniso.wms.pickbylight.data.mqtt

import app.cash.turbine.test
import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.esp.TEST_ESP
import com.uniso.wms.pickbylight.data.esp.valid
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResultStatus
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.EspOnlineStatus
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import com.uniso.wms.pickbylight.data.mqtt.model.OfflineReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Etapa 3b (itens 1, 2 e 9): presença STALE/ONLINE/OFFLINE/CONFIG_INVALID, ordem SUBACK e fallback 3.1.1. */
class FakeMqttEspPresenceAndProtocolTest {

    private val otherEsp = "esp-000001"
    private val rid1 = "123e4567-e89b-12d3-a456-426614174000"
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
        reportedStatus: EspOnlineStatus = EspOnlineStatus.ONLINE,
        errorCode: EspErrorCode? = null
    ) = VirtualEsp(
        espId = id,
        channels = channels,
        bootId = bootId,
        configRevision = revision,
        reportedStatus = reportedStatus,
        statusErrorCode = errorCode
    )

    private fun fake(vararg esps: VirtualEsp, mode: MqttProtocolMode = MqttProtocolMode.AUTO) =
        FakeMqttService(protocolMode = mode).also { f -> esps.forEach { f.addVirtualEsp(it) } }

    private fun set(revision: Long = 4L, mappings: List<EspMapping> = listOf(map1)) =
        ConfigSet(EspContract.SCHEMA_VERSION, TEST_ESP, rid1, expectedRevision = revision, mappings = mappings)

    @Test
    fun retainedStatusEntersAsStaleAndOfflineRetainedEntersAsOffline() = runTest {
        val onlineRetained = esp(TEST_ESP, revision = 2L)
        val offlineRetained = esp(otherEsp, revision = 1L)
        val service = FakeMqttService().apply {
            seedRetainedStatus(onlineRetained.status())
            seedRetainedConfig(onlineRetained.state())
            seedRetainedOffline(offlineRetained.offlineStatus(OfflineReason.LWT))
        }

        service.subscribeV1Topics().getOrThrow()

        val byId = service.espDevices.value.associateBy { it.espId }
        assertEquals(EspPresence.STALE, byId[TEST_ESP]?.presence)
        assertEquals(2L, byId[TEST_ESP]?.config?.configRevision)
        assertEquals(EspPresence.OFFLINE, byId[otherEsp]?.presence)
    }

    @Test
    fun discoveryAndConfigGetPromoteStaleToOnline() = runTest {
        val active = esp(TEST_ESP)
        val silent = esp(otherEsp)
        val service = fake(active, silent).apply {
            seedRetainedStatus(active.status())
            seedRetainedStatus(silent.status())
            silentEspIds.add(otherEsp)
        }

        service.subscribeV1Topics().getOrThrow()
        assertEquals(listOf(EspPresence.STALE, EspPresence.STALE), service.espDevices.value.map { it.presence })

        service.descobrirEsps().getOrThrow()
        val afterDiscovery = service.espDevices.value.associateBy { it.espId }
        assertEquals(EspPresence.ONLINE, afterDiscovery[TEST_ESP]?.presence)
        assertEquals(EspPresence.STALE, afterDiscovery[otherEsp]?.presence)

        service.silentEspIds.remove(otherEsp)
        val getOutcome = service.obterConfig(otherEsp).getOrThrow()
        assertIs<MqttOutcome.Ok<ConfigState>>(getOutcome)
        assertEquals(EspPresence.ONLINE, service.espDevices.value.first { it.espId == otherEsp }.presence)
    }

    @Test
    fun reconnectDemotesOnlineDevicesBackToStaleAndLwtMarksOffline() = runTest {
        val virtual = esp(TEST_ESP)
        val service = fake(virtual)

        service.espDevices.test {
            assertEquals(emptyList(), awaitItem())
            service.descobrirEsps().getOrThrow()
            assertEquals(EspPresence.ONLINE, awaitItem().single().presence)

            service.disconnect()
            service.connect()
            assertEquals(EspPresence.STALE, awaitItem().single().presence)

            val lwtJson = EspContract.encodeStatusOffline(virtual.offlineStatus(OfflineReason.LWT))
            assertTrue(service.simulateIncomingOffline(lwtJson, TEST_ESP))
            assertEquals(EspPresence.OFFLINE, awaitItem().single().presence)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun configInvalidStatusIsExposedIndependentOfPresence() = runTest {
        val invalid = esp(
            id = TEST_ESP,
            reportedStatus = EspOnlineStatus.CONFIG_INVALID,
            errorCode = EspErrorCode.INVALID_CHANNEL_TABLE
        )
        val service = fake(invalid).apply { seedRetainedStatus(invalid.status()) }

        service.subscribeV1Topics().getOrThrow()
        val staleDevice = service.espDevices.value.single()
        assertEquals(EspPresence.STALE, staleDevice.presence)
        assertEquals(EspOnlineStatus.CONFIG_INVALID, staleDevice.reportedStatus)
        assertEquals(EspErrorCode.INVALID_CHANNEL_TABLE, staleDevice.errorCode)

        service.descobrirEsps().getOrThrow()
        val onlineDevice = service.espDevices.value.single()
        assertEquals(EspPresence.ONLINE, onlineDevice.presence)
        assertEquals(EspOnlineStatus.CONFIG_INVALID, onlineDevice.reportedStatus)
        assertEquals(EspErrorCode.INVALID_CHANNEL_TABLE, onlineDevice.errorCode)
    }

    @Test
    fun subackPrecedesDiscoveryGetAndSetPublications() = runTest {
        val service = fake(esp(revision = 4L))

        service.descobrirEsps().getOrThrow()
        val discEvents = service.protocolEvents
        val subackIdx = discEvents.indexOf("SUBACK(0x00)")
        assertTrue(discEvents.indexOf("SUBSCRIBE_V1") in 0 until subackIdx)
        assertTrue(subackIdx in 0 until discEvents.indexOf("PUBLISH(${MqttTopics.V1_DISCOVERY_REQUEST})"))

        service.disconnect()
        service.connect()
        service.clearProtocolEvents()
        service.obterConfig(TEST_ESP).getOrThrow()
        val getEvents = service.protocolEvents
        assertTrue(getEvents.indexOf("SUBACK(0x00)") in 0 until getEvents.indexOf("PUBLISH(${MqttTopics.espConfigGet(TEST_ESP)})"))

        service.disconnect()
        service.connect()
        service.clearProtocolEvents()
        service.gravarConfig(set()).getOrThrow()
        val setEvents = service.protocolEvents
        assertTrue(setEvents.indexOf("SUBACK(0x00)") in 0 until setEvents.indexOf("PUBLISH(${MqttTopics.espConfigSet(TEST_ESP)})"))
    }

    @Test
    fun suback0x87BlocksPublishAndReturnsFailure() = runTest {
        val service = fake(esp())
        service.simulatedSubackReasonCode = 0x87

        assertTrue(service.descobrirEsps().isFailure)
        assertTrue(service.obterConfig(TEST_ESP).isFailure)
        assertTrue(service.gravarConfig(set(mappings = emptyList())).isFailure)
        assertTrue(service.sentManagement.isEmpty())
    }

    @Test
    fun autoModeFallsBackToMqtt311AndRunsAllManagementFlows() = runTest {
        val service = FakeMqttService(
            initialState = MqttConnectionState.DISCONNECTED,
            protocolMode = MqttProtocolMode.AUTO
        ).apply {
            brokerProtocol = MqttProtocol.V3_1_1
            addVirtualEsp(esp(revision = 4L))
        }

        service.negotiatedProtocol.test {
            assertNull(awaitItem())
            service.connect()
            assertEquals(MqttProtocol.V3_1_1, awaitItem())
            assertEquals(MqttCapabilities.NONE, service.capabilities.value)
            assertFalse(service.capabilities.value.reasonCodes)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, service.descobrirEsps().getOrThrow().size)
        assertIs<MqttOutcome.Ok<ConfigState>>(service.obterConfig(TEST_ESP).getOrThrow())
        val setOut = service.gravarConfig(set()).getOrThrow()
        assertIs<MqttOutcome.Ok<ConfigResult>>(setOut)
        assertEquals(5L, setOut.value.configRevision)
        assertTrue(setOut.diagnostics.isEmpty())
    }

    @Test
    fun strictV5ModeFailsWithoutSilentFallbackWhenBrokerIs311() = runTest {
        val service = FakeMqttService(
            initialState = MqttConnectionState.DISCONNECTED,
            protocolMode = MqttProtocolMode.V5_0
        ).apply {
            brokerProtocol = MqttProtocol.V3_1_1
        }

        service.connect()

        assertEquals(MqttConnectionState.ERROR, service.connectionState.value)
        assertNull(service.negotiatedProtocol.value)
        assertEquals(MqttCapabilities.NONE, service.capabilities.value)
    }

    @Test
    fun internalEspEncodersRoundTripThroughParsersWithoutNulls() {
        val virtual = esp(revision = 4L, bootId = "9f1a2b3c")
        val online = virtual.status()
        val state = virtual.state(lastRequestId = rid1)
        val okResult = ConfigResult(
            schemaVersion = EspContract.SCHEMA_VERSION,
            espId = TEST_ESP,
            bootId = "9f1a2b3c",
            requestId = rid1,
            status = ConfigResultStatus.OK,
            configRevision = 5L
        )
        val errResult = ConfigResult(
            schemaVersion = EspContract.SCHEMA_VERSION,
            espId = TEST_ESP,
            bootId = "9f1a2b3c",
            requestId = rid1,
            status = ConfigResultStatus.ERRO,
            errorCode = EspErrorCode.REVISION_MISMATCH,
            configRevision = 4L,
            message = "revisão esperada difere"
        )

        val onlineJson = EspContract.encodeStatusOnline(online)
        val stateJson = EspContract.encodeConfigState(state)
        val okJson = EspContract.encodeConfigResult(okResult)
        val errJson = EspContract.encodeConfigResult(errResult)

        assertFalse(onlineJson.contains("null"), onlineJson)
        assertFalse(okJson.contains("null"), okJson)
        assertEquals(online, EspContract.parseStatusOnline(onlineJson, TEST_ESP).valid())
        assertEquals(state, EspContract.parseConfigState(stateJson, TEST_ESP).valid())
        assertEquals(okResult, EspContract.parseConfigResult(okJson, TEST_ESP).valid())
        assertEquals(errResult, EspContract.parseConfigResult(errJson, TEST_ESP).valid())
    }
}
