package com.uniso.wms.pickbylight

import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoAcender
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoApagar
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoPing
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoTesteLed
import com.uniso.wms.pickbylight.data.mqtt.model.MqttStatusApp
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Golden tests do contrato legado (MQTT5_E_ESP32.md 6.9). Strings idênticas ao JSON emitido antes da Etapa 2.
 * A configuração Json é a mesma de PahoMqttServiceImpl/FakeMqttService (encodeDefaults = true; explicitNulls padrão,
 * logo os `null` aparecem). Não altere estas strings sem atualizar ESPECIFICACAO.md e o firmware.
 */
class MqttLegacyContractGoldenTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun acenderJsonIsByteIdentical() {
        val payload = MqttComandoAcender(
            estanteLedId = "EST_A_LED1",
            prateleiraLedId = "PRAT_A1_LED1",
            posicaoLedId = "POS_A1_01"
        )
        assertEquals(
            """{"acao":"ACENDER","estante_led_id":"EST_A_LED1","prateleira_led_id":"PRAT_A1_LED1","posicao_led_id":"POS_A1_01","cor":"VERDE"}""",
            json.encodeToString(payload)
        )
    }

    @Test
    fun apagarJsonIsByteIdentical() {
        val payload = MqttComandoApagar(
            estanteLedId = "EST_A_LED1",
            prateleiraLedId = "PRAT_A1_LED1",
            posicaoLedId = "POS_A1_01"
        )
        assertEquals(
            """{"acao":"APAGAR","estante_led_id":"EST_A_LED1","prateleira_led_id":"PRAT_A1_LED1","posicao_led_id":"POS_A1_01"}""",
            json.encodeToString(payload)
        )
    }

    @Test
    fun pingJsonIsByteIdentical() {
        assertEquals("""{"acao":"PING"}""", json.encodeToString(MqttComandoPing()))
    }

    @Test
    fun testeLedJsonIsByteIdenticalIncludingExplicitNulls() {
        val full = MqttComandoTesteLed(
            estanteLedId = "EST_A_LED1",
            prateleiraLedId = "PRAT_A1_LED1",
            posicaoLedId = "POS_A1_01"
        )
        assertEquals(
            """{"acao":"TESTE_LED","estante_led_id":"EST_A_LED1","prateleira_led_id":"PRAT_A1_LED1","posicao_led_id":"POS_A1_01","cor":"AZUL","duracao_ms":3000}""",
            json.encodeToString(full)
        )
        // Os null explícitos de hoje (prateleira/posição ausentes) permanecem na saída.
        val onlyShelf = MqttComandoTesteLed(estanteLedId = "EST_A_LED1")
        assertEquals(
            """{"acao":"TESTE_LED","estante_led_id":"EST_A_LED1","prateleira_led_id":null,"posicao_led_id":null,"cor":"AZUL","duracao_ms":3000}""",
            json.encodeToString(onlyShelf)
        )
    }

    @Test
    fun appLwtJsonIsByteIdentical() {
        assertEquals(
            """{"client":"wms_mobile","status":"OFFLINE"}""",
            json.encodeToString(MqttStatusApp(client = "wms_mobile", status = "OFFLINE"))
        )
        assertEquals(
            """{"client":"wms_mobile","status":"ONLINE"}""",
            json.encodeToString(MqttStatusApp(client = "wms_mobile", status = "ONLINE"))
        )
    }

    @Test
    fun legacyTopicsAreUnchanged() {
        assertEquals("wms/pickbylight/comando", MqttTopics.COMANDO)
        assertEquals("wms/pickbylight/ack", MqttTopics.ACK)
        assertEquals("wms/pickbylight/status/app", MqttTopics.STATUS_APP)
    }

    @Test
    fun v1TopicsFollowTheContract() {
        assertEquals("wms/pickbylight/v1/discovery/request", MqttTopics.V1_DISCOVERY_REQUEST)
        assertEquals("wms/pickbylight/v1/esp/esp-a1b2c3/status", MqttTopics.espStatus("esp-a1b2c3"))
        assertEquals("wms/pickbylight/v1/esp/esp-a1b2c3/config/get", MqttTopics.espConfigGet("esp-a1b2c3"))
        assertEquals("wms/pickbylight/v1/esp/esp-a1b2c3/config/state", MqttTopics.espConfigState("esp-a1b2c3"))
        assertEquals("wms/pickbylight/v1/esp/esp-a1b2c3/config/set", MqttTopics.espConfigSet("esp-a1b2c3"))
        assertEquals("wms/pickbylight/v1/esp/esp-a1b2c3/config/result", MqttTopics.espConfigResult("esp-a1b2c3"))
        assertEquals("wms/pickbylight/v1/esp/+/status", MqttTopics.V1_ESP_STATUS_FILTER)
        assertEquals("wms/pickbylight/v1/esp/+/config/state", MqttTopics.V1_ESP_CONFIG_STATE_FILTER)
        assertEquals("wms/pickbylight/v1/esp/+/config/result", MqttTopics.V1_ESP_CONFIG_RESULT_FILTER)
    }

    @Test
    fun espIdFromTopicExtractsOnlyValidTopics() {
        assertEquals("esp-a1b2c3", MqttTopics.espIdFromTopic("wms/pickbylight/v1/esp/esp-a1b2c3/config/result"))
        assertEquals("esp-a1b2c3", MqttTopics.espIdFromTopic("wms/pickbylight/v1/esp/esp-a1b2c3/status"))
        assertNull(MqttTopics.espIdFromTopic("wms/pickbylight/v1/esp/esp-A1B2C3/status"))
        assertNull(MqttTopics.espIdFromTopic("wms/pickbylight/v1/esp/esp-a1b2c3/outro"))
        assertNull(MqttTopics.espIdFromTopic("wms/pickbylight/comando"))
    }
}
