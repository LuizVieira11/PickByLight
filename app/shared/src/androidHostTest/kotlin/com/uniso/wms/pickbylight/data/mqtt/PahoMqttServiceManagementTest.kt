package com.uniso.wms.pickbylight.data.mqtt

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
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import com.uniso.wms.pickbylight.data.mqtt.model.OfflineReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Etapa 4b2: testes da API de gerenciamento v1 e correlação ACK/PONG em [PahoMqttServiceImpl]. */
@OptIn(ExperimentalCoroutinesApi::class)
class PahoMqttServiceManagementTest {

    private val otherEsp = "esp-000001"
    private val rid1 = "123e4567-e89b-12d3-a456-426614174000"
    private val rid2 = "223e4567-e89b-12d3-a456-426614174000"
    private val rid3 = "323e4567-e89b-12d3-a456-426614174000"
    private val channels = listOf(
        EspChannel("CH01", 4, true),
        EspChannel("CH02", 13, true),
        EspChannel("CH03", 16, true)
    )
    private val map1 = EspMapping("POS_A1_01", MappingKind.POSICAO, "CH03")

    private fun esp(id: String = TEST_ESP, revision: Long = 4L, bootId: String = "1111aaaa") =
        VirtualEsp(id, channels, listOf(map1), bootId, revision)

    private fun set(revision: Long = 4L, requestId: String = rid1, mappings: List<EspMapping> = listOf(map1)) =
        ConfigSet(EspContract.SCHEMA_VERSION, TEST_ESP, requestId, expectedRevision = revision, mappings = mappings)

