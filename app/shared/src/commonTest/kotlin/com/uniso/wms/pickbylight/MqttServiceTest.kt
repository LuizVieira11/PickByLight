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
import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.data.settings.AppSettings
import com.uniso.wms.pickbylight.data.settings.BrokerUrlDataStore
import com.uniso.wms.pickbylight.ui.screens.ajustes.AjustesViewModel
import com.uniso.wms.pickbylight.util.AppBuildConfig
import com.uniso.wms.pickbylight.util.FeedbackController
import kotlinx.coroutines.flow.first
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

    @Test
    fun shouldPersistBrokerUrlInDataStoreAndUseSavedUrlOnReconnect() = runTest {
        val customUrl = "tcp://10.0.0.99:1883"
        AppSettings.saveBrokerUrl(customUrl)
        mqttService.connect(customUrl)

        // Deve observar a URL salva no Flow do DataStore (e não voltar para o BuildConfig)
        val observedFromDataStore = AppSettings.brokerUrlFlow.first()
        assertEquals(customUrl, observedFromDataStore)
        assertEquals(customUrl, mqttService.activeBrokerUrl)

        // Mesmo quando connect(null) é chamado depois, deve usar a URL persistida no DataStore
        mqttService.connect(null)
        assertEquals(customUrl, mqttService.activeBrokerUrl)
    }

    @Test
    fun shouldAjustesViewModelCollectFromDataStoreAndNotRevertToBuildConfigWhenRecreated() = runTest {
        // Limpa o DataStore para simular primeira execução (savedUrl == null)
        BrokerUrlDataStore.saveBrokerUrl("")

        val repo = InMemoryWmsRepository()
        val feedback = FeedbackController()

        val viewModel1 = AjustesViewModel(
            mqttService = mqttService,
            repository = repo,
            feedbackController = feedback,
            coroutineScope = backgroundScope
        )
        testScheduler.runCurrent()

        // Na primeira execução (DataStore nulo), usa o fallback do BuildConfig
        assertEquals(AppBuildConfig.MQTT_BROKER_URL, viewModel1.uiState.value.brokerUrl)

        // Operador altera o IP na tela de Ajustes e clica em Salvar e Reconectar
        val novoIp = "tcp://192.168.15.200:1883"
        viewModel1.onBrokerUrlChanged(novoIp)
        assertEquals(novoIp, viewModel1.uiState.value.brokerUrl)

        viewModel1.salvarEReconectarBroker()
        testScheduler.runCurrent()

        assertEquals(novoIp, viewModel1.uiState.value.brokerUrl)
        assertEquals(novoIp, mqttService.activeBrokerUrl)
        assertEquals(novoIp, AppSettings.brokerUrlState.value)

        // Simula o usuário saindo da tela e voltando (recriando a AjustesViewModel)
        val viewModelRecriada = AjustesViewModel(
            mqttService = mqttService,
            repository = repo,
            feedbackController = feedback,
            coroutineScope = backgroundScope
        )
        testScheduler.runCurrent()

        // Deve exibir o IP salvo no DataStore e NÃO o IP padrão do BuildConfig
        assertEquals(novoIp, viewModelRecriada.uiState.value.brokerUrl)
    }
}
