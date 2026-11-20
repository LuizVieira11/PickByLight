package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import kotlinx.coroutines.flow.StateFlow

enum class MqttConnectionState {
    CONNECTED,
    DISCONNECTED,
    CONNECTING,
    ERROR
}

interface MqttService {
    val connectionState: StateFlow<MqttConnectionState>
    val pendingCommandsCount: StateFlow<Int>

    /**
     * Conecta ao broker MQTT.
     * @param brokerUrl URL do broker. Se null, usa o valor persistido em DataStore/SharedPreferences.
     *                  Se nenhum valor estiver persistido, usa fallback do BuildConfig (não hardcoded na interface).
     */
    suspend fun connect(brokerUrl: String? = null)
    suspend fun acenderLed(estanteLedId: String, prateleiraLedId: String, posicaoLedId: String, cor: String = "VERDE"): Result<Unit>
    suspend fun apagarLed(estanteLedId: String, prateleiraLedId: String, posicaoLedId: String): Result<Unit>
    suspend fun testarLed(estanteLedId: String, prateleiraLedId: String?, posicaoLedId: String?, cor: String = "AZUL", duracaoMs: Int = 3000): Result<Unit>
    suspend fun enviarPing(): Result<Long> // Retorna latência em ms
    fun disconnect()

    // ---- Gerenciamento de ESPs (MQTT5_E_ESP32.md, seções 4.3, 9 e 16). Membros acima não mudam. ----

    /** Protocolo negociado na conexão atual; `null` sem conexão. */
    val negotiatedProtocol: StateFlow<MqttProtocol?>

    /** O que a conexão atual oferece. A lógica de negócio decide por aqui, não pelo protocolo. */
    val capabilities: StateFlow<MqttCapabilities>

    /** ESPs conhecidos (retained de status + discovery), ordenados por `esp_id`. */
    val espDevices: StateFlow<List<EspDevice>>

    /**
     * Assina os tópicos v1, espera o SUBACK e só então publica `discovery/request` (9.1).
     * Devolve os ESPs conhecidos ao fim da janela de [EspManagementTimings.DISCOVERY_WINDOW_MS].
     * `Result.failure` = não foi possível publicar (broker fora, payload inválido).
     */
    suspend fun descobrirEsps(): Result<List<EspDevice>>

    /** `config/get`: publica no tópico do ESP e aguarda o `config/state` correlacionado. Timeout de 5 s. */
    suspend fun obterConfig(espId: String): Result<MqttOutcome<ConfigState>>

    /**
     * `config/set`: nunca entra na fila de comandos pendentes (9.2). Aguarda o `config/result` com o mesmo
     * `request_id` de [set], até [EspManagementTimings.CONFIG_TIMEOUT_MS]. PUBACK não é confirmação de aplicação.
     */
    suspend fun gravarConfig(set: ConfigSet): Result<MqttOutcome<ConfigResult>>

    /**
     * `TESTE_LED` legado com resposta tipada (16.5): OK = ACK OK, Erro = ACK ERRO, Timeout = sem ACK em 5 s.
     * Diferente de [testarLed], que continua devolvendo sucesso também no timeout.
     */
    suspend fun testarLedComConfirmacao(
        estanteLedId: String,
        prateleiraLedId: String?,
        posicaoLedId: String?,
        cor: String = "AZUL",
        duracaoMs: Int = 3000
    ): Result<MqttOutcome<Unit>>
}
