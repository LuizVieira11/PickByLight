package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PahoMqttServiceTransportTest {

    @Test
    fun connectConfiguresLwtAnonymousCleanSessionSubscribesAckAndPublishesOnline() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport()
        val service = PahoMqttServiceImpl(
            scope = backgroundScope,
            transportFactory = { _ -> transport },
            clientIdProvider = { "WmsMobile_TEST" },
            protocolModeProvider = { MqttProtocolMode.V3_1_1 },
            ioDispatcher = dispatcher
        )

        service.connect("tcp://10.0.0.10:1883")

        val cfg = transport.connectConfigs.single()
        assertEquals("tcp://10.0.0.10:1883", cfg.brokerUrl)
        assertEquals("WmsMobile_TEST", cfg.clientId)
        assertTrue(cfg.cleanSession)
        assertEquals(5, cfg.connectionTimeoutSeconds)
        assertEquals(15, cfg.keepAliveSeconds)
        assertNull(cfg.username)
        assertNull(cfg.password)

        val will = assertNotNull(cfg.will)
        assertEquals("wms/pickbylight/status/app", will.topic)
        assertEquals("""{"client":"wms_mobile","status":"OFFLINE"}""", String(will.payload, Charsets.UTF_8))
        assertEquals(1, will.qos)
        assertTrue(will.retained)
        assertEquals(5, will.willDelaySeconds)

        // Expectativa atualizada (Etapa 5e, spec 9.1 e 6.11): ACK + config/restore assinados em toda conexão.
        assertEquals(
            listOf(
                MqttSubscription("wms/pickbylight/ack", qos = 1, noLocal = true),
                MqttSubscription("wms/pickbylight/v1/esp/+/config/restore", qos = 1, noLocal = true)
            ),
            transport.subscriptions
        )
        val onlinePub = transport.publishes.single()
        assertEquals("wms/pickbylight/status/app", onlinePub.topic)
        assertEquals("""{"client":"wms_mobile","status":"ONLINE"}""", onlinePub.payload)
        assertEquals(1, onlinePub.qos)
        assertTrue(onlinePub.retained)
        assertEquals(MqttConnectionState.CONNECTED, service.connectionState.value)
    }

    @Test
    fun publishesFourLegacyCommandsWithExactTopicQosRetainAndGoldenJson() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport().apply {
            onPublish = { t, rec ->
                if (rec.topic == "wms/pickbylight/comando") {
                    when {
                        rec.payload.contains("ACENDER") -> t.deliverAck("""{"acao":"ACK","posicao_led_id":"POS_A2_05","status":"OK"}""")
                        rec.payload.contains("PING") -> t.deliverAck("""{"acao":"PONG","status":"OK"}""")
                    }
                }
            }
        }
        val service = PahoMqttServiceImpl(backgroundScope, { _ -> transport }, { "WmsMobile_TEST" }, { MqttProtocolMode.V3_1_1 }, dispatcher)
        service.connect("tcp://10.0.0.10:1883")
        transport.publishes.clear()

        assertTrue(service.acenderLed("EST_A_LED1", "PRAT_A2_LED1", "POS_A2_05", "VERDE").isSuccess)
        assertTrue(service.apagarLed("EST_A_LED1", "PRAT_A2_LED1", "POS_A2_05").isSuccess)
        assertTrue(service.enviarPing().isSuccess)
        assertTrue(service.testarLed("EST_A_LED1", null, null, "AZUL", 3000).isSuccess)

        assertEquals(4, transport.publishes.size)
        transport.publishes.forEach {
            assertEquals("wms/pickbylight/comando", it.topic)
            assertEquals(1, it.qos)
            assertFalse(it.retained)
        }
        assertEquals(
            listOf(
                """{"acao":"ACENDER","estante_led_id":"EST_A_LED1","prateleira_led_id":"PRAT_A2_LED1","posicao_led_id":"POS_A2_05","cor":"VERDE"}""",
                """{"acao":"APAGAR","estante_led_id":"EST_A_LED1","prateleira_led_id":"PRAT_A2_LED1","posicao_led_id":"POS_A2_05"}""",
                """{"acao":"PING"}""",
                """{"acao":"TESTE_LED","estante_led_id":"EST_A_LED1","prateleira_led_id":null,"posicao_led_id":null,"cor":"AZUL","duracao_ms":3000}"""
            ),
            transport.publishes.map { it.payload }
        )
    }

    @Test
    fun queuesWhenDisconnectedAndRetriesUpToMaxThreeAttempts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport()
        val service = PahoMqttServiceImpl(backgroundScope, { _ -> transport }, { "WmsMobile_TEST" }, { MqttProtocolMode.V3_1_1 }, dispatcher)

        val offlineRes = service.apagarLed("EST_A_LED1", "PRAT_A1_LED1", "POS_A1_01")
        assertTrue(offlineRes.isFailure)
        assertEquals(1, service.pendingCommandsCount.value)

        // Falha nas 3 tentativas seguintes de envio de comando: após esgotar 3, próxima reconexão descarta.
        transport.remainingFailuresOnCommand = 5
        repeat(3) {
            service.connect("tcp://10.0.0.10:1883")
            advanceUntilIdle()
            assertEquals(1, service.pendingCommandsCount.value)
        }

        // Na 4ª reconexão, tentativas já == 3: remove sem consumir nova tentativa de publish.
        service.connect("tcp://10.0.0.10:1883")
        advanceUntilIdle()
        assertEquals(0, service.pendingCommandsCount.value)
        assertEquals(2, transport.remainingFailuresOnCommand, "máximo 3 tentativas de envio")

        service.disconnect()
        advanceUntilIdle()
        transport.remainingFailuresOnCommand = 0
        assertTrue(service.apagarLed("EST_A_LED1", "PRAT_A1_LED1", "POS_A1_01").isFailure)
        assertEquals(1, service.pendingCommandsCount.value)

        service.connect("tcp://10.0.0.10:1883")
        advanceUntilIdle()
        assertEquals(0, service.pendingCommandsCount.value)
        assertTrue(transport.publishes.any { it.topic == "wms/pickbylight/comando" && it.payload.contains("APAGAR") })
    }

    @Test
    fun resolvesPendingAckAndRespects5sTimeout() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport()
        val service = PahoMqttServiceImpl(backgroundScope, { _ -> transport }, { "WmsMobile_TEST" }, { MqttProtocolMode.V3_1_1 }, dispatcher)
        service.connect("tcp://10.0.0.10:1883")

        transport.onPublish = { t, rec ->
            if (rec.topic == "wms/pickbylight/comando") {
                t.deliverAck("""{"acao":"ACK","posicao_led_id":"POS_A1_01","status":"ERRO"}""")
            }
        }
        assertTrue(service.acenderLed("EST_A", "PRAT_A1", "POS_A1_01").isFailure)

        transport.onPublish = null
        val start = currentTime
        val timeoutRes = service.acenderLed("EST_A", "PRAT_A1", "POS_A1_01")
        assertTrue(timeoutRes.isFailure)
        assertTrue(currentTime - start >= 5_000L)
    }

    @Test
    fun reconnectBackoffStopsAtErrorAfter5AttemptsAndPreservesTiming() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport().apply { failNextConnect = true }
        val service = PahoMqttServiceImpl(
            scope = this,
            transportFactory = { _ -> transport },
            clientIdProvider = { "WmsMobile_TEST" },
            protocolModeProvider = { MqttProtocolMode.AUTO },
            ioDispatcher = dispatcher
        )

        val start = currentTime
        service.connect("tcp://10.0.0.10:1883")
        advanceUntilIdle()
        val elapsed = currentTime - start

        assertEquals(MqttConnectionState.ERROR, service.connectionState.value)
        // 1 inicial + 5 tentativas de backoff = 6 chamadas connect; 1s + 2s + 4s + 8s + 16s = 31s virtuais.
        assertEquals(6, transport.connectConfigs.size)
        assertEquals(31_000L, elapsed)
    }

    // ---- Novos testes da Etapa 4b1 (a-k) ----

    @Test
    fun autoModeWithV5SuccessUsesOnlyV5AndExposesCapabilities() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val requestedProtocols = mutableListOf<MqttProtocol>()
        val v5Transport = FakeMqttTransport(MqttProtocol.V5_0)
        val service = PahoMqttServiceImpl(
            scope = backgroundScope,
            transportFactory = { proto ->
                requestedProtocols.add(proto)
                v5Transport
            },
            clientIdProvider = { "wms_mobile_test" },
            protocolModeProvider = { MqttProtocolMode.AUTO },
            ioDispatcher = dispatcher
        )

        service.connect("tcp://10.0.0.10:1883")

        assertEquals(listOf(MqttProtocol.V5_0), requestedProtocols)
        assertEquals(MqttProtocol.V5_0, service.negotiatedProtocol.value)
        assertEquals(MqttCapabilities.forProtocol(MqttProtocol.V5_0), service.capabilities.value)
    }

    @Test
    fun autoModeWithV5ProtocolErrorClosesV5AndFallsBackToV3() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val requestedProtocols = mutableListOf<MqttProtocol>()
        val v5Transport = FakeMqttTransport(MqttProtocol.V5_0).apply {
            failConnectWith = MqttUnsupportedProtocolException(0x84, "Unsupported Protocol Version")
        }
        val v3Transport = FakeMqttTransport(MqttProtocol.V3_1_1)
        val service = PahoMqttServiceImpl(
            scope = backgroundScope,
            transportFactory = { proto ->
                requestedProtocols.add(proto)
                if (proto == MqttProtocol.V5_0) v5Transport else v3Transport
            },
            clientIdProvider = { "wms_mobile_test" },
            protocolModeProvider = { MqttProtocolMode.AUTO },
            ioDispatcher = dispatcher
        )

        service.connect("tcp://10.0.0.10:1883")

        assertEquals(listOf(MqttProtocol.V5_0, MqttProtocol.V3_1_1), requestedProtocols)
        assertTrue(v5Transport.closedCount >= 1, "transporte v5 deve ser fechado antes do fallback")
        assertNull(v5Transport.registeredCallbacks)
        assertEquals(MqttConnectionState.CONNECTED, service.connectionState.value)
        assertEquals(MqttProtocol.V3_1_1, service.negotiatedProtocol.value)
        assertEquals(MqttCapabilities.NONE, service.capabilities.value)
    }

    @Test
    fun autoModeWithV5NetworkErrorDoesNotFallbackAndRunsBackoff() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val requestedProtocols = mutableListOf<MqttProtocol>()
        val v5Transport = FakeMqttTransport(MqttProtocol.V5_0).apply { failNextConnect = true }
        val service = PahoMqttServiceImpl(
            scope = this,
            transportFactory = { proto ->
                requestedProtocols.add(proto)
                v5Transport
            },
            clientIdProvider = { "wms_mobile_test" },
            protocolModeProvider = { MqttProtocolMode.AUTO },
            ioDispatcher = dispatcher
        )

        service.connect("tcp://10.0.0.10:1883")
        advanceUntilIdle()

        assertTrue(requestedProtocols.all { it == MqttProtocol.V5_0 }, "erro de rede não cai para 3.1.1")
        assertEquals(6, requestedProtocols.size)
        assertEquals(MqttConnectionState.ERROR, service.connectionState.value)
    }

    @Test
    fun strictV5ModeWithProtocolErrorDoesNotFallbackToV3() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val requestedProtocols = mutableListOf<MqttProtocol>()
        val v5Transport = FakeMqttTransport(MqttProtocol.V5_0).apply {
            failConnectWith = MqttUnsupportedProtocolException(0x84, "Unsupported Protocol Version")
        }
        val service = PahoMqttServiceImpl(
            scope = backgroundScope,
            transportFactory = { proto ->
                requestedProtocols.add(proto)
                v5Transport
            },
            clientIdProvider = { "wms_mobile_test" },
            protocolModeProvider = { MqttProtocolMode.V5_0 },
            ioDispatcher = dispatcher
        )

        service.connect("tcp://10.0.0.10:1883")

        assertEquals(listOf(MqttProtocol.V5_0), requestedProtocols)
        assertFalse(service.connectionState.value == MqttConnectionState.CONNECTED)
        assertNull(service.negotiatedProtocol.value)
        assertEquals(MqttCapabilities.NONE, service.capabilities.value)
    }

    @Test
    fun strictV3ModeUsesOnlyV3Transport() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val requestedProtocols = mutableListOf<MqttProtocol>()
        val v3Transport = FakeMqttTransport(MqttProtocol.V3_1_1)
        val service = PahoMqttServiceImpl(
            scope = backgroundScope,
            transportFactory = { proto ->
                requestedProtocols.add(proto)
                v3Transport
            },
            clientIdProvider = { "wms_mobile_test" },
            protocolModeProvider = { MqttProtocolMode.V3_1_1 },
            ioDispatcher = dispatcher
        )

        service.connect("tcp://10.0.0.10:1883")

        assertEquals(listOf(MqttProtocol.V3_1_1), requestedProtocols)
        assertEquals(MqttProtocol.V3_1_1, service.negotiatedProtocol.value)
        assertEquals(MqttCapabilities.NONE, service.capabilities.value)
    }

    @Test
    fun connectUsesKeepAlive15WillDelay5AndStablePersistedClientId() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport(MqttProtocol.V5_0)
        val service = PahoMqttServiceImpl(
            scope = backgroundScope,
            transportFactory = { _ -> transport },
            protocolModeProvider = { MqttProtocolMode.V5_0 },
            ioDispatcher = dispatcher
        )

        service.connect("tcp://10.0.0.10:1883")
        service.connect("tcp://10.0.0.10:1883")

        assertEquals(2, transport.connectConfigs.size)
        val first = transport.connectConfigs[0]
        val second = transport.connectConfigs[1]
        assertEquals(15, first.keepAliveSeconds)
        assertEquals(5, first.will?.willDelaySeconds)
        assertTrue(first.clientId.startsWith("wms_mobile_"))
        assertEquals(first.clientId, second.clientId, "Client ID deve ser o mesmo entre reconexões")
    }

    @Test
    fun legacyCommandsSendExpectedV5ExpiryFormatContentTypeAndGoldenJson() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport(MqttProtocol.V5_0).apply {
            onPublish = { t, rec ->
                if (rec.topic == "wms/pickbylight/comando") {
                    when {
                        rec.payload.contains("ACENDER") -> t.deliverAck("""{"acao":"ACK","posicao_led_id":"POS_A2_05","status":"OK"}""")
                        rec.payload.contains("PING") -> t.deliverAck("""{"acao":"PONG","status":"OK"}""")
                        rec.payload.contains("TESTE_LED") -> t.deliverAck("""{"acao":"ACK","posicao_led_id":"POS_A2_05","status":"OK"}""")
                    }
                }
            }
        }
        val service = PahoMqttServiceImpl(backgroundScope, { _ -> transport }, { "wms_mobile_test" }, { MqttProtocolMode.V5_0 }, dispatcher)
        service.connect("tcp://10.0.0.10:1883")
        val onlineProps = transport.publishes.single().properties
        assertTrue(onlineProps?.payloadFormatUtf8 == true)
        assertEquals("application/json", onlineProps?.contentType)
        transport.publishes.clear()

        assertTrue(service.acenderLed("EST_A_LED1", "PRAT_A2_LED1", "POS_A2_05", "VERDE").isSuccess)
        assertTrue(service.apagarLed("EST_A_LED1", "PRAT_A2_LED1", "POS_A2_05").isSuccess)
        assertTrue(service.testarLed("EST_A_LED1", "PRAT_A2_LED1", "POS_A2_05", "AZUL", 3000).isSuccess)
        assertTrue(service.enviarPing().isSuccess)

        val expiries = transport.publishes.map { it.properties?.messageExpirySeconds }
        assertEquals(listOf(5L, 65L, 8L, 5L), expiries)
        transport.publishes.forEach { rec ->
            assertTrue(rec.properties?.payloadFormatUtf8 == true)
            assertEquals("application/json", rec.properties?.contentType)
        }
        assertEquals(
            listOf(
                """{"acao":"ACENDER","estante_led_id":"EST_A_LED1","prateleira_led_id":"PRAT_A2_LED1","posicao_led_id":"POS_A2_05","cor":"VERDE"}""",
                """{"acao":"APAGAR","estante_led_id":"EST_A_LED1","prateleira_led_id":"PRAT_A2_LED1","posicao_led_id":"POS_A2_05"}""",
                """{"acao":"TESTE_LED","estante_led_id":"EST_A_LED1","prateleira_led_id":"PRAT_A2_LED1","posicao_led_id":"POS_A2_05","cor":"AZUL","duracao_ms":3000}""",
                """{"acao":"PING"}"""
            ),
            transport.publishes.map { it.payload }
        )
    }

    @Test
    fun puback0x10SucceedsAnd0x87FailsWithoutQueueing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport(MqttProtocol.V5_0).apply {
            onPublish = { t, rec ->
                if (rec.topic == "wms/pickbylight/comando" && publishReasonCodeOverride == 0x10) {
                    t.deliverAck("""{"acao":"ACK","posicao_led_id":"POS_A1_01","status":"OK"}""")
                }
            }
        }
        val service = PahoMqttServiceImpl(backgroundScope, { _ -> transport }, { "wms_mobile_test" }, { MqttProtocolMode.V5_0 }, dispatcher)
        service.connect("tcp://10.0.0.10:1883")

        transport.publishReasonCodeOverride = 0x10
        assertTrue(service.acenderLed("EST_A", "PRAT_A1", "POS_A1_01").isSuccess)
        assertEquals(0, service.pendingCommandsCount.value)

        transport.publishReasonCodeOverride = 0x87
        val res87 = service.apagarLed("EST_A", "PRAT_A1", "POS_A1_01")
        assertTrue(res87.isFailure)
        assertEquals(0, service.pendingCommandsCount.value, "0x87 não deve entrar na fila")
    }

    @Test
    fun subackFailureCausesConnectFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport(MqttProtocol.V5_0).apply {
            subackCodesOverride = mapOf("wms/pickbylight/ack" to 0x87)
        }
        val service = PahoMqttServiceImpl(backgroundScope, { _ -> transport }, { "wms_mobile_test" }, { MqttProtocolMode.V5_0 }, dispatcher)

        service.connect("tcp://10.0.0.10:1883")

        assertFalse(service.connectionState.value == MqttConnectionState.CONNECTED)
        assertNull(service.negotiatedProtocol.value)
        assertEquals(MqttCapabilities.NONE, service.capabilities.value)
        assertTrue(transport.closedCount >= 1)
    }

    @Test
    fun disconnectClearsNegotiatedProtocolAndCapabilities() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport(MqttProtocol.V5_0)
        val service = PahoMqttServiceImpl(backgroundScope, { _ -> transport }, { "wms_mobile_test" }, { MqttProtocolMode.V5_0 }, dispatcher)

        service.connect("tcp://10.0.0.10:1883")
        assertEquals(MqttProtocol.V5_0, service.negotiatedProtocol.value)
        assertTrue(service.capabilities.value.reasonCodes)

        service.disconnect()
        advanceUntilIdle()

        assertEquals(MqttConnectionState.DISCONNECTED, service.connectionState.value)
        assertNull(service.negotiatedProtocol.value)
        assertEquals(MqttCapabilities.NONE, service.capabilities.value)
    }

    @Test
    fun persistedClientIdHasPrefixValidUuidAndRemainsStableAcrossReads() = runTest {
        val id1 = AppSettings.getOrCreateClientId()
        val id2 = AppSettings.getOrCreateClientId()

        assertTrue(id1.startsWith("wms_mobile_"), id1)
        val uuidPart = id1.removePrefix("wms_mobile_")
        assertTrue(EspContract.UUID_REGEX.matches(uuidPart), "UUID inválido: $uuidPart")
        assertEquals(id1, id2)
    }
}
