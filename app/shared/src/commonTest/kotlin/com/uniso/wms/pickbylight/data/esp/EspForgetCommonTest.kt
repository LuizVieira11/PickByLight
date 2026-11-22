package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.EspManagementTimings
import com.uniso.wms.pickbylight.data.mqtt.EspPresence
import com.uniso.wms.pickbylight.data.mqtt.EspRegistry
import com.uniso.wms.pickbylight.data.mqtt.FakeMqttService
import com.uniso.wms.pickbylight.data.mqtt.MqttOutcome
import com.uniso.wms.pickbylight.data.mqtt.VirtualEsp
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import com.uniso.wms.pickbylight.data.mqtt.model.OfflineReason
import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.ui.screens.esps.EspManagementViewModel
import com.uniso.wms.pickbylight.util.FeedbackController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Etapa 5d (a, g, h, i): testes em commonTest para EspRegistry.forget, Fake, Coordinator e EspManagementViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class EspForgetCommonTest {

    private val esp1Id = TEST_ESP
    private val channels = listOf(
        EspChannel("CH01", 4, true),
        EspChannel("CH02", 13, true),
        EspChannel("CH03", 16, true)
    )
    private val oldMap = EspMapping("POS_A1_01", MappingKind.POSICAO, "CH03")
    private val newMap = EspMapping("EST_A_LED1", MappingKind.ESTANTE, "CH01")

    @Test
    fun registryForgetClearsDeviceAndFiltersSoNewBootWithRevisionZeroIsAcceptedAsOnlineWithoutOldConfig() {
        val registry = EspRegistry()
        val oldEsp = VirtualEsp(esp1Id, channels, listOf(oldMap), bootId = "1111aaaa", configRevision = 9L)
        val midEsp = VirtualEsp(esp1Id, channels, listOf(oldMap), bootId = "2222bbbb", configRevision = 10L)

        registry.applyStatusOnline(oldEsp.status(), fromRetained = false)
        registry.applyStatusOnline(midEsp.status(), fromRetained = false)
        registry.applyConfigState(midEsp.state(), fromRetained = false)
        assertEquals(10L, registry.espDevices.value.single().config?.configRevision)

        assertTrue(registry.forget(esp1Id))
        assertTrue(registry.espDevices.value.isEmpty())
        assertNull(registry.getDevice(esp1Id))

        val freshEsp = VirtualEsp(esp1Id, channels, emptyList(), bootId = "3333cccc", configRevision = 0L)
        assertTrue(registry.applyStatusOnline(freshEsp.status(), fromRetained = false))
        val dev = registry.espDevices.value.single()
        assertEquals(EspPresence.ONLINE, dev.presence)
        assertEquals(0L, dev.status?.configRevision)
        assertNull(dev.config)
    }

    @Test
    fun fakeEsquecerEspEnforcesPreconditionsClearsRetainedAndRediscoversCurrentVirtualEspState() = runTest {
        val fake = FakeMqttService()
        val v1 = VirtualEsp(esp1Id, channels, listOf(oldMap), bootId = "1111aaaa", configRevision = 5L)
        fake.addVirtualEsp(v1, publishRetained = true)
        fake.descobrirEsps().getOrThrow()

        assertTrue(fake.esquecerEsp(esp1Id).isFailure)

        fake.simulateIncomingOffline(EspContract.encodeStatusOffline(v1.offlineStatus(OfflineReason.LWT)), esp1Id)
        fake.esquecerEsp(esp1Id).getOrThrow()

        assertTrue(fake.espDevices.value.isEmpty())
        assertTrue(fake.sentManagement.any { it.first == MqttTopics.espStatus(esp1Id) && it.second.isEmpty() })
        assertTrue(fake.sentManagement.any { it.first == MqttTopics.espConfigState(esp1Id) && it.second.isEmpty() })

        v1.reboot("4444dddd")
        v1.handleConfigSet(
            TEST_RID,
            EspContract.encodeConfigSet(
                ConfigSet(
                    schemaVersion = EspContract.SCHEMA_VERSION,
                    espId = esp1Id,
                    requestId = TEST_RID,
                    expectedRevision = 5L,
                    mappings = listOf(newMap)
                )
            ).valid()
        )
        fake.descobrirEsps().getOrThrow()
        val rediscovered = fake.espDevices.value.single()
        assertEquals(EspPresence.ONLINE, rediscovered.presence)
        assertEquals("4444dddd", rediscovered.status?.bootId)
        assertEquals(6L, rediscovered.status?.configRevision)
        assertNull(rediscovered.config, "status do discovery não traz config antiga")

        val state = (fake.obterConfig(esp1Id).getOrThrow() as MqttOutcome.Ok).value
        assertEquals(listOf(newMap), state.mappings)
    }

    @Test
    fun coordinatorEsquecerEspBlocksDuringConfigOrActiveColetaAndReleasesLockAfterwards() = runTest {
        val lock = ConfigLock()
        val fake = FakeMqttService()
        val v1 = VirtualEsp(esp1Id, channels, listOf(oldMap), bootId = "1111aaaa", configRevision = 4L)
        fake.seedRetainedStatus(v1.status())
        fake.subscribeV1Topics().getOrThrow()
        val coordinator = EspConfigCoordinator(fake, lock)

        assertTrue(lock.tryStartColeta())
        assertTrue(coordinator.esquecerEsp(esp1Id).isFailure)
        assertTrue(fake.sentManagement.isEmpty())
        lock.endColeta()

        assertTrue(lock.tryStartConfig("esp-000001") is ConfigLockResult.Acquired)
        assertTrue(coordinator.esquecerEsp(esp1Id).isFailure)
        assertTrue(fake.sentManagement.isEmpty())
        lock.endConfig("esp-000001")

        fake.silentEspIds.add(esp1Id)
        assertTrue(coordinator.esquecerEsp(esp1Id).isSuccess)
        assertFalse(lock.state.value.isConfigActive)
        assertTrue(fake.espDevices.value.isEmpty())
    }

    @Test
    fun viewModelForgetButtonEnabledOnlyForOfflineOrStaleShowsOnlineReasonNavigatesOnSuccessAndReportsFailure() = runTest {
        val repo = InMemoryWmsRepository().apply { resetarDadosDemo() }
        val fake = FakeMqttService()
        val lock = ConfigLock()
        val feedback = FeedbackController()
        val v1 = VirtualEsp(esp1Id, channels, listOf(oldMap), bootId = "1111aaaa", configRevision = 4L)
        fake.seedRetainedStatus(v1.status())
        fake.subscribeV1Topics().getOrThrow()

        val vm = EspManagementViewModel(
            mqttService = fake,
            repository = repo,
            feedbackController = feedback,
            coordinator = EspConfigCoordinator(fake, lock),
            configLock = lock,
            coroutineScope = backgroundScope,
            isSupported = true
        )

        fake.silentEspIds.add(esp1Id)
        vm.abrirDetalhe(esp1Id)
        advanceTimeBy(EspManagementTimings.CONFIG_TIMEOUT_MS + 20L)
        runCurrent()
        assertTrue(vm.uiState.value.canForgetEsp, "STALE e conectado deve habilitar Esquecer ESP")

        fake.simulateIncomingStatus(EspContract.encodeStatusOnline(v1.status()), esp1Id, retained = false)
        runCurrent()
        assertFalse(vm.uiState.value.canForgetEsp)
        assertEquals("Desligue o ESP para esquecê-lo: ele está ONLINE", vm.uiState.value.forgetDisabledReason)

        fake.simulateIncomingOffline(EspContract.encodeStatusOffline(v1.offlineStatus(OfflineReason.LWT)), esp1Id)
        runCurrent()
        assertTrue(vm.uiState.value.canForgetEsp)
        fake.simulatedPubackReasonCodes[MqttTopics.espConfigState(esp1Id)] = FakeMqttService.REASON_NOT_AUTHORIZED
        vm.esquecerEspAtual()
        runCurrent()
        assertFalse(vm.uiState.value.espForgotten)
        assertTrue(vm.uiState.value.forgetErrorMessage?.contains("0x87") == true)
        assertEquals(1, fake.espDevices.value.size)

        fake.simulatedPubackReasonCodes.remove(MqttTopics.espConfigState(esp1Id))
        vm.esquecerEspAtual()
        runCurrent()
        assertTrue(vm.uiState.value.espForgotten)
        assertTrue(fake.espDevices.value.isEmpty())
    }
}
