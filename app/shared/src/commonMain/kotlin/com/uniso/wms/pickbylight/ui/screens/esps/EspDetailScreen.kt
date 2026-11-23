package com.uniso.wms.pickbylight.ui.screens.esps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.EspMqttProtocol
import com.uniso.wms.pickbylight.data.mqtt.model.EspOnlineStatus
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.ui.components.EmptyState
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
import pickbylight.app.shared.generated.resources.ic_error

@Composable
fun EspDetailScreen(
    espId: String,
    mqttService: MqttService,
    repository: WmsRepository,
    feedbackController: FeedbackController,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EspManagementViewModel = viewModel(key = "esp_detail_$espId") {
        EspManagementViewModel(mqttService = mqttService, repository = repository, feedbackController = feedbackController)
    }
) {
    val dimensions = LocalWmsDimensions.current
    val scrollState = rememberScrollState()
    val uiState by viewModel.uiState.collectAsState()

    PlatformBackHandler { onNavigateBack() }
    LaunchedEffect(espId) { viewModel.abrirDetalhe(espId) }
    LaunchedEffect(uiState.espForgotten) {
        if (uiState.espForgotten) onNavigateBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = dimensions.screenHorizontalPadding, vertical = dimensions.paddingMedium)
    ) {
        ScreenHeader(
            title = "ESP $espId",
            actionLabel = "Voltar",
            onAction = onNavigateBack
        )

        if (!uiState.isSupported) {
            Spacer(modifier = Modifier.height(16.dp))
            EmptyState(
                title = "Gerenciamento de ESPs indisponível na versão Web",
                message = "Use o aplicativo Android para configurar controladores ESP32."
            )
            return@Column
        }

        if (uiState.isColetaActive) {
            Spacer(modifier = Modifier.height(8.dp))
            ColetaActiveBanner()
        }

        if (uiState.infoMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = uiState.infoMessage ?: "",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        EspMetadataAndChannelsSection(espId = espId, uiState = uiState, viewModel = viewModel)
        Spacer(modifier = Modifier.height(12.dp))
        EspOperationStatusSection(espId = espId, uiState = uiState, viewModel = viewModel)
        Spacer(modifier = Modifier.height(12.dp))
        EspMappingsEditorSection(uiState = uiState, viewModel = viewModel)
        Spacer(modifier = Modifier.height(12.dp))
        EspForgetSection(espId = espId, uiState = uiState, viewModel = viewModel)
    }
}

