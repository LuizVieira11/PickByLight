package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.esp.EspInventoryCodec
import com.uniso.wms.pickbylight.data.esp.TEST_ESP
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import com.uniso.wms.pickbylight.data.mqtt.model.OfflineReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Etapa 5d (b, c, d, e, f): testes do "Esquecer ESP" no [PahoMqttServiceImpl]. */
@OptIn(ExperimentalCoroutinesApi::class)
class PahoMqttServiceForgetTest {

    private val esp1Id = TEST_ESP
    private val esp2Id = "esp-000001"
    private val channels = listOf(
        EspChannel("CH01", 4, true),
        EspChannel("CH02", 13, true),
        EspChannel("CH03", 16, true)
    )
    private val map1 = EspMapping("POS_A1_01", MappingKind.POSICAO, "CH03")

    private fun esp(id: String = esp1Id, revision: Long = 4L, bootId: String = "1111aaaa") =
        VirtualEsp(id, channels, listOf(map1), bootId, revision)

    private suspend fun TestScope.connectedService(
        loader: (suspend () -> String?)? = null,
        saver: (suspend (String) -> Unit)? = null,
        debounceMs: Long = 500L,
        configure: FakeMqttTransport.() -> Unit = {}
    ): Pair<PahoMqttServiceImpl, FakeMqttTransport> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeMqttTransport(MqttProtocol.V5_0).apply(configure)
        val service = PahoMqttServiceImpl(
            scope = backgroundScope,
            transportFactory = { _ -> transport },
            clientIdProvider = { "wms_mobile_test" },
            protocolModeProvider = { MqttProtocolMode.V5_0 },
            ioDispatcher = dispatcher,
            inventoryLoader = loader,
            inventorySaver = saver,
            inventorySaveDebounceMs = debounceMs
        )
        service.connect("tcp://10.0.0.10:1883")
        runCurrent()
        return service to transport
    }

    @Test
    fun pahoEsquecerEspPublishesTwoEmptyRetainedMessagesBeforeRemovingLocally() = runTest {
        val v1 = esp(esp1Id)
        val steps = mutableListOf<String>()
        val (service, transport) = connectedService()
        transport.onPublish = { _, rec ->
            if (rec.topic == MqttTopics.espStatus(esp1Id) || rec.topic == MqttTopics.espConfigState(esp1Id)) {
                steps.add("PUB:${rec.topic}:present=${esp1Id in service.espDevices.value.map { it.espId }}")
            }
        }
        transport.deliverMessage(MqttTopics.espStatus(esp1Id), EspContract.encodeStatusOnline(v1.status()), retained = true)
        transport.deliverMessage(MqttTopics.espConfigState(esp1Id), EspContract.encodeConfigState(v1.state()), retained = true)
        transport.publishes.clear()

        service.esquecerEsp(esp1Id).getOrThrow()

        assertEquals(2, transport.publishes.size)
        assertEquals(MqttTopics.espStatus(esp1Id), transport.publishes[0].topic)
        assertEquals("", transport.publishes[0].payload)
        assertTrue(transport.publishes[0].retained)
        assertEquals(1, transport.publishes[0].qos)

        assertEquals(MqttTopics.espConfigState(esp1Id), transport.publishes[1].topic)
        assertEquals("", transport.publishes[1].payload)
        assertTrue(transport.publishes[1].retained)
        assertEquals(1, transport.publishes[1].qos)

        assertEquals(
            listOf(
                "PUB:${MqttTopics.espStatus(esp1Id)}:present=true",
                "PUB:${MqttTopics.espConfigState(esp1Id)}:present=true"
            ),
            steps
        )
        assertTrue(service.espDevices.value.isEmpty())
    }

    @Test
    fun pahoEsquecerEspKeepsDeviceAndInventoryWhenPublishFailsOrPuback0x87() = runTest {
        val v1 = esp(esp1Id)
        val saves = mutableListOf<String>()
        val (service, transport) = connectedService(
            loader = { null },
            saver = { saves.add(it) }
        ) {
            publishReasonCodeByTopic[MqttTopics.espConfigState(esp1Id)] = 0x87
        }
        transport.deliverMessage(MqttTopics.espStatus(esp1Id), EspContract.encodeStatusOnline(v1.status()), retained = true)
        advanceTimeBy(600L)
        assertEquals(1, saves.size)

        val res = service.esquecerEsp(esp1Id)

        assertTrue(res.isFailure)
        assertEquals(1, service.espDevices.value.size, "PUBACK 0x87 não pode remover localmente")
        assertEquals(1, saves.size, "não deve regravar inventário se limpeza falhou")
    }

    @Test
    fun pahoEsquecerEspRejectsOnlineUnknownAndDisconnectedWithoutPublishing() = runTest {
        val v1 = esp(esp1Id)
        val (service, transport) = connectedService()
        transport.publishes.clear()

        assertTrue(service.esquecerEsp(esp1Id).isFailure)
        assertTrue(transport.publishes.isEmpty())

        transport.deliverMessage(MqttTopics.espStatus(esp1Id), EspContract.encodeStatusOnline(v1.status()), retained = false)
        val onlineRes = service.esquecerEsp(esp1Id)
        assertTrue(onlineRes.isFailure)
        assertTrue(onlineRes.exceptionOrNull()?.message?.contains("Desligue") == true)
        assertTrue(transport.publishes.isEmpty())

        transport.deliverMessage(MqttTopics.espStatus(esp1Id), EspContract.encodeStatusOffline(v1.offlineStatus(OfflineReason.LWT)))
        service.disconnect()
        runCurrent()
        assertTrue(service.esquecerEsp(esp1Id).isFailure)
        assertTrue(transport.publishes.isEmpty())
    }

    @Test
    fun pahoEsquecerEspSavesInventoryImmediatelyAndSerializesAgainstInFlightDebouncedSave() = runTest {
        val v1 = esp(esp1Id)
        val v2 = esp(esp2Id)
        val saves = mutableListOf<String>()
        val firstSaveStarted = CompletableDeferred<Unit>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        var blockFirstSave = true

        val (service, transport) = connectedService(
            loader = { null },
            saver = { json ->
                if (blockFirstSave) {
                    blockFirstSave = false
                    firstSaveStarted.complete(Unit)
                    releaseFirstSave.await()
                }
                saves.add(json)
            },
            debounceMs = 500L
        )

        transport.deliverMessage(MqttTopics.espStatus(esp1Id), EspContract.encodeStatusOnline(v1.status()), retained = true)
        transport.deliverMessage(MqttTopics.espStatus(esp2Id), EspContract.encodeStatusOnline(v2.status()), retained = true)
        advanceTimeBy(550L)
        firstSaveStarted.await()

        val forgetJob = async { service.esquecerEsp(esp1Id) }
        runCurrent()

        releaseFirstSave.complete(Unit)
        forgetJob.await().getOrThrow()
        advanceTimeBy(600L)

        assertTrue(saves.size >= 2)
        val lastDecodedIds = EspInventoryCodec.decode(saves.last()).map { it.espId }
        assertEquals(listOf(esp2Id), lastDecodedIds, "última gravação deve ser sempre a sem o ESP esquecido")
    }

    @Test
    fun pahoHandleV1MessageIgnoresEmptyRetainedClearPayloadSilently() = runTest {
        val (service, transport) = connectedService()

        transport.deliverMessage(MqttTopics.espStatus(esp1Id), "", retained = false)
        transport.deliverMessage(MqttTopics.espConfigState(esp1Id), "", retained = false)
        runCurrent()

        assertTrue(service.espDevices.value.isEmpty())
    }
}
