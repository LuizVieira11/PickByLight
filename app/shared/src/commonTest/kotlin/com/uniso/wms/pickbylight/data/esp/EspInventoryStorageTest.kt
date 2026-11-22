package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.EspDevice
import com.uniso.wms.pickbylight.data.mqtt.EspPresence
import com.uniso.wms.pickbylight.data.mqtt.EspRegistry
import com.uniso.wms.pickbylight.data.mqtt.VirtualEsp
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.data.settings.AppSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Etapa 5a (a, b, c, d): testes do codec JSON versionado do inventário de ESPs e semeadura no [EspRegistry]. */
class EspInventoryStorageTest {

    private val esp1Id = "esp-a1b2c3"
    private val esp2Id = "esp-000001"
    private val channels = listOf(
        EspChannel("CH01", 4, true),
        EspChannel("CH02", 13, true),
        EspChannel("CH03", 16, true)
    )
    private val map1 = EspMapping("POS_A1_01", MappingKind.POSICAO, "CH03")
    private val map2 = EspMapping("EST_A_LED1", MappingKind.ESTANTE, "CH01")

    private fun virtualEsp1(revision: Long = 4L, bootId: String = "1111aaaa") =
        VirtualEsp(esp1Id, channels, listOf(map1), bootId, revision)

    private fun virtualEsp2(revision: Long = 7L, bootId: String = "2222bbbb") =
        VirtualEsp(esp2Id, channels, listOf(map2), bootId, revision)

    @Test
    fun jsonRoundTripPreservesStatusAndConfigOfTwoEsps() {
        val v1 = virtualEsp1()
        val v2 = virtualEsp2()
        val devices = listOf(
            EspDevice(esp1Id, EspPresence.ONLINE, v1.status(), v1.state("123e4567-e89b-12d3-a456-426614174000")),
            EspDevice(esp2Id, EspPresence.STALE, v2.status(), v2.state())
        )

        val encoded = EspInventoryCodec.encode(devices)
        val decoded = EspInventoryCodec.decode(encoded).associateBy { it.espId }

        assertEquals(2, decoded.size)
        assertEquals(v1.status(), decoded[esp1Id]?.status)
        assertEquals(v1.state("123e4567-e89b-12d3-a456-426614174000"), decoded[esp1Id]?.config)
        assertEquals(v2.status(), decoded[esp2Id]?.status)
        assertEquals(v2.state(), decoded[esp2Id]?.config)
    }

    @Test
    fun corruptedJsonAndUnknownVersionReturnEmptyListWithoutThrowing() {
        assertTrue(EspInventoryCodec.decode(null).isEmpty())
        assertTrue(EspInventoryCodec.decode("").isEmpty())
        assertTrue(EspInventoryCodec.decode("{json_corrompido").isEmpty())
        assertTrue(EspInventoryCodec.decode("""{"v":99,"esps":[{"espId":"esp-a1b2c3"}]}""").isEmpty())
        assertTrue(EspInventoryCodec.decode("""{"v":1,"esps":[{"espId":"ESP-INVALIDO"}]}""").isEmpty())
    }

    @Test
    fun persistedEspEntersAsStaleLivePromotesToOnlineAndDoesNotOverwriteLiveEsp() {
        val v1 = virtualEsp1(revision = 4L)
        val v2Live = virtualEsp2(revision = 9L, bootId = "9999ffff")
        val v2OldPersisted = virtualEsp2(revision = 2L, bootId = "2222bbbb")

        val registry = EspRegistry()
        // v2 já visto ao vivo ANTES de aplicar o snapshot salvo em disco:
        registry.applyStatusOnline(v2Live.status(), fromRetained = false)
        registry.applyConfigState(v2Live.state(), fromRetained = false)

        val persistedJson = EspInventoryCodec.encode(
            listOf(
                EspDevice(esp1Id, EspPresence.ONLINE, v1.status(), v1.state()),
                EspDevice(esp2Id, EspPresence.ONLINE, v2OldPersisted.status(), v2OldPersisted.state())
            )
        )
        registry.seedFromPersisted(EspInventoryCodec.decode(persistedJson))

        val byId = registry.espDevices.value.associateBy { it.espId }
        // esp1 carregado do disco entra como STALE ("possivelmente antigo")
        assertEquals(EspPresence.STALE, byId[esp1Id]?.presence)
        assertEquals(4L, byId[esp1Id]?.config?.configRevision)

        // esp2 já visto ao vivo NÃO é sobrescrito pelo snapshot antigo
        assertEquals(EspPresence.ONLINE, byId[esp2Id]?.presence)
        assertEquals(9L, byId[esp2Id]?.config?.configRevision)
        assertEquals("9999ffff", byId[esp2Id]?.status?.bootId)

        // Mensagem ao vivo de esp1 promove de STALE para ONLINE
        registry.applyStatusOnline(v1.status(), fromRetained = false)
        assertEquals(EspPresence.ONLINE, registry.espDevices.value.associateBy { it.espId }[esp1Id]?.presence)
    }

    @Test
    fun resetarDadosDemoDoesNotEraseSavedEspInventory() = runTest {
        val v1 = virtualEsp1()
        val encoded = EspInventoryCodec.encode(
            listOf(EspDevice(esp1Id, EspPresence.ONLINE, v1.status(), v1.state()))
        )
        AppSettings.saveEspInventoryJson(encoded)

        val repository = InMemoryWmsRepository()
        repository.resetarDadosDemo().getOrThrow()

        val afterReset = AppSettings.getSavedEspInventoryJson()
        assertEquals(encoded, afterReset)
        val snap = EspInventoryCodec.decode(afterReset).single()
        assertEquals(esp1Id, snap.espId)
        assertEquals(v1.state(), snap.config)
    }
}
