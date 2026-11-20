package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspStatusOnline
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult

/**
 * Tipos da API de gerenciamento de ESPs em [MqttService] (especificacao/MQTT5_E_ESP32.md, seções 4.3, 9 e 16).
 * Não confundir com `EspMqttProtocol`, que é o protocolo que o ESP reporta em `capabilities.mqtt_protocol`.
 */

/** Protocolo MQTT efetivamente negociado na conexão do app (4.3). `null` no serviço = sem conexão. */
enum class MqttProtocol { V5_0, V3_1_1 }

/**
 * O que a conexão atual oferece (4.2). A lógica de negócio decide por aqui, nunca por `if (v5)`.
 * Nenhum fluxo depende só destes recursos: o payload JSON é a fonte da verdade nos dois protocolos.
 * Etapa 4 refina com o CONNACK real (reason codes, Maximum Packet Size, Receive Maximum).
 */
data class MqttCapabilities(
    val responseTopic: Boolean = false,
    val correlationData: Boolean = false,
    val messageExpiry: Boolean = false,
    val reasonCodes: Boolean = false
) {
    companion object {
        val NONE = MqttCapabilities()

        fun forProtocol(protocol: MqttProtocol?): MqttCapabilities = when (protocol) {
            MqttProtocol.V5_0 -> MqttCapabilities(
                responseTopic = true,
                correlationData = true,
                messageExpiry = true,
                reasonCodes = true
            )
            MqttProtocol.V3_1_1, null -> NONE
        }
    }
}

/** Presença de um ESP na conexão atual. [STALE] = só há retained, sem confirmação desde que o app conectou (9.3). */
enum class EspPresence { ONLINE, OFFLINE, STALE }

/** Visão de um ESP conhecido pelo app. [config] é o último `config/state` visto; `null` = ainda não lido. */
data class EspDevice(
    val espId: String,
    val presence: EspPresence,
    val status: EspStatusOnline?,
    val config: ConfigState? = null
)

/**
 * Resultado de uma operação que espera resposta do ESP. Ausência de resposta é [Timeout],
 * nunca "configuração vazia" nem sucesso (9.2). Falha antes de publicar (broker fora, payload inválido)
 * vem como `Result.failure` no método, não aqui.
 */
sealed interface MqttOutcome<out T> {
    data class Ok<T>(val value: T) : MqttOutcome<T>

    /** O ESP respondeu ERRO. [config] traz o `config/result` completo quando a operação é `config/set`. */
    data class Erro(val errorCode: EspErrorCode?, val config: ConfigResult? = null) : MqttOutcome<Nothing>

    data object Timeout : MqttOutcome<Nothing>
}

/** Janelas e timeouts da seção 9 e do contrato legado (5 s de ACK). */
object EspManagementTimings {
    const val DISCOVERY_WINDOW_MS = 3_000L
    const val CONFIG_TIMEOUT_MS = 5_000L
    const val ACK_TIMEOUT_MS = 5_000L
}
