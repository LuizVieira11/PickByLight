package com.uniso.wms.pickbylight.data.settings

import com.uniso.wms.pickbylight.data.mqtt.MqttProtocolMode
import kotlinx.coroutines.flow.Flow

/**
 * Armazenamento persistente multiplataforma (DataStore no Android, estado em memória no Wasm)
 * para configurações MQTT (URL do broker, modo de protocolo e Client ID estável da instalação).
 */
expect object BrokerUrlDataStore {
    /**
     * Retorna o Flow da URL salva no DataStore, emitindo null caso nenhum valor tenha sido salvo ainda.
     */
    fun getBrokerUrl(): Flow<String?>

    /**
     * Atalho para getBrokerUrl() mantendo compatibilidade.
     */
    val savedBrokerUrlFlow: Flow<String?>

    /**
     * Persiste a nova URL do Broker MQTT no DataStore.
     */
    suspend fun saveBrokerUrl(url: String)

    /**
     * Obtém o valor atual persistido no DataStore (ou null se vazio).
     */
    suspend fun getSavedBrokerUrl(): String?

    /** Flow do modo de protocolo MQTT selecionado (padrão [MqttProtocolMode.AUTO]). */
    fun getProtocolMode(): Flow<MqttProtocolMode>

    /** Persiste o modo de protocolo MQTT no mesmo DataStore. */
    suspend fun saveProtocolMode(mode: MqttProtocolMode)

    /** Lê o modo de protocolo MQTT atual persistido (fallback [MqttProtocolMode.AUTO]). */
    suspend fun getSavedProtocolMode(): MqttProtocolMode

    /** Retorna o Client ID único da instalação (`wms_mobile_<uuid>`), gerando e persistindo na 1ª leitura. */
    suspend fun getOrCreateClientId(): String
}
