package com.uniso.wms.pickbylight.data.mqtt

actual object MqttServiceProvider {
    @Volatile
    private var instance: MqttService? = null

    fun setMqttService(service: MqttService) {
        instance = service
    }

    actual fun getMqttService(): MqttService {
        return instance ?: synchronized(this) {
            instance ?: PahoMqttServiceImpl().also { instance = it }
        }
    }
}
