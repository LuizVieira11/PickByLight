package com.uniso.wms.pickbylight.data.mqtt

expect object MqttServiceProvider {
    fun getMqttService(): MqttService
}
