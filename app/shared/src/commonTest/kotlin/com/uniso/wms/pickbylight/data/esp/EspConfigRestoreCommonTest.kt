package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.EspPresence
import com.uniso.wms.pickbylight.data.mqtt.FakeMqttService
import com.uniso.wms.pickbylight.data.mqtt.MqttOutcome
import com.uniso.wms.pickbylight.data.mqtt.VirtualEsp
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Etapa 5e (h): validação de contrato de `config/restore` e fluxo completo no [FakeMqttService] + [VirtualEsp]. */
class EspConfigRestoreCommonTest {

    private val esp1Id = TEST_ESP
    private val channels = listOf(
        EspChannel("CH01", 4, true),
        EspChannel("CH02", 13, true),
        EspChannel("CH03", 16, true)
    )
    private val map1 = EspMapping("POS_A1_01", MappingKind.POSICAO, "CH03")

    @Test
    fun fakeAndVirtualEspRebootLosesConfigAndRestoreReappliesMappings() = runTest {
        val fake = FakeMqttService()
        val v1 = VirtualEsp(esp1Id, channels, listOf(map1), bootId = "1111aaaa", configRevision = 5L)
        fake.addVirtualEsp(v1, publishRetained = true)
        fake.subscribeV1Topics().getOrThrow()

        // Reboot sem NVS: VirtualEsp perde os mappings, zera revisão e entra em RESTORE_PENDING
        val restoreReq = v1.rebootForRestore("2222bbbb", TEST_RID)
        assertTrue(v1.mappings.isEmpty())
        assertEquals(0L, v1.configRevision)
        assertTrue(v1.restorePending)

        // Em RESTORE_PENDING, obterConfig não recebe config/state (timeout) e não sobrescreve o snapshot no app
        assertIs<MqttOutcome.Timeout>(fake.obterConfig(esp1Id).getOrThrow())
        assertEquals(listOf(map1), fake.espDevices.value.single().config?.mappings)

        // App recebe config/restore -> publica config/set com expected_revision = 0 e map1
        val restoreJson = EspContract.encodeConfigRestore(restoreReq)
        val parsedRestore = EspContract.parseConfigRestore(restoreJson, esp1Id).valid()
        assertEquals(TEST_RID, parsedRestore.requestId)
        EspContract.parseConfigRestore(restoreJson, "esp-ffffff").assertInvalid(EspErrorCode.ESP_ID_MISMATCH)

        assertTrue(fake.simulateIncomingConfigRestore(restoreJson, esp1Id))
        assertFalse(v1.restorePending)
        assertEquals(listOf(map1), v1.mappings)
        assertEquals(1L, v1.configRevision)
        val dev = fake.espDevices.value.single()
        assertEquals(EspPresence.ONLINE, dev.presence)
        assertEquals(1L, dev.config?.configRevision)
        assertTrue(fake.sentManagement.any { it.first == MqttTopics.espConfigSet(esp1Id) })
    }
}
