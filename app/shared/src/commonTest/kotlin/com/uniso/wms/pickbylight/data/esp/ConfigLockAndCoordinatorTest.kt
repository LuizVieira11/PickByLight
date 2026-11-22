package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.EspManagementTimings
import com.uniso.wms.pickbylight.data.mqtt.FakeMqttService
import com.uniso.wms.pickbylight.data.mqtt.MqttConnectionState
import com.uniso.wms.pickbylight.data.mqtt.MqttOutcome
import com.uniso.wms.pickbylight.data.mqtt.VirtualEsp
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Etapa 5a (e, f, g): testes do [ConfigLock] bidirecional e do [EspConfigCoordinator]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigLockAndCoordinatorTest {

    private val esp1 = "esp-a1b2c3"
    private val esp2 = "esp-000001"
    private val rid1 = "123e4567-e89b-12d3-a456-426614174000"
    private val rid2 = "223e4567-e89b-12d3-a456-426614174000"
    private val rid3 = "323e4567-e89b-12d3-a456-426614174000"
    private val rid4 = "423e4567-e89b-12d3-a456-426614174000"
    private val rid5 = "523e4567-e89b-12d3-a456-426614174000"
    private val channels = listOf(
        EspChannel("CH01", 4, true),
        EspChannel("CH02", 13, true),
        EspChannel("CH03", 16, true)
    )
    private val map1 = EspMapping("POS_A1_01", MappingKind.POSICAO, "CH03")

    private fun set(espId: String = esp1, revision: Long = 4L, requestId: String = rid1) =
        ConfigSet(EspContract.SCHEMA_VERSION, espId, requestId, expectedRevision = revision, mappings = listOf(map1))

    @Test
    fun configLockBlocksColetaDuringConfigBlocksConfigDuringColetaAndBlocksSecondConfig() {
        val lock = ConfigLock()

        assertIs<ConfigLockResult.Acquired>(lock.tryStartConfig(esp1))
        assertTrue(lock.state.value.isConfigActive)
        assertFalse(lock.tryStartColeta(), "config em andamento deve bloquear início de coleta")
        val secondConfig = assertIs<ConfigLockResult.Blocked>(lock.tryStartConfig(esp2))
        assertTrue(secondConfig.reason.contains("outra configuração"))

        lock.endConfig(esp1)
        assertFalse(lock.state.value.isConfigActive)

        assertTrue(lock.tryStartColeta())
        assertTrue(lock.state.value.isColetaActive)
        val blockedByColeta = assertIs<ConfigLockResult.Blocked>(lock.tryStartConfig(esp1))
        assertEquals("coleta ativa", blockedByColeta.reason)

        lock.endColeta()
        assertFalse(lock.state.value.isColetaActive)
        assertIs<ConfigLockResult.Acquired>(lock.tryStartConfig(esp1))
        lock.endConfig(esp1)
    }

    @Test
    fun coordinatorReleasesLockOnOkErroTimeoutDisconnectAndCoroutineCancellation() = runTest {
        val lock = ConfigLock()
        val mqtt = FakeMqttService()
        val virtual = VirtualEsp(esp1, channels, listOf(map1), "1111aaaa", configRevision = 4L)
        mqtt.addVirtualEsp(virtual)
        val coordinator = EspConfigCoordinator(mqtt, lock)

        assertIs<MqttOutcome.Ok<ConfigResult>>(coordinator.gravarConfig(set(revision = 4L, requestId = rid1)).getOrThrow())
        assertFalse(lock.state.value.isConfigActive)

        val errOut = assertIs<MqttOutcome.Erro>(coordinator.gravarConfig(set(revision = 3L, requestId = rid2)).getOrThrow())
        assertEquals(EspErrorCode.REVISION_MISMATCH, errOut.errorCode)
        assertFalse(lock.state.value.isConfigActive)

        mqtt.silentEspIds.add(esp1)
        val timeoutJob = async { coordinator.gravarConfig(set(revision = 5L, requestId = rid3)) }
        runCurrent()
        assertEquals(esp1, lock.state.value.configuringEspId)
        assertFalse(lock.tryStartColeta())
        advanceTimeBy(EspManagementTimings.CONFIG_TIMEOUT_MS + 10L)
        assertIs<MqttOutcome.Timeout>(timeoutJob.await().getOrThrow())
        assertNull(lock.state.value.configuringEspId)
        mqtt.silentEspIds.remove(esp1)

        mqtt.setConnectionState(MqttConnectionState.DISCONNECTED)
        assertTrue(coordinator.gravarConfig(set(revision = 5L, requestId = rid4)).isFailure)
        assertFalse(lock.state.value.isConfigActive)
        mqtt.setConnectionState(MqttConnectionState.CONNECTED)

        mqtt.silentEspIds.add(esp1)
        val cancelJob = async { coordinator.gravarConfig(set(revision = 5L, requestId = rid5)) }
        runCurrent()
        assertTrue(lock.state.value.isConfigActive)
        cancelJob.cancel()
        runCurrent()
        assertFalse(lock.state.value.isConfigActive)
    }

    @Test
    fun coordinatorWithActiveColetaFailsWithoutPublishing() = runTest {
        val lock = ConfigLock()
        val mqtt = FakeMqttService()
        val coordinator = EspConfigCoordinator(mqtt, lock)

        assertTrue(lock.tryStartColeta())
        val res = coordinator.gravarConfig(set())

        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("coleta ativa") == true)
        assertTrue(mqtt.sentManagement.isEmpty())
        lock.endColeta()
    }
}
