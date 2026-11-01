package com.uniso.wms.pickbylight

import com.uniso.wms.pickbylight.data.mqtt.FakeMqttService
import com.uniso.wms.pickbylight.data.mqtt.MqttConnectionState
import com.uniso.wms.pickbylight.data.mqtt.model.MqttAck
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoAcender
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoApagar
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoPing
import com.uniso.wms.pickbylight.data.mqtt.model.MqttComandoTesteLed
import com.uniso.wms.pickbylight.data.mqtt.model.MqttStatusApp
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MqttServiceTest {

    private lateinit var mqttService: FakeMqttService
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @BeforeTest
    fun setup() {
        mqttService = FakeMqttService()
    }

    @Test
    fun shouldSerializeMqttComandoAcenderAccordingToSpecification() {
        val comando = MqttComandoAcender(
            estanteLedId = "EST_A_LED1",
            prateleiraLedId = "PRAT_A2_LED1",
            posicaoLedId = "POS_A2_05",
            cor = "VERDE"
        )
        val jsonStr = json.encodeToString(comando)

        assertTrue(jsonStr.contains("\"acao\":\"ACENDER\""))
        assertTrue(jsonStr.contains("\"estante_led_id\":\"EST_A_LED1\""))
        assertTrue(jsonStr.contains("\"prateleira_led_id\":\"PRAT_A2_LED1\""))
        assertTrue(jsonStr.contains("\"posicao_led_id\":\"POS_A2_05\""))
        assertTrue(jsonStr.contains("\"cor\":\"VERDE\""))
    }

    @Test
    fun shouldSerializeMqttComandoApagarAccordingToSpecification() {
        val comando = MqttComandoApagar(
            estanteLedId = "EST_A_LED1",
            prateleiraLedId = "PRAT_A2_LED1",
            posicaoLedId = "POS_A2_05"
        )
        val jsonStr = json.encodeToString(comando)

        assertTrue(jsonStr.contains("\"acao\":\"APAGAR\""))
        assertTrue(jsonStr.contains("\"estante_led_id\":\"EST_A_LED1\""))
        assertTrue(jsonStr.contains("\"prateleira_led_id\":\"PRAT_A2_LED1\""))
        assertTrue(jsonStr.contains("\"posicao_led_id\":\"POS_A2_05\""))
    }

    @Test
    fun shouldSerializeMqttComandoPingAndTesteLed() {
        val ping = MqttComandoPing()
        val pingStr = json.encodeToString(ping)
        assertEquals("{\"acao\":\"PING\"}", pingStr)

        val testeLed = MqttComandoTesteLed(
            estanteLedId = "EST_A_LED1",
            prateleiraLedId = "PRAT_A2_LED1",
            posicaoLedId = "POS_A2_05",
            cor = "AZUL",
            duracaoMs = 3000
        )
        val testeLedStr = json.encodeToString(testeLed)
        assertTrue(testeLedStr.contains("\"acao\":\"TESTE_LED\""))
        assertTrue(testeLedStr.contains("\"cor\":\"AZUL\""))
        assertTrue(testeLedStr.contains("\"duracao_ms\":3000"))
    }

    @Test
    fun shouldDeserializeMqttAck() {
        val jsonOk = """{"acao":"ACK","posicao_led_id":"POS_A2_05","status":"OK"}"""
        val ackOk = json.decodeFromString<MqttAck>(jsonOk)
        assertEquals("ACK", ackOk.acao)
        assertEquals("POS_A2_05", ackOk.posicaoLedId)
        assertEquals("OK", ackOk.status)
        assertTrue(ackOk.isOk)

        val jsonErro = """{"acao":"ACK","posicao_led_id":"POS_A2_05","status":"ERRO"}"""
        val ackErro = json.decodeFromString<MqttAck>(jsonErro)
        assertFalse(ackErro.isOk)
    }

    @Test
    fun shouldSerializeLwtAndOnlineStatus() {
        val lwt = MqttStatusApp(client = "wms_mobile", status = "OFFLINE")
        val lwtStr = json.encodeToString(lwt)
        assertTrue(lwtStr.contains("\"client\":\"wms_mobile\""))
        assertTrue(lwtStr.contains("\"status\":\"OFFLINE\""))

        val online = MqttStatusApp(client = "wms_mobile", status = "ONLINE")
        val onlineStr = json.encodeToString(online)
        assertTrue(onlineStr.contains("\"status\":\"ONLINE\""))
    }

    @Test
    fun shouldExecuteAcenderAndApagarSuccessfullyOnFakeMqtt() = runTest {
        val acenderResult = mqttService.acenderLed(
            estanteLedId = "EST_A_LED1",
            prateleiraLedId = "PRAT_A2_LED1",
            posicaoLedId = "POS_A2_05"
        )
        assertTrue(acenderResult.isSuccess)
        assertEquals(1, mqttService.sentCommands.size)
        assertTrue(mqttService.sentCommands[0].contains("ACENDER"))

        val apagarResult = mqttService.apagarLed(
            estanteLedId = "EST_A_LED1",
            prateleiraLedId = "PRAT_A2_LED1",
            posicaoLedId = "POS_A2_05"
        )
        assertTrue(apagarResult.isSuccess)
        assertEquals(2, mqttService.sentCommands.size)
        assertTrue(mqttService.sentCommands[1].contains("APAGAR"))
    }

    @Test
    fun shouldHandlePingLatencyCorrectly() = runTest {
        mqttService.pingLatencyMs = 15L
        val pingResult = mqttService.enviarPing()
        assertTrue(pingResult.isSuccess)
        assertEquals(15L, pingResult.getOrNull())
    }

    @Test
    fun shouldEnqueuePendingCommandsWhenDisconnected() = runTest {
        mqttService.setConnectionState(MqttConnectionState.DISCONNECTED)

        val result = mqttService.acenderLed("EST_A", "PRAT_A1", "POS_A1_01")
        assertTrue(result.isFailure)
        assertEquals(1, mqttService.pendingCommandsCount.value)

        val result2 = mqttService.apagarLed("EST_A", "PRAT_A1", "POS_A1_01")
        assertTrue(result2.isFailure)
        assertEquals(2, mqttService.pendingCommandsCount.value)
    }

    @Test
    fun shouldFailWhenAckReturnsStatusErro() = runTest {
        mqttService.simulateAckError = true

        val result = mqttService.acenderLed("EST_A", "PRAT_A1", "POS_A1_01")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("ERRO") == true)
    }

    @Test
    fun shouldVerifyTopicsConstants() {
        assertEquals("wms/pickbylight/comando", MqttTopics.COMANDO)
        assertEquals("wms/pickbylight/ack", MqttTopics.ACK)
        assertEquals("wms/pickbylight/status/app", MqttTopics.STATUS_APP)
    }
}
