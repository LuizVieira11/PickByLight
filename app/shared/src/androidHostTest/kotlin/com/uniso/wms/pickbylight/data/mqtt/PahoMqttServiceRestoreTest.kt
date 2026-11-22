package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.esp.ConfigLock
import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.esp.EspInventoryCodec
import com.uniso.wms.pickbylight.data.esp.TEST_ESP
import com.uniso.wms.pickbylight.data.esp.TEST_RID
import com.uniso.wms.pickbylight.data.esp.valid
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigRestore
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResultStatus
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import com.uniso.wms.pickbylight.data.mqtt.model.OfflineReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Etapa 5e (a-g): testes de `config/restore` no [PahoMqttServiceImpl]. */
@OptIn(ExperimentalCoroutinesApi::class)
class PahoMqttServiceRestoreTest {

    private val esp1Id = TEST_ESP
    private val channels = listOf(
        EspChannel("CH01", 4, true),
        EspChannel("CH02", 13, true),
        EspChannel("CH03", 16, true)
    )
    private val map1 = EspMapping("POS_A1_01", MappingKind.POSICAO, "CH03")
    private val map2 = EspMapping("EST_A_LED1", MappingKind.ESTANTE, "CH01")

    private fun esp(id: String = esp1Id, revision: Long = 5L, bootId: String = "1111aaaa", mappings: List<EspMapping> = listOf(map1)) =
        VirtualEsp(id, channels, mappings, bootId, revision)

