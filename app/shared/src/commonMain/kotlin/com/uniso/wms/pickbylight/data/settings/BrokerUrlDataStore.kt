package com.uniso.wms.pickbylight.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * Armazenamento persistente multiplataforma (DataStore no Android, estado em memória no Wasm)
 * para a URL do Broker MQTT (Seções 3.6 e 4.1 da especificação).
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
}
