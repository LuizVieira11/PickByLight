package com.uniso.wms.pickbylight.util

/**
 * Cenários de feedback multimodal definidos na Seção 1.3 da especificação:
 * - SUCCESS_BEEP_VIBRATE: 1 vibração curta (150ms) + tom sonoro agudo
 * - WARNING_DOUBLE_BEEP_VIBRATE: 2 vibrações médias (250ms) + tom duplo
 * - ERROR_CRITICAL_LONG_VIBRATE: 1 vibração longa e intensa (600ms) + tom grave de falha
 */
enum class FeedbackScenario {
    SUCCESS_BEEP_VIBRATE,
    WARNING_DOUBLE_BEEP_VIBRATE,
    ERROR_CRITICAL_LONG_VIBRATE
}

data class FeedbackSettings(
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val volume: Float = 1.0f // 0.0f a 1.0f
)

expect class FeedbackController() {
    var settings: FeedbackSettings
    fun triggerFeedback(scenario: FeedbackScenario)
}
