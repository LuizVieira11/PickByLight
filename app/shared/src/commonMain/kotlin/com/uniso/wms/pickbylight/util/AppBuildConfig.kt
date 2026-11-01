package com.uniso.wms.pickbylight.util

/**
 * Provedor de configuração de build multiplataforma.
 * A URL do broker MQTT nunca é hardcoded na interface:
 * Prioridade: (1) parâmetro explícito, (2) DataStore, (3) AppBuildConfig lido do local.properties com fallback.
 */
expect object AppBuildConfig {
    val MQTT_BROKER_URL: String
}
