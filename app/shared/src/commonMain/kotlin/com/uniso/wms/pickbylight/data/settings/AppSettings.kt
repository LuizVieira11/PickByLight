package com.uniso.wms.pickbylight.data.settings

import com.uniso.wms.pickbylight.util.AppBuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Configurações de aplicação compartilhadas (Seções 3.6 e 4.1 da especificação).
 * Gerencia preferências como a URL do Broker MQTT (com persistência em DataStore e fallback para BuildConfig)
 * e o Modo Debug NFC.
 */
object AppSettings {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _modoDebugNfc = MutableStateFlow(false)
    val modoDebugNfc: StateFlow<Boolean> = _modoDebugNfc.asStateFlow()

    fun setModoDebugNfc(enabled: Boolean) {
        _modoDebugNfc.value = enabled
    }

    /**
     * StateFlow global da URL do Broker MQTT observando o DataStore.
     * Não inicializa com BuildConfig antes de consultar o DataStore; usa BuildConfig
     * apenas se o valor salvo no DataStore for nulo (primeira execução).
     */
    private val _brokerUrlState = MutableStateFlow("")
    val brokerUrlState: StateFlow<String> = _brokerUrlState.asStateFlow()

    val brokerUrlFlow: StateFlow<String>
        get() = brokerUrlState

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            BrokerUrlDataStore.getBrokerUrl().collect { savedUrl ->
                val urlToShow = savedUrl?.takeIf { it.isNotBlank() } ?: AppBuildConfig.MQTT_BROKER_URL
                _brokerUrlState.value = urlToShow
            }
        }
    }

    /**
     * Salva a nova URL do Broker MQTT no DataStore e atualiza imediatamente o StateFlow global.
     */
    suspend fun saveBrokerUrl(url: String) {
        val clean = url.trim()
        if (clean.isNotBlank()) {
            _brokerUrlState.value = clean
            BrokerUrlDataStore.saveBrokerUrl(clean)
        }
    }

    /**
     * Resolve a URL do Broker MQTT seguindo a ordem de prioridade da Seção 4.1:
     * (1) valor persistido no DataStore, (2) fallback do AppBuildConfig.
     */
    suspend fun resolveBrokerUrlOrFallback(): String {
        val saved = BrokerUrlDataStore.getSavedBrokerUrl()
        val resolved = saved?.takeIf { it.isNotBlank() } ?: AppBuildConfig.MQTT_BROKER_URL
        _brokerUrlState.value = resolved
        return resolved
    }
}
