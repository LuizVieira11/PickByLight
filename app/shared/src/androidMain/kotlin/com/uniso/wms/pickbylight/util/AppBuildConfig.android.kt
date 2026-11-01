package com.uniso.wms.pickbylight.util

actual object AppBuildConfig {
    @Volatile
    private var dynamicUrl: String? = null

    fun setDynamicBrokerUrl(url: String) {
        dynamicUrl = url
    }

    actual val MQTT_BROKER_URL: String
        get() = dynamicUrl ?: System.getProperty("MQTT_BROKER_URL") ?: "tcp://192.168.1.100:1883"
}
