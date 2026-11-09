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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.PlatformBackHandler

@Composable
fun AjustesScreen(
    mqttService: MqttService,
    repository: WmsRepository,
    feedbackController: FeedbackController,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AjustesViewModel = viewModel {
        AjustesViewModel(
            mqttService = mqttService,
            repository = repository,
            feedbackController = feedbackController
        )
    }
) {
    val dimensions = LocalWmsDimensions.current
    val scrollState = rememberScrollState()

    val uiState by viewModel.uiState.collectAsState()
    val mqttState by mqttService.connectionState.collectAsState()
    val modoDebugNfc by AppSettings.modoDebugNfc.collectAsState()

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
                    value = uiState.brokerUrl,
                    onValueChange = { viewModel.onBrokerUrlChanged(it) },
                    label = { Text("URL do Broker MQTT") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                PrimaryButton(
                    text = if (uiState.isTestingConnection) "Testando Conexão..." else "Testar Conexão (PING)",
                    onClick = { viewModel.testarConexaoPing() },
                    isLoading = uiState.isTestingConnection,
                    containerColor = ShelfRackBlue
                )

                if (uiState.pingStatusText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.pingStatusText ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (uiState.pingStatusText?.startsWith("Conexão OK") == true) EmeraldSuccess else CoralError
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                PrimaryButton(
                    text = "Salvar e Reconectar Agora",
                    onClick = { viewModel.salvarEReconectarBroker() },
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
                        checked = uiState.soundEnabled,
                        onCheckedChange = { viewModel.setSoundEnabled(it) }
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
                        checked = uiState.vibrationEnabled,
                        onCheckedChange = { viewModel.setVibrationEnabled(it) }
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
                    onClick = { viewModel.resetarDadosDemo() },
                    containerColor = AmberWarning,
                    contentColor = Color.White
                )

                if (uiState.resetStatusText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.resetStatusText ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (uiState.resetStatusText?.startsWith("Dados resetados") == true) EmeraldSuccess else CoralError
                    )
                }
            }
        }
    }
}
