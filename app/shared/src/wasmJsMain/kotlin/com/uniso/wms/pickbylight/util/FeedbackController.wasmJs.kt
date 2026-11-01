package com.uniso.wms.pickbylight.util

actual class FeedbackController actual constructor() {

    actual var settings: FeedbackSettings = FeedbackSettings()

    actual fun triggerFeedback(scenario: FeedbackScenario) {
        if (!settings.soundEnabled && !settings.vibrationEnabled) return
        // No navegador/WasmJs, registra o feedback multimodal de forma segura
        println("[Feedback WasmJs] Cenário disparado: $scenario (som=${settings.soundEnabled}, vibração=${settings.vibrationEnabled})")
    }
}
