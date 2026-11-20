package com.uniso.wms.pickbylight

import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.MqttAck
import com.uniso.wms.pickbylight.data.mqtt.model.MqttPong
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ACK legado, ACK aditivo e PONG (MQTT5_E_ESP32.md 6.9). Json igual ao de PahoMqttServiceImpl.handleAckMessage. */
class MqttAckCompatibilityTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun legacyAckWithoutNewFieldsStillDecodes() {
        val ack = json.decodeFromString<MqttAck>("""{"acao":"ACK","posicao_led_id":"POS_A1_01","status":"OK"}""")
        assertEquals("ACK", ack.acao)
        assertEquals("POS_A1_01", ack.posicaoLedId)
        assertTrue(ack.isOk)
        assertNull(ack.espId)
        assertNull(ack.bootId)
        assertNull(ack.requestId)
        assertNull(ack.errorCode)
        assertFalse(ack.isPong)
    }

    @Test
    fun statusRemainsRequiredAndAcaoHasDefault() {
        assertEquals("ACK", json.decodeFromString<MqttAck>("""{"status":"ERRO"}""").acao)
        assertTrue(runCatching { json.decodeFromString<MqttAck>("""{"acao":"ACK"}""") }.isFailure)
    }

    @Test
    fun ackWithExtraFieldsDecodes() {
        val ack = json.decodeFromString<MqttAck>(
            """{"acao":"ACK","posicao_led_id":"POS_A1_01","status":"OK","esp_id":"esp-a1b2c3","boot_id":"ab12cd34","request_id":"r-1","campo_futuro":123}"""
        )
        assertEquals("esp-a1b2c3", ack.espId)
        assertEquals("ab12cd34", ack.bootId)
        assertEquals("r-1", ack.requestId)
        assertTrue(ack.isOk)
    }

    @Test
    fun ackErrorCodeToleratesUnknownValues() {
        val known = json.decodeFromString<MqttAck>("""{"acao":"ACK","status":"ERRO","error_code":"INTERNAL_ERROR"}""")
        assertEquals(EspErrorCode.INTERNAL_ERROR, known.errorCodeEnum)
        val unknown = json.decodeFromString<MqttAck>("""{"acao":"ACK","status":"ERRO","error_code":"CODIGO_NOVO"}""")
        assertEquals("CODIGO_NOVO", unknown.errorCode)
        assertNull(unknown.errorCodeEnum)
        assertFalse(unknown.isOk)
    }

    @Test
    fun pongWithStatusDecodesAsMqttAck() {
        val ack = json.decodeFromString<MqttAck>(
            """{"acao":"PONG","status":"OK","esp_id":"esp-a1b2c3","boot_id":"ab12cd34"}"""
        )
        assertEquals("PONG", ack.acao)
        assertTrue(ack.isPong)
        assertTrue(ack.isOk)
        assertNull(ack.posicaoLedId) // sem posição: PahoMqttServiceImpl.handleAckMessage usa a chave "GLOBAL"
        assertEquals("esp-a1b2c3", ack.espId)
    }

    @Test
    fun pongModelProducesFirmwarePayloadAndRoundTripsThroughMqttAck() {
        val pong = json.encodeToString(MqttPong(espId = "esp-a1b2c3", bootId = "ab12cd34"))
        assertEquals("""{"acao":"PONG","status":"OK","esp_id":"esp-a1b2c3","boot_id":"ab12cd34"}""", pong)
        assertTrue(json.decodeFromString<MqttAck>(pong).isOk)
    }
}
