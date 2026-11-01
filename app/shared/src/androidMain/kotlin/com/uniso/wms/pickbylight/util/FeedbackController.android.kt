package com.uniso.wms.pickbylight.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

actual class FeedbackController actual constructor() {

    actual var settings: FeedbackSettings = FeedbackSettings()

    companion object {
        private var applicationContext: Context? = null

        fun init(context: Context) {
            applicationContext = context.applicationContext
        }
    }

    private fun getVibrator(): Vibrator? {
        val context = applicationContext ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    actual fun triggerFeedback(scenario: FeedbackScenario) {
        if (settings.vibrationEnabled) {
            triggerVibration(scenario)
        }
        if (settings.soundEnabled) {
            triggerSound(scenario)
        }
    }

    private fun triggerVibration(scenario: FeedbackScenario) {
        val vibrator = getVibrator() ?: return
        if (!vibrator.hasVibrator()) return

        try {
            when (scenario) {
                // Sucesso: 1 vibração curta (150ms)
                FeedbackScenario.SUCCESS_BEEP_VIBRATE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(150)
                    }
                }
                // Alerta/Aviso: 2 vibrações médias (250ms)
                FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE -> {
                    val timings = longArrayOf(0, 250, 100, 250)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(timings, -1)
                    }
                }
                // Erro Crítico: 1 vibração longa e intensa (600ms)
                FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(600, 255))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(600)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun triggerSound(scenario: FeedbackScenario) {
        try {
            val volumeInt = (settings.volume * 100).toInt().coerceIn(1, 100)
            val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volumeInt)

            when (scenario) {
                // Sucesso: tom agudo curto (150ms)
                FeedbackScenario.SUCCESS_BEEP_VIBRATE -> {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                }
                // Alerta: tom duplo
                FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE -> {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 300)
                }
                // Erro: sinal sonoro grave de falha (500ms)
                FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE -> {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 500)
                }
            }
        } catch (_: Exception) {}
    }
}
