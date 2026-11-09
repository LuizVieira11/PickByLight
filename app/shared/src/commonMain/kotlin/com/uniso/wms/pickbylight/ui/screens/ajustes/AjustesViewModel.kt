package com.uniso.wms.pickbylight.ui.screens.ajustes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.data.settings.AppSettings
import com.uniso.wms.pickbylight.data.settings.BrokerUrlDataStore
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.util.AppBuildConfig
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.FeedbackScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado UDF da Tela de Ajustes e Conectividade (Seção 3.6).
 * O campo brokerUrl inicia vazio e é populado de forma assíncrona via coleta do DataStore no init da ViewModel.
 */
data class AjustesUiState(
    val brokerUrl: String = "",
    val pingStatusText: String? = null,
    val resetStatusText: String? = null,
    val isTestingConnection: Boolean = false,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true
)

class AjustesViewModel(
    private val mqttService: MqttService,
    private val repository: WmsRepository,
    private val feedbackController: FeedbackController,
    private val dataStore: BrokerUrlDataStore = BrokerUrlDataStore,
    coroutineScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow(
        AjustesUiState(
            soundEnabled = feedbackController.settings.soundEnabled,
            vibrationEnabled = feedbackController.settings.vibrationEnabled
        )
    )
    val uiState: StateFlow<AjustesUiState> = _uiState.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            dataStore.getBrokerUrl().collect { savedUrl ->
                // Só usa o BuildConfig se o savedUrl for nulo (primeira execução)
                val urlToShow = savedUrl ?: AppBuildConfig.MQTT_BROKER_URL
                _uiState.update { it.copy(brokerUrl = urlToShow) }
            }
        }
    }

    /**
     * Atualiza o estado da URL na ViewModel conforme o usuário digita no TextField.
     */
    fun onBrokerUrlChanged(newUrl: String) {
        _uiState.update { it.copy(brokerUrl = newUrl) }
    }

    /**
     * Salva a URL no DataStore, emite o novo estado para a UI e reconecta o MqttService imediatamente.
     */
    fun salvarEReconectarBroker() {
        val novaUrl = _uiState.value.brokerUrl.trim()
        if (novaUrl.isBlank()) return

        _uiState.update { it.copy(brokerUrl = novaUrl, pingStatusText = null) }
        scope.launch {
            AppSettings.saveBrokerUrl(novaUrl)
            mqttService.connect(novaUrl)
        }
    }

    /**
     * Testa a conectividade com o ESP32 via PING (Seção 3.6).
     */
    fun testarConexaoPing() {
        scope.launch {
            _uiState.update { it.copy(isTestingConnection = true) }
            val result = mqttService.enviarPing()
            if (result.isSuccess) {
                val latencia = result.getOrNull() ?: 0L
                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        pingStatusText = "Conexão OK: ${latencia}ms de latência."
                    )
                }
                feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
            } else {
                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        pingStatusText = "Falha no Ping: ${result.exceptionOrNull()?.message}"
                    )
                }
                feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
            }
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        feedbackController.settings = feedbackController.settings.copy(soundEnabled = enabled)
        _uiState.update { it.copy(soundEnabled = enabled) }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        feedbackController.settings = feedbackController.settings.copy(vibrationEnabled = enabled)
        _uiState.update { it.copy(vibrationEnabled = enabled) }
    }

    fun resetarDadosDemo() {
        scope.launch {
            val result = repository.resetarDadosDemo()
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        resetStatusText = "Dados resetados! Maquete 3x3 carregada (1 Estante, 3 Prateleiras, 9 Posições, 9 Tags NFC e 5 Itens)."
                    )
                }
                feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
            } else {
                _uiState.update {
                    it.copy(
                        resetStatusText = "Falha ao resetar dados: ${result.exceptionOrNull()?.message}"
                    )
                }
                feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
            }
        }
    }
}

