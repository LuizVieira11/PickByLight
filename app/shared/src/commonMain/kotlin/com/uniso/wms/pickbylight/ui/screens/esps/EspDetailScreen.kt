package com.uniso.wms.pickbylight.ui.screens.esps

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.uniso.wms.pickbylight.ui.theme.AmberWarning
import com.uniso.wms.pickbylight.ui.theme.CoralError
import com.uniso.wms.pickbylight.ui.theme.EmeraldSuccess
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.ShelfRackBlue
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.PlatformBackHandler

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
            .padding(dimensions.paddingMedium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ESP: $espId",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onNavigateBack, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text("Voltar", fontSize = 16.sp)
            }
        }

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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ShelfRackBlue, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(uiState.infoMessage ?: "", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Metadados e Canais GPIO", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (device != null) EspStatusBadge(device)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Firmware: $firmware • Protocolo: $protocol", fontSize = 16.sp)
            Text("Boot ID: $bootId • Revisão: ${revision?.toString() ?: "—"}", fontSize = 16.sp)

            if (device?.reportedStatus == EspOnlineStatus.CONFIG_INVALID) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = EspManagementViewModel.readableErrorCode(device.errorCode),
                    color = CoralError,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            when (val readState = uiState.detailReadState) {
                EspDetailReadState.Idle, EspDetailReadState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), color = ShelfRackBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Lendo configuração atual do ESP...", fontSize = 16.sp)
                    }
                }
                is EspDetailReadState.NotRead -> {
                    Text(readState.message, color = CoralError, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Edição bloqueada até a leitura da configuração atual nesta sessão.",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    PrimaryButton(
                        text = "Ler novamente",
                        onClick = { viewModel.lerConfiguracao(espId) },
                        containerColor = ShelfRackBlue
                    )
                }
                is EspDetailReadState.Loaded -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Configuração lida (rev ${readState.revision})", color = EmeraldSuccess, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = { viewModel.lerConfiguracao(espId) },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("Ler configuração", fontSize = 16.sp)
                        }
                    }
                }
            }

            if (uiState.selectedChannels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text("Canais Anunciados (somente leitura):", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                uiState.selectedChannels.forEach { ch ->
                    Text(
                        text = "• ${ch.channelId} → GPIO ${ch.gpio} (${if (ch.activeHigh) "Active-High" else "Active-Low"})",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
            when (op) {
                EspConfigOpState.Idle -> {}
                is EspConfigOpState.WaitingResult -> {
                    Text("Aguardando confirmação do ESP...", color = ShelfRackBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                is EspConfigOpState.AppliedWaitingState -> {
                    val label = if (op.timedOutWaitingState) {
                        "Aplicado — aguardando estado (rev ${op.expectedRevision})"
                    } else {
                        "Aplicado — aguardando confirmação do estado (rev ${op.expectedRevision})..."
                    }
                    Text(label, color = AmberWarning, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (op.diagnostics.isNotEmpty()) {
                        Text(op.diagnostics.joinToString { it.message }, fontSize = 16.sp, color = AmberWarning)
                    }
                    if (op.timedOutWaitingState) {
                        Spacer(modifier = Modifier.height(8.dp))
                        PrimaryButton(
                            text = "Ler configuração",
                            onClick = { viewModel.lerConfiguracao(espId) },
                            containerColor = ShelfRackBlue
                        )
                    }
                }
                is EspConfigOpState.Synced -> {
                    Text("Sincronizado (revisão ${op.revision})", color = EmeraldSuccess, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (op.diagnostics.isNotEmpty()) {
                        Text(op.diagnostics.joinToString { it.message }, fontSize = 16.sp, color = AmberWarning)
                    }
                }
                is EspConfigOpState.Rejected -> {
                    Text("Configuração rejeitada: ${op.message}", color = CoralError, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (op.errorCode == EspErrorCode.REVISION_MISMATCH) {
                        Spacer(modifier = Modifier.height(8.dp))
                        PrimaryButton(
                            text = "Recarregar configuração",
                            onClick = { viewModel.recarregarAposConflitoRevisao() },
                            containerColor = ShelfRackBlue
                        )
                    }
                }
                is EspConfigOpState.Timeout -> {
                    Text(
                        text = "Timeout: o ESP não confirmou em 5 s (resultado indeterminado).",
                        color = AmberWarning,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (op.diagnostics.isNotEmpty()) {
                        Text(op.diagnostics.joinToString { it.message }, fontSize = 16.sp, color = AmberWarning)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    PrimaryButton(
                        text = "Tentar de novo",
                        onClick = { viewModel.tentarNovamenteSalvar() },
                        enabled = uiState.canEditMappings,
                        containerColor = ShelfRackBlue
                    )
                }
                is EspConfigOpState.Failed -> {
                    Text(op.message, color = CoralError, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

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
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
            Text(
                text = "Mapeamentos LED ↔ Canal GPIO (${uiState.draftMappings.size}/${uiState.selectedChannels.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (uiState.isChannelTableInvalid) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Edição bloqueada: tabela de canais inválida no firmware (INVALID_CHANNEL_TABLE).",
                    color = CoralError,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            } else if (uiState.selectedDevice?.reportedStatus == EspOnlineStatus.CONFIG_INVALID &&
                uiState.selectedDevice?.errorCode == EspErrorCode.STORAGE_FAILURE
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Modo de recuperação NVS (STORAGE_FAILURE): salve uma configuração para restaurar o ESP.",
                    color = AmberWarning,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            if (uiState.draftMappings.isEmpty()) {
                Text(
                    text = if (uiState.configReadInSession) "Nenhum mapeamento configurado no rascunho." else "Aguardando leitura da configuração...",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                uiState.draftMappings.forEachIndexed { idx, mapping ->
                    MappingRowItem(
                        mapping = mapping,
                        uiState = uiState,
                        onTestClick = { viewModel.testarMapping(mapping) },
                        onRemoveClick = { viewModel.removerMapping(idx) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (uiState.canEditMappings) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Adicionar Mapeamento", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MappingKind.entries.forEach { kind ->
                        val selected = selectedKind == kind
                        Button(
                            onClick = { selectedKind = kind },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) ShelfRackBlue else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(kind.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("1. Selecione o ID do LED (${selectedKind.name}):", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                            .defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) EmeraldSuccess else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text("${opt.label}$reason", fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("2. Selecione um Canal GPIO Livre:", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                if (freeChannels.isEmpty()) {
                    Text("Todos os canais deste ESP já estão em uso no rascunho.", color = AmberWarning, fontSize = 16.sp)
                } else {
                    freeChannels.chunked(3).forEach { chunk ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            chunk.forEach { ch ->
                                val isSelected = selectedChannelId == ch.channelId
                                Button(
                                    onClick = { selectedChannelId = ch.channelId },
                                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) ShelfRackBlue else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                    )
                                ) {
                                    Text("${ch.channelId} (G${ch.gpio})", fontSize = 16.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                PrimaryButton(
                    text = "Adicionar ao Rascunho",
                    onClick = { viewModel.adicionarMapping(selectedKind, selectedLedId, selectedChannelId) },
                    enabled = uiState.canAddMoreMappings && selectedLedId.isNotBlank() && selectedChannelId.isNotBlank(),
                    containerColor = ShelfRackBlue
                )
            }

            if (uiState.violations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                uiState.violations.forEach { v ->
                    Text(
                        text = "• ${EspManagementViewModel.readableViolation(v)}",
                        color = CoralError,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            PrimaryButton(
                text = if (uiState.isSaving) "Salvando Configuração..." else "Salvar Configuração",
                onClick = { viewModel.salvarConfig() },
                enabled = uiState.canSave,
                isLoading = uiState.isSaving,
                containerColor = EmeraldSuccess
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
    val gpio = uiState.selectedChannels.find { it.channelId == mapping.channelId }?.gpio
    val rowViolations = uiState.violations.filter { it.ledId == mapping.ledId || it.channelId == mapping.channelId }
    val testFeedback = uiState.ledTestFeedbackByLedId[mapping.ledId]

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${mapping.channelId}${gpio?.let { " (GPIO $it)" } ?: ""} → [${mapping.kind.name}] ${mapping.ledId}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            rowViolations.forEach { v ->
                Text(
                    text = EspManagementViewModel.readableViolation(v),
                    color = CoralError,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (testFeedback != null) {
                val color = when {
                    testFeedback == "LED confirmado" -> EmeraldSuccess
                    testFeedback.startsWith("Erro") -> CoralError
                    else -> AmberWarning
                }
                Text(testFeedback, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onTestClick,
                    enabled = uiState.canTestLed,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ShelfRackBlue, contentColor = Color.White)
                ) {
                    Text("Testar LED", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onRemoveClick,
                    enabled = uiState.canEditMappings,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CoralError, contentColor = Color.White)
                ) {
                    Text("Remover", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
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
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
            Text(
                text = "Remover Controlador",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            uiState.forgetDisabledReason?.let { reason ->
                Text(
                    text = reason,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            uiState.forgetErrorMessage?.let { error ->
                Text(
                    text = error,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CoralError
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Button(
                onClick = { showConfirmDialog = true },
                enabled = uiState.canForgetEsp,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CoralError,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (uiState.isForgetting) "Esquecendo ESP..." else "Esquecer ESP",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Esquecer $espId?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Esquecer $espId? Ele sai deste celular e as mensagens retidas dele são apagadas do broker. Se ligar de novo, reaparece só com os dados que publicar.",
                    fontSize = 16.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        viewModel.esquecerEspAtual()
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CoralError, contentColor = Color.White)
                ) {
                    Text("Esquecer ESP", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("Cancelar", fontSize = 16.sp)
                }
            }
        )
    }
}