    private suspend fun TestScope.connectedService(
        protocol: MqttProtocol = MqttProtocol.V5_0,
        mode: MqttProtocolMode = MqttProtocolMode.V5_0,
        configure: FakeMqttTransport.() -> Unit = {}
    ): Pair<PahoMqttServiceImpl, FakeMqttTransport> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport(protocol).apply(configure)
        val service = PahoMqttServiceImpl(
            scope = backgroundScope,
            transportFactory = { _ -> transport },
            clientIdProvider = { "wms_mobile_test" },
            protocolModeProvider = { mode },
            ioDispatcher = dispatcher
        )
        service.connect("tcp://10.0.0.10:1883")
        return service to transport
    }

    @Test
    fun v1SubackCompletesBeforeDiscoveryGetAndSetPublish() = runTest {
        val (service, transport) = connectedService()
        transport.events.clear()

        val discJob = async { service.descobrirEsps() }
        advanceTimeBy(EspManagementTimings.DISCOVERY_WINDOW_MS)
        discJob.await().getOrThrow()

        val subIdx = transport.events.indexOfFirst { it.startsWith("SUBACK_V1(") }
        val pubIdx = transport.events.indexOf("PUBLISH(${MqttTopics.V1_DISCOVERY_REQUEST})")
        assertTrue(subIdx in 0 until pubIdx, "SUBACK v1 deve preceder PUBLISH: ${transport.events}")
        // Expectativa atualizada (Etapa 5e, spec 9.1 e 6.11): filtros v1 incluem V1_ESP_CONFIG_RESTORE_FILTER assinado na conexão.
        assertEquals(
            setOf(
                MqttTopics.V1_ESP_CONFIG_RESTORE_FILTER,
                MqttTopics.V1_ESP_STATUS_FILTER,
                MqttTopics.V1_ESP_CONFIG_STATE_FILTER,
                MqttTopics.V1_ESP_CONFIG_RESULT_FILTER
            ),
            transport.subscriptions.filter { it.topicFilter.startsWith("wms/pickbylight/v1/") }.map { it.topicFilter }.toSet()
        )
    }

    @Test
    fun v1SubackFailureAbortsDiscoveryWithoutPublishing() = runTest {
        val (service, transport) = connectedService {
            v1SubackCodesOverride = mapOf(
                MqttTopics.V1_ESP_STATUS_FILTER to 0x87,
                MqttTopics.V1_ESP_CONFIG_STATE_FILTER to 0,
                MqttTopics.V1_ESP_CONFIG_RESULT_FILTER to 0
            )
        }
        transport.publishes.clear()

        val res = service.descobrirEsps()

        assertTrue(res.isFailure)
        assertTrue(transport.publishes.isEmpty(), "falha no SUBACK v1 impede publicar discovery")
    }

    @Test
    fun discoveryPublishesValidPayloadWith3sExpiryHandlesRetainedStaleAndLiveOnlineAfterWindow() = runTest {
        val v1 = esp(TEST_ESP, revision = 4L)
        val v2 = esp(otherEsp, revision = 1L)
        val (service, transport) = connectedService {
            onSubscribe = { t, subs ->
                if (subs.any { it.topicFilter == MqttTopics.V1_ESP_STATUS_FILTER }) {
                    t.deliverMessage(MqttTopics.espStatus(TEST_ESP), EspContract.encodeStatusOnline(v1.status()), retained = true)
                    t.deliverMessage(MqttTopics.espStatus(otherEsp), EspContract.encodeStatusOnline(v2.status()), retained = true)
                }
            }
            onPublish = { t, rec ->
                if (rec.topic == MqttTopics.V1_DISCOVERY_REQUEST) {
                    t.deliverMessage(MqttTopics.espStatus(TEST_ESP), EspContract.encodeStatusOnline(v1.status()), retained = false)
                }
            }
        }
        transport.publishes.clear()

        val start = currentTime
        val devices = service.descobrirEsps().getOrThrow()
        val elapsed = currentTime - start

        assertEquals(EspManagementTimings.DISCOVERY_WINDOW_MS, elapsed)
        val discPub = transport.publishes.single { it.topic == MqttTopics.V1_DISCOVERY_REQUEST }
        assertFalse(discPub.retained)
        assertEquals(3L, discPub.properties?.messageExpirySeconds)
        assertTrue(discPub.properties?.payloadFormatUtf8 == true)
        assertEquals("application/json", discPub.properties?.contentType)
        EspContract.parseDiscoveryRequest(discPub.payload).valid()

        val byId = devices.associateBy { it.espId }
        assertEquals(EspPresence.ONLINE, byId[TEST_ESP]?.presence)
        assertEquals(EspPresence.STALE, byId[otherEsp]?.presence)
    }

    @Test
    fun obterConfigCompletesOnlyOnLiveStateWithMatchingRequestIdAndTimesOutIn5s() = runTest {
        val virtual = esp(TEST_ESP, revision = 4L)
        val (service, transport) = connectedService {
            onPublish = { t, rec ->
                if (rec.topic == MqttTopics.espConfigGet(TEST_ESP)) {
                    val sent = EspContract.parseConfigGet(rec.payload, TEST_ESP).valid()
                    t.deliverMessage(
                        MqttTopics.espConfigState(TEST_ESP),
                        EspContract.encodeConfigState(virtual.state(sent.requestId)),
                        retained = true
                    )
                    t.deliverMessage(
                        MqttTopics.espConfigState(TEST_ESP),
                        EspContract.encodeConfigState(virtual.state(sent.requestId)),
                        retained = false
                    )
                }
            }
        }

        val okOut = assertIs<MqttOutcome.Ok<ConfigState>>(service.obterConfig(TEST_ESP).getOrThrow())
        assertEquals(4L, okOut.value.configRevision)
        assertEquals(EspPresence.ONLINE, service.espDevices.value.single().presence)

        transport.onPublish = { t, rec ->
            if (rec.topic == MqttTopics.espConfigGet(TEST_ESP)) {
                val sent = EspContract.parseConfigGet(rec.payload, TEST_ESP).valid()
                t.deliverMessage(
                    MqttTopics.espConfigState(TEST_ESP),
                    EspContract.encodeConfigState(virtual.state(sent.requestId)),
                    retained = true
                )
            }
        }
        val start = currentTime
        assertIs<MqttOutcome.Timeout>(service.obterConfig(TEST_ESP).getOrThrow())
        assertEquals(EspManagementTimings.CONFIG_TIMEOUT_MS, currentTime - start)
    }

    @Test
    fun gravarConfigSetsV5ResponseTopicAndCorrelationDataOmitsInV3AndReturnsOkOrRevisionMismatch() = runTest {
        val virtual = esp(TEST_ESP, revision = 4L)
        val (v5Service, v5Transport) = connectedService(MqttProtocol.V5_0, MqttProtocolMode.V5_0) {
            onPublish = { t, rec ->
                if (rec.topic == MqttTopics.espConfigSet(TEST_ESP)) {
                    val res = virtual.handleConfigSet(rid1, rec.payload)
                    t.deliverMessage(MqttTopics.espConfigResult(TEST_ESP), EspContract.encodeConfigResult(res))
                }
            }
        }
        v5Transport.publishes.clear()
        val okOut = assertIs<MqttOutcome.Ok<ConfigResult>>(v5Service.gravarConfig(set(revision = 4L)).getOrThrow())
        assertEquals(5L, okOut.value.configRevision)
        val v5Pub = v5Transport.publishes.single()
        assertEquals(5L, v5Pub.properties?.messageExpirySeconds)
        assertEquals(MqttTopics.espConfigResult(TEST_ESP), v5Pub.properties?.responseTopic)
        assertEquals(rid1, v5Pub.properties?.correlationData?.let { String(it, Charsets.UTF_8) })

        v5Transport.onPublish = { t, rec ->
            if (rec.topic == MqttTopics.espConfigSet(TEST_ESP)) {
                val res = virtual.handleConfigSet(rid2, rec.payload)
                t.deliverMessage(MqttTopics.espConfigResult(TEST_ESP), EspContract.encodeConfigResult(res))
            }
        }
        val errOut = assertIs<MqttOutcome.Erro>(v5Service.gravarConfig(set(revision = 3L, requestId = rid2)).getOrThrow())
        assertEquals(EspErrorCode.REVISION_MISMATCH, errOut.errorCode)

        val (v3Service, v3Transport) = connectedService(MqttProtocol.V3_1_1, MqttProtocolMode.V3_1_1) {
            onPublish = { t, rec ->
                if (rec.topic == MqttTopics.espConfigSet(TEST_ESP)) {
                    val res = virtual.handleConfigSet(rid3, rec.payload)
                    t.deliverMessage(MqttTopics.espConfigResult(TEST_ESP), EspContract.encodeConfigResult(res))
                }
            }
        }
        v3Transport.publishes.clear()
        assertIs<MqttOutcome.Ok<ConfigResult>>(v3Service.gravarConfig(set(revision = 5L, requestId = rid3)).getOrThrow())
        val v3Pub = v3Transport.publishes.single()
        assertNull(v3Pub.properties?.responseTopic)
        assertNull(v3Pub.properties?.correlationData)
    }

    @Test
    fun gravarConfigUsesPayloadRequestIdWhenCorrelationDataDiverges() = runTest {
        val virtual = esp(TEST_ESP, revision = 4L)
        val (service, _) = connectedService {
            onPublish = { t, rec ->
                if (rec.topic == MqttTopics.espConfigSet(TEST_ESP)) {
                    val res = virtual.handleConfigSet(rid1, rec.payload)
                    t.deliverMessage(
                        topic = MqttTopics.espConfigResult(TEST_ESP),
                        json = EspContract.encodeConfigResult(res),
                        correlationData = rid2.toByteArray(Charsets.UTF_8)
                    )
                }
            }
        }

        val out = assertIs<MqttOutcome.Ok<ConfigResult>>(service.gravarConfig(set(revision = 4L)).getOrThrow())
        assertEquals(rid1, out.value.requestId)
        assertEquals(5L, out.value.configRevision)
    }

    @Test
    fun gravarConfigWhenDisconnectedFailsWithoutQueueing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport(MqttProtocol.V5_0)
        val service = PahoMqttServiceImpl(backgroundScope, { _ -> transport }, { "wms_mobile_test" }, { MqttProtocolMode.V5_0 }, dispatcher)

        val res = service.gravarConfig(set())

        assertTrue(res.isFailure)
        assertEquals(0, service.pendingCommandsCount.value)
        assertTrue(transport.publishes.isEmpty())
    }

    @Test
    fun connectionLostDuringPendingConfigSetFailsImmediately() = runTest {
        val (service, _) = connectedService {
            onPublish = { t, rec ->
                if (rec.topic == MqttTopics.espConfigSet(TEST_ESP)) {
                    t.isConnected = false
                    t.registeredCallbacks?.onConnectionLost(IllegalStateException("queda simulada"))
                }
            }
        }

        val res = service.gravarConfig(set())

        assertTrue(res.isFailure)
        assertEquals(0, service.pendingCommandsCount.value)
    }

    @Test
    fun duplicateResultAndUnknownRequestIdAreDiscardedWithoutSideEffects() = runTest {
        val virtual = esp(TEST_ESP, revision = 4L)
        val (service, _) = connectedService {
            onPublish = { t, rec ->
                if (rec.topic == MqttTopics.espConfigSet(TEST_ESP)) {
                    val unknown = ConfigResult(
                        EspContract.SCHEMA_VERSION, TEST_ESP, virtual.bootId, rid2,
                        ConfigResultStatus.ERRO, EspErrorCode.INTERNAL_ERROR, 4L
                    )
                    t.deliverMessage(MqttTopics.espConfigResult(TEST_ESP), EspContract.encodeConfigResult(unknown))

                    val ok = virtual.handleConfigSet(rid1, rec.payload)
                    val okJson = EspContract.encodeConfigResult(ok)
                    t.deliverMessage(MqttTopics.espConfigResult(TEST_ESP), okJson)
                    t.deliverMessage(MqttTopics.espConfigResult(TEST_ESP), okJson)
                }
            }
        }

        val out = assertIs<MqttOutcome.Ok<ConfigResult>>(service.gravarConfig(set(revision = 4L)).getOrThrow())
        assertEquals(5L, out.value.configRevision)
        assertTrue(service.discardedObsoleteMessages.any { it.contains("unknown_request=$rid2") })
        assertTrue(service.discardedObsoleteMessages.any { it.contains("duplicate_request=$rid1") })
    }

    @Test
    fun retiredBootIdAndLowerRevisionMessagesAreDiscarded() = runTest {
        val virtual = esp(TEST_ESP, revision = 5L, bootId = "1111aaaa")
        val (service, transport) = connectedService()

        transport.deliverMessage(MqttTopics.espStatus(TEST_ESP), EspContract.encodeStatusOnline(virtual.status()))
        transport.deliverMessage(MqttTopics.espConfigState(TEST_ESP), EspContract.encodeConfigState(virtual.state()))
        assertEquals(5L, service.espDevices.value.single().config?.configRevision)

        val oldRevState = virtual.state().copy(configRevision = 2L)
        transport.deliverMessage(MqttTopics.espConfigState(TEST_ESP), EspContract.encodeConfigState(oldRevState))
        assertEquals(5L, service.espDevices.value.single().config?.configRevision)
        assertTrue(service.discardedObsoleteMessages.any { it.contains("old_revision=2<5") })

        virtual.reboot("2222bbbb")
        transport.deliverMessage(MqttTopics.espStatus(TEST_ESP), EspContract.encodeStatusOnline(virtual.status()))
        val retiredStatus = virtual.status().copy(bootId = "1111aaaa", configRevision = 1L)
        transport.deliverMessage(MqttTopics.espStatus(TEST_ESP), EspContract.encodeStatusOnline(retiredStatus))

        assertEquals("2222bbbb", service.espDevices.value.single().status?.bootId)
        assertTrue(service.discardedObsoleteMessages.any { it.contains("retired_boot=1111aaaa") })
    }

    @Test
    fun offlineStatusMarksOfflineAndReconnectDemotesOnlineToStaleAndResubscribesV1() = runTest {
        val virtual = esp(TEST_ESP, revision = 4L)
        val (service, transport) = connectedService {
            onPublish = { t, rec ->
                when (rec.topic) {
                    MqttTopics.V1_DISCOVERY_REQUEST -> {
                        t.deliverMessage(MqttTopics.espStatus(TEST_ESP), EspContract.encodeStatusOnline(virtual.status()))
                    }
                    MqttTopics.COMANDO -> {
                        if (rec.payload.contains("PING")) t.deliverAck("""{"acao":"PONG","status":"OK"}""")
                    }
                }
            }
        }

        service.descobrirEsps().getOrThrow()
        assertEquals(EspPresence.ONLINE, service.espDevices.value.single().presence)

        transport.deliverMessage(
            MqttTopics.espStatus(TEST_ESP),
            EspContract.encodeStatusOffline(virtual.offlineStatus(OfflineReason.LWT))
        )
        assertEquals(EspPresence.OFFLINE, service.espDevices.value.single().presence)

        // Volta a ONLINE e reconecta com reassinatura v1 recusada (Ajuste 2):
        // rebaixa ONLINE -> STALE, conexão permanece CONNECTED, comando legado funciona e próximo discovery reassina.
        transport.deliverMessage(MqttTopics.espStatus(TEST_ESP), EspContract.encodeStatusOnline(virtual.status()))
        assertEquals(EspPresence.ONLINE, service.espDevices.value.single().presence)

        transport.v1SubackCodesOverride = mapOf(
            MqttTopics.V1_ESP_STATUS_FILTER to 0x87,
            MqttTopics.V1_ESP_CONFIG_STATE_FILTER to 0,
            MqttTopics.V1_ESP_CONFIG_RESULT_FILTER to 0
        )
        service.connect("tcp://10.0.0.10:1883")

        assertEquals(MqttConnectionState.CONNECTED, service.connectionState.value)
        assertEquals(EspPresence.STALE, service.espDevices.value.single().presence)
        assertTrue(service.enviarPing().isSuccess, "fluxo legado continua funcionando mesmo sem SUBACK v1")

        transport.v1SubackCodesOverride = null
        transport.events.clear()
        service.descobrirEsps().getOrThrow()
        assertTrue(transport.events.any { it.startsWith("SUBACK_V1(") })
        assertEquals(EspPresence.ONLINE, service.espDevices.value.single().presence)
    }

    @Test
    fun invalidV1PayloadOrMismatchedTopicEspIdIsDiscardedWithoutCrash() = runTest {
        val virtual = esp(TEST_ESP, revision = 4L)
        val (service, transport) = connectedService()

        transport.deliverMessage(MqttTopics.espStatus(TEST_ESP), "{json_quebrado")
        transport.deliverMessage(
            MqttTopics.espStatus(TEST_ESP),
            EspContract.encodeStatusOnline(virtual.status().copy(espId = otherEsp))
        )
        transport.deliverMessage(
            MqttTopics.espConfigState(TEST_ESP),
            EspContract.encodeConfigState(virtual.state().copy(espId = otherEsp))
        )
        transport.deliverMessage("wms/pickbylight/v1/topico/desconhecido", "{}")

        assertTrue(service.espDevices.value.isEmpty())
    }

    @Test
    fun testarLedComConfirmacaoCorrelatesWithAndWithoutPositionAndDoesNotCrossWithPing() = runTest {
        val (service, transport) = connectedService {
            onPublish = { t, rec ->
                if (rec.topic == MqttTopics.COMANDO) {
                    if (rec.payload.contains("POS_A1_01")) {
                        // ACK síncrono no onPublish com posição (Ajuste 3).
                        t.deliverAck("""{"acao":"ACK","posicao_led_id":"POS_A1_01","status":"OK"}""")
                    } else if (rec.payload.contains("EST_B")) {
                        // ACK sem posicao_led_id deve casar com KEY_TESTE_LED_NO_POS.
                        t.deliverAck("""{"acao":"ACK","status":"OK"}""")
                    } else if (rec.payload.contains("POS_ERR")) {
                        t.deliverAck("""{"acao":"ACK","posicao_led_id":"POS_ERR","status":"ERRO","error_code":"UNKNOWN_CHANNEL"}""")
                    }
                }
            }
        }

        assertIs<MqttOutcome.Ok<Unit>>(service.testarLedComConfirmacao("EST_A", "PRAT_1", "POS_A1_01", "AZUL", 3000).getOrThrow())
        assertIs<MqttOutcome.Ok<Unit>>(service.testarLedComConfirmacao("EST_B", null, null, "VERDE", 2000).getOrThrow())

        val errOut = assertIs<MqttOutcome.Erro>(service.testarLedComConfirmacao("EST_A", "PRAT_1", "POS_ERR").getOrThrow())
        assertEquals(EspErrorCode.UNKNOWN_CHANNEL, errOut.errorCode)

        val start = currentTime
        assertIs<MqttOutcome.Timeout>(service.testarLedComConfirmacao("EST_A", "PRAT_1", "POS_SILENT").getOrThrow())
        assertEquals(EspManagementTimings.ACK_TIMEOUT_MS, currentTime - start)

        // ACK comum não completa enviarPing, e PONG não completa testarLedComConfirmacao.
        transport.onPublish = { t, rec ->
            if (rec.topic == MqttTopics.COMANDO) {
                if (rec.payload.contains("PING")) {
                    t.deliverAck("""{"acao":"ACK","status":"OK"}""")
                } else if (rec.payload.contains("EST_CROSS")) {
                    t.deliverAck("""{"acao":"PONG","status":"OK"}""")
                }
            }
        }

        val pingJob = async { service.enviarPing() }
        advanceTimeBy(4_000L)
        assertFalse(pingJob.isCompleted, "ACK comum não pode concluir PING")
        transport.deliverAck("""{"acao":"PONG","status":"OK"}""")
        assertTrue(pingJob.await().isSuccess)

        val ledJob = async { service.testarLedComConfirmacao("EST_CROSS", null, null) }
        advanceTimeBy(4_000L)
        assertFalse(ledJob.isCompleted, "PONG não pode concluir testarLedComConfirmacao")
        transport.deliverAck("""{"acao":"ACK","status":"OK"}""")
        assertIs<MqttOutcome.Ok<Unit>>(ledJob.await().getOrThrow())
    }

    @Test
    fun gravarConfigSurfacesPuback0x10AndFailsWithoutTimeoutOn0x87Or0x95() = runTest {
        val virtual = esp(TEST_ESP, revision = 4L)
        val (service, transport) = connectedService(MqttProtocol.V5_0, MqttProtocolMode.V5_0) {
            publishReasonCodeByTopic[MqttTopics.espConfigSet(TEST_ESP)] = 0x10
            onPublish = { t, rec ->
                if (rec.topic == MqttTopics.espConfigSet(TEST_ESP)) {
                    val res = virtual.handleConfigSet(rid1, rec.payload)
                    t.deliverMessage(MqttTopics.espConfigResult(TEST_ESP), EspContract.encodeConfigResult(res))
                }
            }
        }

        val out0x10 = assertIs<MqttOutcome.Ok<ConfigResult>>(service.gravarConfig(set(revision = 4L)).getOrThrow())
        assertEquals(0x10, out0x10.diagnostics.single().code)

        for (rc in listOf(0x87, 0x95)) {
            transport.publishReasonCodeByTopic[MqttTopics.espConfigSet(TEST_ESP)] = rc
            transport.onPublish = null
            val start = currentTime
            val failRes = service.gravarConfig(set(revision = 5L, requestId = rid2))
            assertTrue(failRes.isFailure, "PUBACK 0x${rc.toString(16)} deve falhar")
            assertEquals(0L, currentTime - start, "PUBACK >= 0x80 falha imediato sem esperar 5s")
        }
    }

    @Test
    fun v1OperationsRejectInvalidEspIdOrOver14MappingsWithoutPublishing() = runTest {
        val (service, transport) = connectedService()
        transport.publishes.clear()

        val badId = service.obterConfig("ESP_INVALIDO")
        assertTrue(badId.isFailure)

        val tooMany = (1..15).map { idx ->
            EspMapping("POS_$idx", MappingKind.POSICAO, "CH${idx.toString().padStart(2, '0')}")
        }
        val over14 = service.gravarConfig(set(mappings = tooMany))
        assertTrue(over14.isFailure)
        assertTrue(transport.publishes.isEmpty())
        advanceUntilIdle()
    }
}
