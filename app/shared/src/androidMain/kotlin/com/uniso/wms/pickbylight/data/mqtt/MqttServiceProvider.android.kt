package com.uniso.wms.pickbylight.data.mqtt

import com.uniso.wms.pickbylight.data.settings.AppSettings

actual object MqttServiceProvider {
    @Volatile
    private var instance: MqttService? = null

    fun setMqttService(service: MqttService) {
        instance = service
    }

    actual fun getMqttService(): MqttService {
        return instance ?: synchronized(this) {
            instance ?: PahoMqttServiceImpl(
                inventoryLoader = { AppSettings.getSavedEspInventoryJson() },
                inventorySaver = { json -> AppSettings.saveEspInventoryJson(json) }
            ).also { instance = it }
        }
    }
}

