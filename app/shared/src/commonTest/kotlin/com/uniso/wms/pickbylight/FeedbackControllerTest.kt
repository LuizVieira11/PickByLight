package com.uniso.wms.pickbylight

import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.FeedbackScenario
import com.uniso.wms.pickbylight.util.FeedbackSettings
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackControllerTest {

    private lateinit var controller: FeedbackController

    @BeforeTest
    fun setup() {
        controller = FeedbackController()
    }

    @Test
    fun shouldHaveDefaultSettingsActive() {
        assertTrue(controller.settings.soundEnabled)
        assertTrue(controller.settings.vibrationEnabled)
        assertEquals(1.0f, controller.settings.volume)
    }

    @Test
    fun shouldAllowUpdatingSettings() {
        val newSettings = FeedbackSettings(
            soundEnabled = false,
            vibrationEnabled = true,
            volume = 0.5f
        )
        controller.settings = newSettings

        assertEquals(newSettings, controller.settings)
    }

    @Test
    fun shouldTriggerAllScenariosWithoutException() {
        // Sucesso
        controller.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
        // Alerta
        controller.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
        // Erro Crítico
        controller.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
    }

    @Test
    fun shouldHandleSilentModeWithoutException() {
        controller.settings = FeedbackSettings(soundEnabled = false, vibrationEnabled = false)
        controller.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
        controller.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
    }
}
