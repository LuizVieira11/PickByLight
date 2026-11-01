package com.uniso.wms.pickbylight.data.mqtt

actual object MqttServiceProvider {
    private val fakeInstance: MqttService by lazy { FakeMqttService() }

    actual fun getMqttService(): MqttService = fakeInstance
}