@Composable
private fun EspMetadataAndChannelsSection(
    espId: String,
    uiState: EspManagementUiState,
    viewModel: EspManagementViewModel
) {
    val dimensions = LocalWmsDimensions.current
    val device = uiState.selectedDevice
    val firmware = device?.status?.capabilities?.firmwareVersion ?: "—"
    val protocol = when (device?.status?.capabilities?.mqttProtocol) {
        EspMqttProtocol.V5 -> "MQTT 5.0"
        EspMqttProtocol.V3_1_1 -> "MQTT 3.1.1"
        null -> "—"
    }
    val bootId = device?.config?.bootId ?: device?.status?.bootId ?: "—"
    val revision = uiState.loadedConfigRevision ?: device?.config?.configRevision ?: device?.status?.configRevision

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(dimensions.cardCornerRadius)
    ) {
        Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Metadados e canais GPIO",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (device != null) EspStatusBadge(device)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Firmware $firmware · $protocol",
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
            )
            Text(
                text = "Boot ID $bootId · Revisão ${revision?.toString() ?: "—"}",
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                color = InkMuted
            )

            if (device?.reportedStatus == EspOnlineStatus.CONFIG_INVALID) {
                Spacer(modifier = Modifier.height(6.dp))
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
                        text = EspManagementViewModel.readableErrorCode(device.errorCode),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            when (val readState = uiState.detailReadState) {
                EspDetailReadState.Idle, EspDetailReadState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(20.dp).height(20.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Lendo configuração atual do ESP...", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                is EspDetailReadState.NotRead -> {
                    Text(
                        text = readState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Edição bloqueada até a leitura da configuração atual nesta sessão.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = InkMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.lerConfiguracao(espId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = dimensions.minTouchTarget),
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text("Ler novamente", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                is EspDetailReadState.Loaded -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Configuração lida (rev ${readState.revision})",
                            color = OkTeal,
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
                        )
                        TextButton(
                            onClick = { viewModel.lerConfiguracao(espId) },
                            modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                        ) {
                            Text("Ler configuração", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            if (uiState.selectedChannels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(10.dp))
                Text("Canais anunciados", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Canal", style = MaterialTheme.typography.labelMedium, color = InkMuted)
                    Text("GPIO", style = MaterialTheme.typography.labelMedium, color = InkMuted)
                }
                Spacer(modifier = Modifier.height(4.dp))
                uiState.selectedChannels.forEachIndexed { idx, ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = ch.channelId,
                            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "GPIO ${ch.gpio} (${if (ch.activeHigh) "Active-High" else "Active-Low"})",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                            color = InkMuted
                        )
                    }
                    if (idx < uiState.selectedChannels.lastIndex) {
                        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun EspOperationStatusSection(
    espId: String,
    uiState: EspManagementUiState,
    viewModel: EspManagementViewModel
) {
    val op = uiState.opState
    if (op is EspConfigOpState.Idle) return
    val dimensions = LocalWmsDimensions.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(dimensions.cardCornerRadius)
    ) {
        Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
            when (op) {
                EspConfigOpState.Idle -> {}
                is EspConfigOpState.WaitingResult -> {
                    Text(
                        text = "Aguardando confirmação do ESP...",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                is EspConfigOpState.AppliedWaitingState -> {
                    val label = if (op.timedOutWaitingState) {
                        "Aplicado — aguardando estado (rev ${op.expectedRevision})"
                    } else {
                        "Aplicado — aguardando confirmação do estado (rev ${op.expectedRevision})..."
                    }
                    Text(
                        text = label,
                        color = WarnAmber,
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
                    )
                    if (op.diagnostics.isNotEmpty()) {
                        Text(
                            text = op.diagnostics.joinToString { it.message },
                            style = MaterialTheme.typography.bodyLarge,
                            color = WarnAmber
                        )
                    }
                    if (op.timedOutWaitingState) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.lerConfiguracao(espId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = dimensions.minTouchTarget),
                            shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text("Ler configuração", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                is EspConfigOpState.Synced -> {
                    Text(
                        text = "Sincronizado (revisão ${op.revision})",
                        color = OkTeal,
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
                    )
                    if (op.diagnostics.isNotEmpty()) {
                        Text(
                            text = op.diagnostics.joinToString { it.message },
                            style = MaterialTheme.typography.bodyLarge,
                            color = WarnAmber
                        )
                    }
                }
                is EspConfigOpState.Rejected -> {
                    Text(
                        text = "Configuração rejeitada: ${op.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (op.errorCode == EspErrorCode.REVISION_MISMATCH) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.recarregarAposConflitoRevisao() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = dimensions.minTouchTarget),
                            shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text("Recarregar configuração", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                is EspConfigOpState.Timeout -> {
                    Text(
                        text = "Timeout: o ESP não confirmou em 5 s (resultado indeterminado).",
                        color = WarnAmber,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (op.diagnostics.isNotEmpty()) {
                        Text(
                            text = op.diagnostics.joinToString { it.message },
                            style = MaterialTheme.typography.bodyLarge,
                            color = WarnAmber
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.tentarNovamenteSalvar() },
                        enabled = uiState.canEditMappings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = dimensions.minTouchTarget),
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text("Tentar de novo", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                is EspConfigOpState.Failed -> {
                    Text(
                        text = op.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EspMappingsEditorSection(
    uiState: EspManagementUiState,
    viewModel: EspManagementViewModel
) {
    val dimensions = LocalWmsDimensions.current
    var selectedKind by remember { mutableStateOf(MappingKind.POSICAO) }
    var selectedLedId by remember { mutableStateOf("") }
    var selectedChannelId by remember { mutableStateOf("") }

    val options = uiState.ledIdOptionsFor(selectedKind)
    val freeChannels = uiState.freeChannels

    LaunchedEffect(selectedKind, options, freeChannels) {
        if (options.none { it.ledId == selectedLedId && it.isAvailable }) {
            selectedLedId = options.firstOrNull { it.isAvailable }?.ledId ?: ""
        }
        if (freeChannels.none { it.channelId == selectedChannelId }) {
            selectedChannelId = freeChannels.firstOrNull()?.channelId ?: ""
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(dimensions.cardCornerRadius)
    ) {
        Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
            Text(
                text = "Mapeamentos LED e canal (${uiState.draftMappings.size}/${uiState.selectedChannels.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface
            )

            if (uiState.isChannelTableInvalid) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Edição bloqueada: tabela de canais inválida no firmware (INVALID_CHANNEL_TABLE).",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
            } else if (uiState.selectedDevice?.reportedStatus == EspOnlineStatus.CONFIG_INVALID &&
                uiState.selectedDevice?.errorCode == EspErrorCode.STORAGE_FAILURE
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Modo de recuperação NVS (STORAGE_FAILURE): salve uma configuração para restaurar o ESP.",
                    color = WarnAmber,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            if (uiState.draftMappings.isEmpty()) {
                Text(
                    text = if (uiState.configReadInSession) "Nenhum mapeamento configurado no rascunho." else "Aguardando leitura da configuração...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = InkMuted
                )
            } else {
                uiState.draftMappings.forEachIndexed { idx, mapping ->
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    MappingRowItem(
                        mapping = mapping,
                        uiState = uiState,
                        onTestClick = { viewModel.testarMapping(mapping) },
                        onRemoveClick = { viewModel.removerMapping(idx) }
                    )
                }
            }

            if (uiState.canEditMappings) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Adicionar mapeamento", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MappingKind.entries.forEach { kind ->
                        val selected = selectedKind == kind
                        Button(
                            onClick = { selectedKind = kind },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = dimensions.minTouchTarget),
                            shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                            border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                contentColor = if (selected) OnStrong else MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text(kind.toUiLabel(), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("1. Selecione o ID do LED", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(4.dp))
                options.forEach { opt ->
                    val isSelected = selectedLedId == opt.ledId
                    val reason = when {
                        opt.ownerOtherEspId != null -> " — Indisponível (mapeado em ${opt.ownerOtherEspId})"
                        opt.alreadyInDraft -> " — Já incluído no rascunho"
                        else -> ""
                    }
                    Button(
                        onClick = { selectedLedId = opt.ledId },
                        enabled = opt.isAvailable,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .defaultMinSize(minHeight = dimensions.minTouchTarget),
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            contentColor = if (isSelected) OnStrong else MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(
                            text = "${opt.label}$reason",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("2. Selecione um canal GPIO livre", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(4.dp))
                if (freeChannels.isEmpty()) {
                    Text("Todos os canais deste ESP já estão em uso no rascunho.", color = WarnAmber, style = MaterialTheme.typography.bodyLarge)
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        freeChannels.forEach { ch ->
                            val isSelected = selectedChannelId == ch.channelId
                            Button(
                                onClick = { selectedChannelId = ch.channelId },
                                modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                                shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    contentColor = if (isSelected) OnStrong else MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text(
                                    text = "${ch.channelId} (G${ch.gpio})",
                                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { viewModel.adicionarMapping(selectedKind, selectedLedId, selectedChannelId) },
                    enabled = uiState.canAddMoreMappings && selectedLedId.isNotBlank() && selectedChannelId.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Adicionar ao rascunho", color = MaterialTheme.colorScheme.onSurface)
                }
            }

            if (uiState.violations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                uiState.violations.forEach { v ->
                    Text(
                        text = "• ${EspManagementViewModel.readableViolation(v)}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            PrimaryButton(
                text = if (uiState.isSaving) "Salvando configuração..." else "Salvar configuração",
                onClick = { viewModel.salvarConfig() },
                enabled = uiState.canSave,
                isLoading = uiState.isSaving
            )
        }
    }
}

@Composable
private fun MappingRowItem(
    mapping: EspMapping,
    uiState: EspManagementUiState,
    onTestClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    val dimensions = LocalWmsDimensions.current
    val gpio = uiState.selectedChannels.find { it.channelId == mapping.channelId }?.gpio
    val rowViolations = uiState.violations.filter { it.ledId == mapping.ledId || it.channelId == mapping.channelId }
    val testFeedback = uiState.ledTestFeedbackByLedId[mapping.ledId]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${mapping.channelId}${gpio?.let { " (GPIO $it)" } ?: ""}",
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${mapping.kind.toUiLabel()} · ${mapping.ledId}",
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                color = InkMuted
            )
        }
        rowViolations.forEach { v ->
            Text(
                text = EspManagementViewModel.readableViolation(v),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        if (testFeedback != null) {
            val color = when {
                testFeedback == "LED confirmado" -> OkTeal
                testFeedback.startsWith("Erro") -> MaterialTheme.colorScheme.error
                else -> WarnAmber
            }
            Text(testFeedback, color = color, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onTestClick,
                enabled = uiState.canTestLed,
                modifier = Modifier.weight(1f).defaultMinSize(minHeight = dimensions.minTouchTarget),
                shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text("Testar LED", color = MaterialTheme.colorScheme.onSurface)
            }
            OutlinedButton(
                onClick = onRemoveClick,
                enabled = uiState.canEditMappings,
                modifier = Modifier.weight(1f).defaultMinSize(minHeight = dimensions.minTouchTarget),
                shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text("Remover", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private fun MappingKind.toUiLabel(): String = when (this) {
    MappingKind.ESTANTE -> "Estante"
    MappingKind.PRATELEIRA -> "Prateleira"
    MappingKind.POSICAO -> "Posição"
}

@Composable
private fun EspForgetSection(
    espId: String,
    uiState: EspManagementUiState,
    viewModel: EspManagementViewModel
) {
    val dimensions = LocalWmsDimensions.current
    var showConfirmDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(dimensions.cardCornerRadius)
    ) {
        Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
            Text(
                text = "Remover controlador",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            uiState.forgetDisabledReason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyLarge,
                    color = InkMuted
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            uiState.forgetErrorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = { showConfirmDialog = true },
                enabled = uiState.canForgetEsp,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = dimensions.minTouchTarget),
                shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = if (uiState.isForgetting) "Esquecendo ESP..." else "Esquecer ESP",
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Esquecer $espId?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    text = "Esquecer $espId? Ele sai deste celular e as mensagens retidas dele são apagadas do broker. Se ligar de novo, reaparece só com os dados que publicar.",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        viewModel.esquecerEspAtual()
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = OnStrong
                    )
                ) {
                    Text("Esquecer ESP")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showConfirmDialog = false },
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





