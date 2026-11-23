package com.uniso.wms.pickbylight.ui.screens.ajustes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uniso.wms.pickbylight.data.mqtt.MqttConnectionState
import com.uniso.wms.pickbylight.data.mqtt.MqttProtocol
import com.uniso.wms.pickbylight.data.mqtt.MqttProtocolMode
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.data.settings.AppSettings
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.ui.components.PrimaryButton
import com.uniso.wms.pickbylight.ui.components.ScreenHeader
import com.uniso.wms.pickbylight.ui.theme.InkMuted
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.OkTeal
import com.uniso.wms.pickbylight.ui.theme.OnStrong
import com.uniso.wms.pickbylight.ui.theme.WarnAmber
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import org.jetbrains.compose.resources.painterResource
import pickbylight.app.shared.generated.resources.Res
import pickbylight.app.shared.generated.resources.ic_chevron_right

@Composable
fun AjustesScreen(
    mqttService: MqttService,
    repository: WmsRepository,
    feedbackController: FeedbackController,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateEsps: (() -> Unit)? = null,
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
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    PlatformBackHandler {
        onNavigateBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = dimensions.screenHorizontalPadding, vertical = dimensions.paddingMedium)
    ) {
        ScreenHeader(
            title = "Ajustes",
            actionLabel = "Voltar",
            onAction = onNavigateBack
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Card Conectividade MQTT (Seção 3.6)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(dimensions.cardCornerRadius)
        ) {
            Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
                Text(
                    text = "Conectividade MQTT",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Status da conexão", style = MaterialTheme.typography.bodyLarge, color = InkMuted)
                    val (badgeBg, badgeFg, badgeText, badgeBorder) = when (mqttState) {
                        MqttConnectionState.CONNECTED -> Quad(OkTeal, OnStrong, "Conectado", null)
                        MqttConnectionState.CONNECTING -> Quad(WarnAmber, OnStrong, "Reconectando...", null)
                        MqttConnectionState.DISCONNECTED -> Quad(MaterialTheme.colorScheme.surface, InkMuted, "Offline", MaterialTheme.colorScheme.outline)
                        MqttConnectionState.ERROR -> Quad(MaterialTheme.colorScheme.error, OnStrong, "Erro MQTT", null)
                    }
                    Surface(
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        color = badgeBg,
                        border = badgeBorder?.let { BorderStroke(1.dp, it) }
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelMedium,
                            color = badgeFg,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (mqttState == MqttConnectionState.CONNECTED) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val protocolText = when (uiState.negotiatedProtocol) {
                        MqttProtocol.V5_0 -> "Conectado via MQTT 5.0"
                        MqttProtocol.V3_1_1 -> "Conectado via MQTT 3.1.1"
                        null -> "Conectado"
                    }
                    Text(
                        text = protocolText,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                        color = OkTeal
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = uiState.brokerUrl,
                    onValueChange = { viewModel.onBrokerUrlChanged(it) },
                    label = { Text("URL do broker MQTT") },
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Protocolo MQTT",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Aplicado na próxima conexão",
                    style = MaterialTheme.typography.bodyLarge,
                    color = InkMuted
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(dimensions.cardCornerRadius),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val options = listOf(
                            MqttProtocolMode.AUTO to "Automático",
                            MqttProtocolMode.V5_0 to "5.0",
                            MqttProtocolMode.V3_1_1 to "3.1.1"
                        )
                        options.forEach { (mode, label) ->
                            val selected = uiState.protocolMode == mode
                            Surface(
                                shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = dimensions.minTouchTarget)
                                    .clickable { viewModel.onProtocolModeSelected(mode) }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                        color = if (selected) OnStrong else InkMuted,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                PrimaryButton(
                    text = "Salvar e reconectar",
                    onClick = { viewModel.salvarEReconectarBroker() }
                )

                Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

                OutlinedButton(
                    onClick = { viewModel.testarConexaoPing() },
                    enabled = !uiState.isTestingConnection,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dimensions.primaryButtonHeight),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    if (uiState.isTestingConnection) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Testando conexão...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Text(
                            text = "Testar conexão",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (uiState.pingStatusText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.pingStatusText ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (uiState.pingStatusText?.startsWith("Conexão OK") == true) OkTeal else MaterialTheme.colorScheme.error
                    )
                }

                if (onNavigateEsps != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp)
                            .clickable(onClick = onNavigateEsps)
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Gerenciar ESPs",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Descobrir e mapear controladores",
                                style = MaterialTheme.typography.bodyLarge,
                                color = InkMuted
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            painter = painterResource(Res.drawable.ic_chevron_right),
                            contentDescription = "Abrir gerenciamento de ESPs",
                            tint = InkMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card Acessibilidade e Feedback (Seção 3.6)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(dimensions.cardCornerRadius)
        ) {
            Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
                Text(
                    text = "Acessibilidade e feedback",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Feedback sonoro", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = "Bipes sonoros para confirmações e erros",
                            style = MaterialTheme.typography.bodyLarge,
                            color = InkMuted
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = uiState.soundEnabled,
                        onCheckedChange = { viewModel.setSoundEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Feedback tátil", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = "Vibrações para leituras NFC e alertas",
                            style = MaterialTheme.typography.bodyLarge,
                            color = InkMuted
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = uiState.vibrationEnabled,
                        onCheckedChange = { viewModel.setVibrationEnabled(it) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card Dados e Demonstração (Seção 3.6)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(dimensions.cardCornerRadius)
        ) {
            Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
                Text(
                    text = "Dados e demonstração",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Restaure a base de dados padrão para demonstrações.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = InkMuted
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Modo debug NFC", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = "Habilita ações de teste no catálogo",
                            style = MaterialTheme.typography.bodyLarge,
                            color = InkMuted
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = modoDebugNfc,
                        onCheckedChange = { AppSettings.setModoDebugNfc(it) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = { showResetConfirmDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dimensions.primaryButtonHeight),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = "Resetar dados de demonstração",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (uiState.resetStatusText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.resetStatusText ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (uiState.resetStatusText?.startsWith("Dados resetados") == true) OkTeal else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Resetar dados de demonstração?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    text = "Estantes, itens, tags NFC e histórico serão restaurados para a maquete padrão.",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirmDialog = false
                        viewModel.resetarDadosDemo()
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = OnStrong
                    )
                ) {
                    Text("Resetar")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetConfirmDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Cancelar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
