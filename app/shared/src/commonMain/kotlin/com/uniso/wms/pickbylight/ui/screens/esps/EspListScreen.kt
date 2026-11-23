package com.uniso.wms.pickbylight.ui.screens.esps

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.uniso.wms.pickbylight.ui.components.ScreenHeader
import com.uniso.wms.pickbylight.ui.responsive.LocalWindowSizeClass
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
import pickbylight.app.shared.generated.resources.ic_cloud_off
import pickbylight.app.shared.generated.resources.ic_error
import pickbylight.app.shared.generated.resources.ic_history
import pickbylight.app.shared.generated.resources.ic_warning
import pickbylight.app.shared.generated.resources.ic_wifi

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
        ScreenHeader(
            title = "Controladores ESP",
            actionLabel = "Voltar",
            onAction = onNavigateBack
        )

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
                        style = MaterialTheme.typography.bodyLarge,
                        color = InkMuted
                    )
                }
            }
        }
        EspListPhase.Empty -> {
            EmptyState(
                title = "Nenhum ESP32 encontrado",
                message = "Nenhum controlador respondeu ao discovery na janela de 3 segundos.",
                actionLabel = "Descobrir ESPs novamente",
                onActionClick = { viewModel.descobrirEsps() },
                modifier = Modifier.fillMaxSize()
            )
        }
        is EspListPhase.Error -> {
            ErrorState(
                message = phase.message,
                retryLabel = "Tentar novamente",
                onRetry = { viewModel.descobrirEsps() },
                modifier = Modifier.fillMaxSize()
            )
        }
        EspListPhase.Success -> {
            PrimaryButton(
                text = if (uiState.isDiscovering) "Descobrindo ESPs..." else "Descobrir ESPs",
                onClick = { viewModel.descobrirEsps() },
                isLoading = uiState.isDiscovering
            )
            if (uiState.listErrorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_error),
                        contentDescription = null, // Decorativo: o texto ao lado informa o erro
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = uiState.listErrorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
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
    val dimensions = LocalWmsDimensions.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, WarnAmber)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_warning),
                contentDescription = null, // Decorativo: o texto ao lado informa o bloqueio de gravação
                tint = WarnAmber,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Coleta em andamento: gravação bloqueada",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
internal fun EspStatusBadge(device: EspDevice) {
    val dimensions = LocalWmsDimensions.current
    when {
        device.reportedStatus == EspOnlineStatus.CONFIG_INVALID -> {
            EspStatusChip(
                bg = MaterialTheme.colorScheme.error,
                fg = OnStrong,
                iconRes = Res.drawable.ic_error,
                text = "CONFIG_INVALID (${device.errorCode?.name ?: "ERRO"})",
                cornerRadius = dimensions.buttonCornerRadius
            )
        }
        device.presence == EspPresence.ONLINE -> {
            EspStatusChip(
                bg = OkTeal,
                fg = OnStrong,
                iconRes = Res.drawable.ic_wifi,
                text = "Online",
                cornerRadius = dimensions.buttonCornerRadius
            )
        }
        device.presence == EspPresence.OFFLINE -> {
            EspStatusChip(
                bg = MaterialTheme.colorScheme.surface,
                fg = InkMuted,
                iconRes = Res.drawable.ic_cloud_off,
                text = "Offline",
                cornerRadius = dimensions.buttonCornerRadius,
                borderColor = MaterialTheme.colorScheme.outline
            )
        }
        else -> {
            EspStatusChip(
                bg = WarnAmber,
                fg = OnStrong,
                iconRes = Res.drawable.ic_history,
                text = "Possivelmente antigo",
                cornerRadius = dimensions.buttonCornerRadius
            )
        }
    }
}

@Composable
private fun EspStatusChip(
    bg: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color,
    iconRes: org.jetbrains.compose.resources.DrawableResource,
    text: String,
    cornerRadius: androidx.compose.ui.unit.Dp,
    borderColor: androidx.compose.ui.graphics.Color? = null
) {
    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = bg,
        border = borderColor?.let { BorderStroke(1.dp, it) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null, // Decorativo: o rótulo ao lado informa o estado do ESP
                tint = fg,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                color = fg,
                style = MaterialTheme.typography.labelMedium
            )
        }
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(dimensions.cardCornerRadius)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = dimensions.minTouchTarget)
                .padding(dimensions.paddingMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = device.espId,
                        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    EspStatusBadge(device)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Firmware $firmware · $protocol",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Revisão ${revision?.toString() ?: "—"} · Canais usados $usedChannels/$totalChannels",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    color = InkMuted
                )
                if (device.reportedStatus == EspOnlineStatus.CONFIG_INVALID) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = EspManagementViewModel.readableErrorCode(device.errorCode),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(Res.drawable.ic_chevron_right),
                contentDescription = "Abrir detalhes do controlador",
                tint = InkMuted,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}


