package com.uniso.wms.pickbylight.ui.screens.esps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uniso.wms.pickbylight.data.mqtt.EspDevice
import com.uniso.wms.pickbylight.data.mqtt.EspPresence
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.data.mqtt.model.EspMqttProtocol
import com.uniso.wms.pickbylight.data.mqtt.model.EspOnlineStatus
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.ui.components.EmptyState
import com.uniso.wms.pickbylight.ui.components.ErrorState
import com.uniso.wms.pickbylight.ui.components.PrimaryButton
import com.uniso.wms.pickbylight.ui.responsive.LocalWindowSizeClass
import com.uniso.wms.pickbylight.ui.theme.InkMuted
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.OkTeal
import com.uniso.wms.pickbylight.ui.theme.WarnAmber
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.PlatformBackHandler

@Composable
fun EspListScreen(
    mqttService: MqttService,
    repository: WmsRepository,
    feedbackController: FeedbackController,
    onNavigateBack: () -> Unit,
    onOpenEspDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EspManagementViewModel = viewModel {
        EspManagementViewModel(
            mqttService = mqttService,
            repository = repository,
            feedbackController = feedbackController
        )
    }
) {
    val dimensions = LocalWmsDimensions.current
    val windowSize = LocalWindowSizeClass.current
    val uiState by viewModel.uiState.collectAsState()

    PlatformBackHandler { onNavigateBack() }

    LaunchedEffect(Unit) {
        viewModel.iniciarLista()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimensions.screenHorizontalPadding, vertical = dimensions.paddingMedium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Gerenciamento de ESPs",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onNavigateBack,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Voltar", fontSize = 16.sp)
            }
        }

        if (uiState.isColetaActive) {
            Spacer(modifier = Modifier.height(8.dp))
            ColetaActiveBanner()
        }

        Spacer(modifier = Modifier.height(12.dp))
        EspListContent(uiState, viewModel, windowSize.widthSizeClass.isCompact, onOpenEspDetail)
    }
}

@Composable
private fun EspListContent(
    uiState: EspManagementUiState,
    viewModel: EspManagementViewModel,
    isCompact: Boolean,
    onOpenEspDetail: (String) -> Unit
) {
    when (val phase = uiState.listPhase) {
        EspListPhase.Unsupported -> {
            EmptyState(
                title = "Gerenciamento de ESPs indisponível na versão Web",
                message = "Use o aplicativo Android para descobrir e configurar controladores ESP32 via MQTT.",
                modifier = Modifier.fillMaxSize()
            )
        }
        EspListPhase.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Descobrindo controladores ESP32 (janela de 3 s)...",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        EspListPhase.Empty -> {
            EmptyState(
                title = "Nenhum ESP32 encontrado",
                message = "Nenhum controlador respondeu ao discovery na janela de 3 segundos.",
                actionLabel = "Descobrir ESPs Novamente",
                onActionClick = { viewModel.descobrirEsps() },
                modifier = Modifier.fillMaxSize()
            )
        }
        is EspListPhase.Error -> {
            ErrorState(
                message = phase.message,
                retryLabel = "Tentar Discovery Novamente",
                onRetry = { viewModel.descobrirEsps() },
                modifier = Modifier.fillMaxSize()
            )
        }
        EspListPhase.Success -> {
            PrimaryButton(
                text = if (uiState.isDiscovering) "Descobrindo ESPs..." else "Descobrir ESPs (Atualizar)",
                onClick = { viewModel.descobrirEsps() },
                isLoading = uiState.isDiscovering,
                containerColor = MaterialTheme.colorScheme.onSurface
            )
            if (uiState.listErrorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.listErrorMessage,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (!isCompact) {
                val rows = uiState.devices.chunked(2)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(rows) { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            pair.forEach { device ->
                                EspDeviceCard(
                                    device = device,
                                    onClick = { onOpenEspDetail(device.espId) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(uiState.devices, key = { it.espId }) { device ->
                        EspDeviceCard(
                            device = device,
                            onClick = { onOpenEspDetail(device.espId) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ColetaActiveBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(WarnAmber, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = "Coleta em andamento: gravação bloqueada",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun EspStatusBadge(device: EspDevice) {
    val (bg, text) = when {
        device.reportedStatus == EspOnlineStatus.CONFIG_INVALID ->
            MaterialTheme.colorScheme.error to "CONFIG_INVALID (${device.errorCode?.name ?: "ERRO"})"
        device.presence == EspPresence.ONLINE ->
            OkTeal to "ONLINE"
        device.presence == EspPresence.OFFLINE ->
            InkMuted to "OFFLINE"
        else ->
            WarnAmber to "STATE_STALE (Possivelmente antigo)"
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EspDeviceCard(
    device: EspDevice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalWmsDimensions.current
    val firmware = device.status?.capabilities?.firmwareVersion ?: "—"
    val protocol = when (device.status?.capabilities?.mqttProtocol) {
        EspMqttProtocol.V5 -> "MQTT 5.0"
        EspMqttProtocol.V3_1_1 -> "MQTT 3.1.1"
        null -> "—"
    }
    val revision = device.config?.configRevision ?: device.status?.configRevision
    val totalChannels = device.status?.capabilities?.channels?.size ?: 0
    val usedChannels = device.config?.mappings?.size ?: 0

    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = device.espId,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                EspStatusBadge(device)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Firmware: $firmware • Protocolo: $protocol", fontSize = 16.sp)
            Text(
                text = "Revisão: ${revision?.toString() ?: "—"} • Canais usados: $usedChannels/$totalChannels",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (device.reportedStatus == EspOnlineStatus.CONFIG_INVALID) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = EspManagementViewModel.readableErrorCode(device.errorCode),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = Color.White)
            ) {
                Text("Configurar / Detalhes", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}


