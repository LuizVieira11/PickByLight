package com.uniso.wms.pickbylight.data.esp

import com.uniso.wms.pickbylight.data.mqtt.MqttOutcome
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet

/**
 * Porta única de gravação de configuração de ESP (`config/set`) usada pela camada de apresentação (Etapa 5b).
 * Garante aquisição prévia do [ConfigLock] e liberação incondicional em `finally` (OK, erro do ESP,
 * timeout de 5 s, desconexão ou cancelamento da coroutine).
 */
class EspConfigCoordinator(
    private val mqttService: MqttService,
    private val configLock: ConfigLock = ConfigLockProvider.getLock()
) {
    suspend fun gravarConfig(set: ConfigSet): Result<MqttOutcome<ConfigResult>> {
        when (val lockResult = configLock.tryStartConfig(set.espId)) {
            is ConfigLockResult.Blocked -> {
                return Result.failure(
                    IllegalStateException("Configuração bloqueada: ${lockResult.reason}")
                )
            }
            ConfigLockResult.Acquired -> {
                return try {
                    mqttService.gravarConfig(set)
                } finally {
                    configLock.endConfig(set.espId)
                }
            }
        }
    }
}
