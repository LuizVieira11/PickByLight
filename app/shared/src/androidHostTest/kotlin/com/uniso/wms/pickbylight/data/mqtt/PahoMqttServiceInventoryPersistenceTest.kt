package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.esp.EspInventoryCodec
import com.uniso.wms.pickbylight.data.esp.TEST_ESP
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import com.uniso.wms.pickbylight.data.mqtt.model.OfflineReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Etapa 5a: integração opt-in de carga e persistência debounced em [PahoMqttServiceImpl]. */
@OptIn(ExperimentalCoroutinesApi::class)
class PahoMqttServiceInventoryPersistenceTest {

    private val otherEsp = "esp-000001"
    private val channels = listOf(
        EspChannel("CH01", 4, true),
        EspChannel("CH02", 13, true),
        EspChannel("CH03", 16, true)
    )
    private val map1 = EspMapping("POS_A1_01", MappingKind.POSICAO, "CH03")

    private fun esp(id: String = TEST_ESP, revision: Long = 4L, bootId: String = "1111aaaa") =
        VirtualEsp(id, channels, listOf(map1), bootId, revision)

    @Test
    fun pahoLoadsPersistedInventoryAsStaleBeforeFirstConnectAndPromotesLiveToOnline() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val v1 = esp(TEST_ESP, revision = 4L)
        val initialJson = EspInventoryCodec.encode(
            listOf(EspDevice(TEST_ESP, EspPresence.ONLINE, v1.status(), v1.state()))
        )
        val saves = mutableListOf<String>()
        val transport = FakeMqttTransport(MqttProtocol.V5_0)
        val service = PahoMqttServiceImpl(
            scope = backgroundScope,
            transportFactory = { _ -> transport },
            clientIdProvider = { "wms_mobile_test" },
            protocolModeProvider = { MqttProtocolMode.V5_0 },
            ioDispatcher = dispatcher,
            inventoryLoader = { initialJson },
            inventorySaver = { saves.add(it) },
            inventorySaveDebounceMs = 500L
        )

        // Sem chamar connect(), o init carrega em background no ioDispatcher como STALE
        runCurrent()
        val beforeConnect = service.espDevices.value.single()
        assertEquals(TEST_ESP, beforeConnect.espId)
        assertEquals(EspPresence.STALE, beforeConnect.presence)
        assertEquals(4L, beforeConnect.config?.configRevision)

        // Nenhuma gravação redundante do estado inicial recém-carregado
        advanceTimeBy(600L)
        assertTrue(saves.isEmpty(), "não deve regravar o mesmo JSON logo ao carregar")

        service.connect("tcp://10.0.0.10:1883")
        transport.deliverMessage(MqttTopics.espStatus(TEST_ESP), EspContract.encodeStatusOnline(v1.status()), retained = false)
        assertEquals(EspPresence.ONLINE, service.espDevices.value.single().presence)
    }

    @Test
    fun pahoDebouncesBurstOfEspUpdatesIntoSingleInventorySaveAndSkipsPresenceOnlyChanges() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val v1 = esp(TEST_ESP, revision = 4L)
        val v2 = esp(otherEsp, revision = 7L)
        val saves = mutableListOf<String>()
        val transport = FakeMqttTransport(MqttProtocol.V5_0)
        val service = PahoMqttServiceImpl(
            scope = backgroundScope,
            transportFactory = { _ -> transport },
            clientIdProvider = { "wms_mobile_test" },
            protocolModeProvider = { MqttProtocolMode.V5_0 },
            ioDispatcher = dispatcher,
            inventoryLoader = { null },
            inventorySaver = { saves.add(it) },
            inventorySaveDebounceMs = 500L
        )

        service.connect("tcp://10.0.0.10:1883")
        runCurrent()

        // Rajada de 4 atualizações (status + state de 2 ESPs) dentro da janela de debounce
        transport.deliverMessage(MqttTopics.espStatus(TEST_ESP), EspContract.encodeStatusOnline(v1.status()))
        transport.deliverMessage(MqttTopics.espConfigState(TEST_ESP), EspContract.encodeConfigState(v1.state()))
        transport.deliverMessage(MqttTopics.espStatus(otherEsp), EspContract.encodeStatusOnline(v2.status()))
        transport.deliverMessage(MqttTopics.espConfigState(otherEsp), EspContract.encodeConfigState(v2.state()))
        runCurrent()
        advanceTimeBy(200L)
        assertTrue(saves.isEmpty(), "dentro do debounce de 500ms ainda não gravou")

        advanceTimeBy(350L)
        assertEquals(1, saves.size, "rajada deve consolidar em 1 única gravação")
        val decoded = EspInventoryCodec.decode(saves.single()).associateBy { it.espId }
        assertEquals(4L, decoded[TEST_ESP]?.config?.configRevision)
        assertEquals(7L, decoded[otherEsp]?.config?.configRevision)

        // Mudança apenas de presença (OFFLINE) não muda o JSON persistido -> não dispara nova escrita
        transport.deliverMessage(
            MqttTopics.espStatus(TEST_ESP),
            EspContract.encodeStatusOffline(v1.offlineStatus(OfflineReason.LWT))
        )
        runCurrent()
        advanceTimeBy(600L)
        assertEquals(1, saves.size, "transição pura de presença não deve regravar o DataStore")

        // Cenário A -> B -> A em menos que o debounce (500ms): cancela B e mantém A no disco
        val statusA = v1.status()
        val statusB = statusA.copy(rssi = -79)
        transport.deliverMessage(MqttTopics.espStatus(TEST_ESP), EspContract.encodeStatusOnline(statusB))
        runCurrent()
        advanceTimeBy(150L)
        transport.deliverMessage(MqttTopics.espStatus(TEST_ESP), EspContract.encodeStatusOnline(statusA))
        runCurrent()
        advanceTimeBy(600L)

        assertEquals(1, saves.size, "A -> B -> A dentro do debounce deve cancelar B sem nova gravação")
        assertEquals(statusA.rssi, EspInventoryCodec.decode(saves.last()).associateBy { it.espId }[TEST_ESP]?.status?.rssi)
    }
}
