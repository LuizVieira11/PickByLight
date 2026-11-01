package com.uniso.wms.pickbylight.ui.screens.ajustes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.data.mqtt.MqttConnectionState
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.data.settings.AppSettings
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.ui.components.PrimaryButton
import com.uniso.wms.pickbylight.ui.theme.AmberWarning
import com.uniso.wms.pickbylight.ui.theme.CoralError
import com.uniso.wms.pickbylight.ui.theme.EmeraldSuccess
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.ShelfRackBlue
import com.uniso.wms.pickbylight.util.AppBuildConfig
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.FeedbackScenario
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun AjustesScreen(
    mqttService: MqttService,
    repository: WmsRepository,
    feedbackController: FeedbackController,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalWmsDimensions.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val mqttState by mqttService.connectionState.collectAsState()
    var brokerUrlInput by remember { mutableStateOf(AppBuildConfig.MQTT_BROKER_URL) }
    var pingStatusText by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }

    var soundEnabled by remember { mutableStateOf(feedbackController.settings.soundEnabled) }
    var vibrationEnabled by remember { mutableStateOf(feedbackController.settings.vibrationEnabled) }

    PlatformBackHandler {
        onNavigateBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(dimensions.paddingMedium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Ajustes e Conectividade",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onNavigateBack) {
                Text("Voltar", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card Conectividade MQTT (Seção 3.6)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Conectividade MQTT (IoT)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Status da Conexão:", style = MaterialTheme.typography.bodyMedium)
                    val (badgeBg, badgeText) = when (mqttState) {
                        MqttConnectionState.CONNECTED -> EmeraldSuccess to "Conectado"
                        MqttConnectionState.CONNECTING -> AmberWarning to "Reconectando..."
                        MqttConnectionState.DISCONNECTED -> CoralError to "Offline"
                        MqttConnectionState.ERROR -> CoralError to "Erro MQTT"
                    }
                    Box(
                        modifier = Modifier
                            .background(badgeBg, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = brokerUrlInput,
                    onValueChange = { brokerUrlInput = it },
                    label = { Text("URL do Broker MQTT") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                PrimaryButton(
                    text = if (isTestingConnection) "Testando Conexão..." else "Testar Conexão (PING)",
                    onClick = {
                        coroutineScope.launch(Dispatchers.Default) {
                            isTestingConnection = true
                            val result = mqttService.enviarPing()
                            isTestingConnection = false
                            if (result.isSuccess) {
                                val latencia = result.getOrNull() ?: 0L
                                pingStatusText = "Conexão OK: ${latencia}ms de latência."
                                feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                            } else {
                                pingStatusText = "Falha no Ping: ${result.exceptionOrNull()?.message}"
                                feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
                            }
                        }
                    },
                    isLoading = isTestingConnection,
                    containerColor = ShelfRackBlue
                )

                if (pingStatusText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = pingStatusText ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (pingStatusText?.startsWith("Conexão OK") == true) EmeraldSuccess else CoralError
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                PrimaryButton(
                    text = "Reconectar Agora",
                    onClick = {
                        coroutineScope.launch(Dispatchers.Default) {
                            mqttService.connect(brokerUrlInput)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card Acessibilidade e Feedback (Seção 3.6)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Acessibilidade & Feedback Multimodal",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Feedback Sonoro", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Bipes sonoros para confirmações e erros",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = soundEnabled,
                        onCheckedChange = {
                            soundEnabled = it
                            feedbackController.settings = feedbackController.settings.copy(soundEnabled = it)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Feedback Tátil (Vibração)", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Vibrações para leituras NFC e alertas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = vibrationEnabled,
                        onCheckedChange = {
                            vibrationEnabled = it
                            feedbackController.settings = feedbackController.settings.copy(vibrationEnabled = it)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card Dados & Demonstração (Seção 3.6)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Dados e Demonstração",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Restaure a base de dados padrão para as demonstrações da banca do TCC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                val modoDebugNfc by AppSettings.modoDebugNfc.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Modo Debug NFC", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Habilita ações de teste (ex: simular lock de item para a banca)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = modoDebugNfc,
                        onCheckedChange = { AppSettings.setModoDebugNfc(it) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                PrimaryButton(
                    text = "Resetar Dados de Demonstração (Seed)",
                    onClick = {
                        coroutineScope.launch {
                            repository.resetarDadosDemo()
                            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                        }
                    },
                    containerColor = AmberWarning,
                    contentColor = Color.White
                )
            }
        }
    }
}