    private suspend fun TestScope.connectedService(
        loader: (suspend () -> String?)? = null,
        saver: (suspend (String) -> Unit)? = null,
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
            inventorySaveDebounceMs = 500L
        )
        service.connect("tcp://10.0.0.10:1883")
        runCurrent()
        return service to transport
    }

    @Test
    fun pahoSubscribesConfigRestoreOnEveryConnectAndReconnectWithoutOpeningEspScreen() = runTest {
        val (service, transport) = connectedService {
            restoreSubackCodeOverride = 0x87
        }

        assertEquals(MqttConnectionState.CONNECTED, service.connectionState.value)
        assertTrue(
            transport.subscriptions.any {
                it.topicFilter == MqttTopics.V1_ESP_CONFIG_RESTORE_FILTER && it.qos == 1 && it.noLocal
            }
        )

        transport.subscriptions.clear()
        transport.restoreSubackCodeOverride = null
        service.connect("tcp://10.0.0.10:1883")
        runCurrent()
        assertEquals(
            1,
            transport.subscriptions.count {
                it.topicFilter == MqttTopics.V1_ESP_CONFIG_RESTORE_FILTER && it.qos == 1 && it.noLocal
            }
        )
    }

    @Test
    fun pahoRestoreRespondsWithKnownMappingsOrEmptyForUnknownAndForgottenEsp() = runTest {
        val v1 = esp(esp1Id, revision = 5L, bootId = "1111aaaa", mappings = listOf(map1, map2))
        val (service, transport) = connectedService()

        // 1. ESP desconhecido -> mappings=[], expected_revision=0
        val unknownRestore = ConfigRestore(EspContract.SCHEMA_VERSION, "esp-000001", "9999aaaa", TEST_RID)
        transport.deliverMessage(MqttTopics.espConfigRestore("esp-000001"), EspContract.encodeConfigRestore(unknownRestore))
        runCurrent()
        val unknownPub = transport.publishes.single { it.topic == MqttTopics.espConfigSet("esp-000001") }
        val unknownSet = EspContract.parseConfigSet(unknownPub.payload, "esp-000001").valid()
        assertEquals(0L, unknownSet.expectedRevision)
        assertTrue(unknownSet.mappings.isEmpty())

        // 2. ESP conhecido -> restaura mappings conhecidos, expected_revision=0
        transport.deliverMessage(MqttTopics.espStatus(esp1Id), EspContract.encodeStatusOnline(v1.status()), retained = false)
        transport.deliverMessage(MqttTopics.espConfigState(esp1Id), EspContract.encodeConfigState(v1.state()), retained = false)
        transport.publishes.clear()

        val knownRestore = v1.rebootForRestore("2222bbbb", TEST_RID)
        transport.deliverMessage(MqttTopics.espConfigRestore(esp1Id), EspContract.encodeConfigRestore(knownRestore))
        runCurrent()
        val knownPub = transport.publishes.single { it.topic == MqttTopics.espConfigSet(esp1Id) }
        assertFalse(knownPub.retained)
        assertEquals(5L, knownPub.properties?.messageExpirySeconds)
        assertEquals(MqttTopics.espConfigResult(esp1Id), knownPub.properties?.responseTopic)
        val knownSet = EspContract.parseConfigSet(knownPub.payload, esp1Id, activeRevision = 0L, channels = channels).valid()
        assertEquals(0L, knownSet.expectedRevision)
        assertEquals(listOf(map1, map2), knownSet.mappings)

        // 3. ESP esquecido -> volta a responder com mappings=[]
        transport.deliverMessage(MqttTopics.espStatus(esp1Id), EspContract.encodeStatusOffline(v1.offlineStatus(OfflineReason.LWT)))
        service.esquecerEsp(esp1Id).getOrThrow()
        transport.publishes.clear()

        val forgottenRestore = v1.rebootForRestore("3333cccc", TEST_RID)
        transport.deliverMessage(MqttTopics.espConfigRestore(esp1Id), EspContract.encodeConfigRestore(forgottenRestore))
        runCurrent()
        val forgottenPub = transport.publishes.single { it.topic == MqttTopics.espConfigSet(esp1Id) }
        val forgottenSet = EspContract.parseConfigSet(forgottenPub.payload, esp1Id).valid()
        assertEquals(0L, forgottenSet.expectedRevision)
        assertTrue(forgottenSet.mappings.isEmpty())
    }

    @Test
    fun pahoRestartedAppRestoresFromPersistedInventorySnapshot() = runTest {
        val v1 = esp(esp1Id, revision = 6L, bootId = "1111aaaa", mappings = listOf(map1, map2))
        val savedJson = EspInventoryCodec.encode(listOf(EspDevice(esp1Id, EspPresence.ONLINE, v1.status(), v1.state())))
        val (_, transport) = connectedService(loader = { savedJson })
        transport.publishes.clear()

        val restoreReq = v1.rebootForRestore("2222bbbb", TEST_RID)
        transport.deliverMessage(MqttTopics.espConfigRestore(esp1Id), EspContract.encodeConfigRestore(restoreReq))
        runCurrent()

        val setPub = transport.publishes.single { it.topic == MqttTopics.espConfigSet(esp1Id) }
        val set = EspContract.parseConfigSet(setPub.payload, esp1Id).valid()
        assertEquals(0L, set.expectedRevision)
        assertEquals(listOf(map1, map2), set.mappings)
    }

    @Test
    fun pahoRestoreRespondsEvenWhenColetaIsActiveInConfigLock() = runTest {
        val lock = ConfigLock()
        assertTrue(lock.tryStartColeta())
        val v1 = esp(esp1Id, revision = 5L, bootId = "1111aaaa", mappings = listOf(map1))
        val (_, transport) = connectedService()
        transport.deliverMessage(MqttTopics.espStatus(esp1Id), EspContract.encodeStatusOnline(v1.status()), retained = false)
        transport.deliverMessage(MqttTopics.espConfigState(esp1Id), EspContract.encodeConfigState(v1.state()), retained = false)
        transport.publishes.clear()

        val restoreReq = v1.rebootForRestore("2222bbbb", TEST_RID)
        transport.deliverMessage(MqttTopics.espConfigRestore(esp1Id), EspContract.encodeConfigRestore(restoreReq))
        runCurrent()

        assertTrue(lock.state.value.isColetaActive)
        val setPub = transport.publishes.single { it.topic == MqttTopics.espConfigSet(esp1Id) }
        assertEquals(listOf(map1), EspContract.parseConfigSet(setPub.payload, esp1Id).valid().mappings)
        lock.endColeta()
    }

    @Test
    fun pahoStatusWithNewBootIdAndRevisionZeroPreservesConfigAndAcceptsBootNewStateWhileRejectingSameBootRegression() = runTest {
        val v1 = esp(esp1Id, revision = 5L, bootId = "1111aaaa", mappings = listOf(map1))
        val (service, transport) = connectedService()

        transport.deliverMessage(MqttTopics.espStatus(esp1Id), EspContract.encodeStatusOnline(v1.status()), retained = false)
        transport.deliverMessage(MqttTopics.espConfigState(esp1Id), EspContract.encodeConfigState(v1.state()), retained = false)
        assertEquals(5L, service.espDevices.value.single().config?.configRevision)

        // Reboot com boot_id novo e revisão 0: status ONLINE NÃO apaga o config conhecido antes do restore
        v1.rebootForRestore("2222bbbb", TEST_RID)
        transport.deliverMessage(MqttTopics.espStatus(esp1Id), EspContract.encodeStatusOnline(v1.status()), retained = false)
        assertEquals(listOf(map1), service.espDevices.value.single().config?.mappings)

        // Após restore aplicado, config/state do boot NOVO com revisão 1 é ACEITO mesmo com config anterior em rev 5
        val newBootStateRev1 = v1.state().copy(bootId = "2222bbbb", configRevision = 1L, mappings = listOf(map1))
        transport.deliverMessage(MqttTopics.espConfigState(esp1Id), EspContract.encodeConfigState(newBootStateRev1), retained = false)
        assertEquals(1L, service.espDevices.value.single().config?.configRevision)
        assertEquals("2222bbbb", service.espDevices.value.single().config?.bootId)

        // Dentro do MESMO boot ("2222bbbb"), revisão menor (0 < 1) continua descartada como old_revision
        val sameBootOldRev0 = newBootStateRev1.copy(configRevision = 0L, mappings = emptyList())
        transport.deliverMessage(MqttTopics.espConfigState(esp1Id), EspContract.encodeConfigState(sameBootOldRev0), retained = false)
        assertEquals(1L, service.espDevices.value.single().config?.configRevision)
        assertEquals(listOf(map1), service.espDevices.value.single().config?.mappings)
        assertTrue(service.discardedObsoleteMessages.any { it.contains("old_revision=0<1") })
    }

    @Test
    fun pahoInvalidConfigRestoreIsIgnoredWithoutPublishing() = runTest {
        val (service, transport) = connectedService()
        transport.publishes.clear()

        // esp_id do payload diferente do tópico + JSON malformado + campo obrigatório ausente
        val mismatched = ConfigRestore(EspContract.SCHEMA_VERSION, "esp-000001", "1111aaaa", TEST_RID)
        transport.deliverMessage(MqttTopics.espConfigRestore(esp1Id), EspContract.encodeConfigRestore(mismatched))
        transport.deliverMessage(MqttTopics.espConfigRestore(esp1Id), "{json_quebrado")
        transport.deliverMessage(MqttTopics.espConfigRestore(esp1Id), """{"schema_version":1,"esp_id":"$esp1Id"}""")
        runCurrent()

        assertTrue(transport.publishes.isEmpty())
        assertTrue(service.espDevices.value.isEmpty())
    }

    @Test
    fun pahoRestoreRevisionMismatchResultFromSecondDeviceIsHandledSilently() = runTest {
        val v1 = esp(esp1Id, revision = 5L, bootId = "1111aaaa", mappings = listOf(map1))
        val (service, transport) = connectedService()
        transport.deliverMessage(MqttTopics.espStatus(esp1Id), EspContract.encodeStatusOnline(v1.status()), retained = false)
        transport.deliverMessage(MqttTopics.espConfigState(esp1Id), EspContract.encodeConfigState(v1.state()), retained = false)
        transport.publishes.clear()

        val restoreReq = v1.rebootForRestore("2222bbbb", TEST_RID)
        transport.deliverMessage(MqttTopics.espConfigRestore(esp1Id), EspContract.encodeConfigRestore(restoreReq))
        runCurrent()

        val setPub = transport.publishes.single { it.topic == MqttTopics.espConfigSet(esp1Id) }
        val sentSet = EspContract.parseConfigSet(setPub.payload, esp1Id).valid()

        // Outro aparelho aplicou primeiro -> ESP responde REVISION_MISMATCH para este aparelho
        val mismatchResult = ConfigResult(
            schemaVersion = EspContract.SCHEMA_VERSION,
            espId = esp1Id,
            bootId = "2222bbbb",
            requestId = sentSet.requestId,
            status = ConfigResultStatus.ERRO,
            errorCode = EspErrorCode.REVISION_MISMATCH,
            configRevision = 1L
        )
        transport.deliverMessage(MqttTopics.espConfigResult(esp1Id), EspContract.encodeConfigResult(mismatchResult))
        runCurrent()

        assertFalse(
            service.discardedObsoleteMessages.any { it.contains("unknown_request=${sentSet.requestId}") },
            "resultado do restore deve ser consumido explicitamente sem sujar descartes"
        )
    }
}
