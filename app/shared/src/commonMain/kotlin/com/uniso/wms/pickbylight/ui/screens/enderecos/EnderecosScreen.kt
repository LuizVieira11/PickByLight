package com.uniso.wms.pickbylight.ui.screens.enderecos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.nfc.NfcManager
import com.uniso.wms.pickbylight.ui.components.EmptyState
import com.uniso.wms.pickbylight.ui.components.ErrorState
import com.uniso.wms.pickbylight.ui.components.LoadingState
import com.uniso.wms.pickbylight.ui.components.NfcSimulatorDialog
import com.uniso.wms.pickbylight.ui.components.PrimaryButton
import com.uniso.wms.pickbylight.ui.components.ScreenHeader
import com.uniso.wms.pickbylight.ui.theme.InkMuted
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.OkTeal
import com.uniso.wms.pickbylight.ui.theme.WarnAmber
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import pickbylight.app.shared.generated.resources.Res
import pickbylight.app.shared.generated.resources.ic_add
import pickbylight.app.shared.generated.resources.ic_error
import pickbylight.app.shared.generated.resources.ic_expand_less
import pickbylight.app.shared.generated.resources.ic_expand_more
import pickbylight.app.shared.generated.resources.ic_inventory_2
import pickbylight.app.shared.generated.resources.ic_lightbulb
import pickbylight.app.shared.generated.resources.ic_nfc
import pickbylight.app.shared.generated.resources.ic_shelves
import pickbylight.app.shared.generated.resources.ic_table_rows

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnderecosScreen(
    repository: WmsRepository,
    mqttService: MqttService,
    feedbackController: FeedbackController,
    initialTagUid: String? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EnderecosViewModel = viewModel {
        EnderecosViewModel(repository, mqttService, feedbackController)
    }
) {
    val dimensions = LocalWmsDimensions.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filtroNfc by viewModel.filtroNfc.collectAsState()

    // Modais de Criação em Cascata (Seção 3.2)
    var showModalNovaEstante by remember { mutableStateOf(false) }
    var selectedEstanteForPrateleira by remember { mutableStateOf<Estante?>(null) }
    var selectedPrateleiraForPosicao by remember { mutableStateOf<Prateleira?>(null) }

    // Modal de Vinculação NFC (Fonte única de verdade TagNFC)
    var posicaoParaVincularNfc by remember { mutableStateOf<Posicao?>(null) }
    var showNfcSimulatorForBinding by remember { mutableStateOf(false) }
    var bindingTagUidInput by remember { mutableStateOf(initialTagUid ?: "") }
    var bindingErrorMessage by remember { mutableStateOf<String?>(null) }

    // Para o simulador no modal de vinculação
    val allPosicoes by repository.getPosicoes().collectAsState(initial = emptyList())
    val allTags by repository.getTagsNFC().collectAsState(initial = emptyList())

    PlatformBackHandler {
        onNavigateBack()
    }

    // Escuta leitura física de NFC quando o modal de vinculação estiver em foco
    LaunchedEffect(posicaoParaVincularNfc) {
        if (posicaoParaVincularNfc != null) {
            NfcManager.scannedTagFlow.collectLatest { uid ->
                bindingTagUidInput = uid
                bindingErrorMessage = null
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = dimensions.screenHorizontalPadding, vertical = dimensions.paddingMedium)
        ) {
            // Cabeçalho da Tela
            ScreenHeader(
                title = "Endereços",
                actionLabel = "Voltar",
                onAction = onNavigateBack
            )

            if (!initialTagUid.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(dimensions.cardCornerRadius),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, WarnAmber),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_nfc),
                            contentDescription = null, // Decorativo: o texto ao lado informa o UID NFC lido
                            tint = WarnAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "UID lido pronto para vincular: $initialTagUid. Selecione uma posição abaixo.",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                            color = WarnAmber
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (val state = uiState) {
                is EnderecosUiState.Loading -> {
                    LoadingState(message = "Carregando endereços do armazém...")
                }

                is EnderecosUiState.Empty -> {
                    EmptyState(
                        title = "Nenhum endereço cadastrado",
                        message = "Cadastre a primeira estante física para iniciar o mapeamento do armazém.",
                        actionLabel = "Cadastrar nova estante",
                        actionLeadingIcon = {
                            Icon(
                                painter = painterResource(Res.drawable.ic_add),
                                contentDescription = null, // Decorativo: o rótulo ao lado descreve a ação
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        onActionClick = { showModalNovaEstante = true }
                    )
                }

                is EnderecosUiState.Error -> {
                    ErrorState(
                        title = "Erro ao carregar endereços",
                        message = state.message,
                        onRetry = { viewModel.setSearchQuery("") }
                    )
                }

                is EnderecosUiState.Success -> {
                    // Busca, filtros e "Nova estante" rolam junto com a lista (LazyColumn)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item(key = "header_controls") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { viewModel.setSearchQuery(it) },
                                    label = { Text("Buscar por código, estante, posição ou UID...") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    FilterChip(
                                        selected = state.filtroNfc == FiltroNfc.TODOS,
                                        onClick = { viewModel.setFiltroNfc(FiltroNfc.TODOS) },
                                        label = {
                                            Text(
                                                text = "Todos (${state.totalPosicoes})",
                                                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
                                            )
                                        }
                                    )
                                    FilterChip(
                                        selected = state.filtroNfc == FiltroNfc.VINCULADO,
                                        onClick = { viewModel.setFiltroNfc(FiltroNfc.VINCULADO) },
                                        label = {
                                            Text(
                                                text = "Vinculado (${state.posicoesVinculadasCount})",
                                                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
                                            )
                                        }
                                    )
                                    FilterChip(
                                        selected = state.filtroNfc == FiltroNfc.PENDENTE,
                                        onClick = { viewModel.setFiltroNfc(FiltroNfc.PENDENTE) },
                                        label = {
                                            Text(
                                                text = "Pendente (${state.posicoesPendentesCount})",
                                                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
                                            )
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                PrimaryButton(
                                    text = "Nova estante",
                                    onClick = { showModalNovaEstante = true },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_add),
                                            contentDescription = null, // Decorativo: o rótulo "Nova estante" descreve a ação
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                )
                            }
                        }

                        items(state.estantes, key = { it.estante.id }) { estanteItem ->
                            val estante = estanteItem.estante

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                shape = RoundedCornerShape(dimensions.cardCornerRadius)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    // NÍVEL 1: Estante (recuo 0 dp)
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = dimensions.paddingMedium, vertical = 12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .defaultMinSize(minHeight = dimensions.minTouchTarget)
                                                .clickable { viewModel.toggleEstanteExpanded(estante.id) },
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                painter = painterResource(Res.drawable.ic_shelves),
                                                contentDescription = null, // Decorativo: o texto "Estante ..." identifica o nível
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Estante ${estante.codigo} — ${estante.nome}",
                                                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "LED ${estante.ledId} · ${estante.setor}",
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                    color = InkMuted
                                                )
                                            }
                                            IconButton(
                                                onClick = { viewModel.toggleEstanteExpanded(estante.id) },
                                                modifier = Modifier.size(dimensions.minTouchTarget)
                                            ) {
                                                Icon(
                                                    painter = painterResource(if (estanteItem.isExpanded) Res.drawable.ic_expand_less else Res.drawable.ic_expand_more),
                                                    contentDescription = if (estanteItem.isExpanded) "Ocultar estante" else "Expandir estante",
                                                    tint = InkMuted,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }

                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        val res = viewModel.testarLed(estante.ledId, null, null)
                                                        if (res.isSuccess) {
                                                            snackbarHostState.showSnackbar("TESTE_LED enviado para estante ${estante.codigo}")
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                                            ) {
                                                Icon(
                                                    painter = painterResource(Res.drawable.ic_lightbulb),
                                                    contentDescription = null, // Decorativo: o texto "Testar LED" descreve a ação
                                                    tint = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Testar LED", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                                            }

                                            TextButton(
                                                onClick = { selectedEstanteForPrateleira = estante },
                                                modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                                            ) {
                                                Icon(
                                                    painter = painterResource(Res.drawable.ic_add),
                                                    contentDescription = null, // Decorativo: o texto "Prateleira" descreve a ação
                                                    tint = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Prateleira", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                                            }
                                        }
                                    }

                                    // NÍVEL 2: Prateleiras dentro do mesmo card (recuo 16 dp)
                                    AnimatedVisibility(visible = estanteItem.isExpanded) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            if (estanteItem.prateleiras.isEmpty()) {
                                                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                                Text(
                                                    text = "Nenhuma prateleira para este filtro.",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = InkMuted,
                                                    modifier = Modifier.padding(
                                                        start = dimensions.paddingMedium + 16.dp,
                                                        end = dimensions.paddingMedium,
                                                        top = 12.dp,
                                                        bottom = 12.dp
                                                    )
                                                )
                                            }

                                            estanteItem.prateleiras.forEach { pratItem ->
                                                val prateleira = pratItem.prateleira

                                                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(
                                                            start = dimensions.paddingMedium + 16.dp,
                                                            end = dimensions.paddingMedium,
                                                            top = 12.dp,
                                                            bottom = 8.dp
                                                        )
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .defaultMinSize(minHeight = dimensions.minTouchTarget)
                                                            .clickable { viewModel.togglePrateleiraExpanded(prateleira.id) },
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(Res.drawable.ic_table_rows),
                                                            contentDescription = null, // Decorativo: o texto "Prateleira ..." identifica o nível
                                                            tint = MaterialTheme.colorScheme.onSurface,
                                                            modifier = Modifier.size(24.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = "Prateleira ${prateleira.codigo}",
                                                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                            Text(
                                                                text = "LED ponta ${prateleira.ledIdPonta}",
                                                                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                                color = InkMuted
                                                            )
                                                        }
                                                        IconButton(
                                                            onClick = { viewModel.togglePrateleiraExpanded(prateleira.id) },
                                                            modifier = Modifier.size(dimensions.minTouchTarget)
                                                        ) {
                                                            Icon(
                                                                painter = painterResource(if (pratItem.isExpanded) Res.drawable.ic_expand_less else Res.drawable.ic_expand_more),
                                                                contentDescription = if (pratItem.isExpanded) "Ocultar prateleira" else "Expandir prateleira",
                                                                tint = InkMuted,
                                                                modifier = Modifier.size(24.dp)
                                                            )
                                                        }
                                                    }

                                                    FlowRow(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        TextButton(
                                                            onClick = {
                                                                coroutineScope.launch {
                                                                    val res = viewModel.testarLed(estante.ledId, prateleira.ledIdPonta, null)
                                                                    if (res.isSuccess) {
                                                                        snackbarHostState.showSnackbar("TESTE_LED enviado para prateleira ${prateleira.codigo}")
                                                                    }
                                                                }
                                                            },
                                                            modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                                                        ) {
                                                            Icon(
                                                                painter = painterResource(Res.drawable.ic_lightbulb),
                                                                contentDescription = null, // Decorativo: o texto "Testar LED" descreve a ação
                                                                tint = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text("Testar LED", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                                                        }

                                                        TextButton(
                                                            onClick = { selectedPrateleiraForPosicao = prateleira },
                                                            modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                                                        ) {
                                                            Icon(
                                                                painter = painterResource(Res.drawable.ic_add),
                                                                contentDescription = null, // Decorativo: o texto "Posição" descreve a ação
                                                                tint = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text("Posição", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                                                        }
                                                    }
                                                }

                                                // NÍVEL 3: Posições dentro do mesmo card (recuo 32 dp)
                                                AnimatedVisibility(visible = pratItem.isExpanded) {
                                                    Column(modifier = Modifier.fillMaxWidth()) {
                                                        if (pratItem.posicoes.isEmpty()) {
                                                            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                                            Text(
                                                                text = "Nenhuma posição para o filtro.",
                                                                style = MaterialTheme.typography.bodyLarge,
                                                                color = InkMuted,
                                                                modifier = Modifier.padding(
                                                                    start = dimensions.paddingMedium + 32.dp,
                                                                    end = dimensions.paddingMedium,
                                                                    top = 10.dp,
                                                                    bottom = 10.dp
                                                                )
                                                            )
                                                        }

                                                        pratItem.posicoes.forEach { posItem ->
                                                            val posicao = posItem.posicao
                                                            val tag = posItem.tagNfc
                                                            val isVinculado = posItem.isVinculado

                                                            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

                                                            Column(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(
                                                                        start = dimensions.paddingMedium + 32.dp,
                                                                        end = dimensions.paddingMedium,
                                                                        top = 12.dp,
                                                                        bottom = 8.dp
                                                                    )
                                                            ) {
                                                                FlowRow(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                                                    itemVerticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                                        Icon(
                                                                            painter = painterResource(Res.drawable.ic_inventory_2),
                                                                            contentDescription = null, // Decorativo: o texto "Posição ..." identifica o nível
                                                                            tint = MaterialTheme.colorScheme.onSurface,
                                                                            modifier = Modifier.size(24.dp)
                                                                        )
                                                                        Spacer(modifier = Modifier.width(10.dp))
                                                                        Text(
                                                                            text = "Posição ${posicao.codigo}",
                                                                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                                                            color = MaterialTheme.colorScheme.onSurface
                                                                        )
                                                                    }

                                                                    val statusColor = if (isVinculado) OkTeal else WarnAmber
                                                                    Surface(
                                                                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                                                                        color = MaterialTheme.colorScheme.surface,
                                                                        border = BorderStroke(1.dp, statusColor)
                                                                    ) {
                                                                        Row(
                                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                                            verticalAlignment = Alignment.CenterVertically,
                                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                                        ) {
                                                                            Icon(
                                                                                painter = painterResource(Res.drawable.ic_nfc),
                                                                                contentDescription = null, // Decorativo: o rótulo ao lado informa o status NFC
                                                                                tint = statusColor,
                                                                                modifier = Modifier.size(18.dp)
                                                                            )
                                                                            Text(
                                                                                text = if (isVinculado) "Vinculado" else "NFC pendente",
                                                                                style = MaterialTheme.typography.labelMedium,
                                                                                color = statusColor
                                                                            )
                                                                        }
                                                                    }
                                                                            }

                                                                Spacer(modifier = Modifier.height(6.dp))

                                                                Column(modifier = Modifier.padding(start = 34.dp)) {
                                                                    Text(
                                                                        text = "LED ${posicao.ledId} · ${posicao.limitesLap}",
                                                                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                                        color = InkMuted
                                                                    )
                                                                    if (isVinculado && tag != null) {
                                                                        Text(
                                                                            text = "UID ${tag.uid}",
                                                                            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                                            color = InkMuted
                                                                        )
                                                                    }
                                                                }

                                                                FlowRow(
                                                                    modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .padding(start = 26.dp),
                                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                                ) {
                                                                    TextButton(
                                                                        onClick = {
                                                                            coroutineScope.launch {
                                                                                val res = viewModel.testarLed(estante.ledId, prateleira.ledIdPonta, posicao.ledId)
                                                                                if (res.isSuccess) {
                                                                                    snackbarHostState.showSnackbar("TESTE_LED enviado para posição ${posicao.codigo}")
                                                                                }
                                                                            }
                                                                        },
                                                                        modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                                                                    ) {
                                                                        Icon(
                                                                            painter = painterResource(Res.drawable.ic_lightbulb),
                                                                            contentDescription = null, // Decorativo: o texto "Testar LED" descreve a ação
                                                                            tint = MaterialTheme.colorScheme.onSurface,
                                                                            modifier = Modifier.size(20.dp)
                                                                        )
                                                                        Spacer(modifier = Modifier.width(6.dp))
                                                                        Text("Testar LED", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                                                                    }

                                                                    TextButton(
                                                                        onClick = {
                                                                            posicaoParaVincularNfc = posicao
                                                                            bindingTagUidInput = tag?.uid ?: initialTagUid ?: ""
                                                                            bindingErrorMessage = null
                                                                        },
                                                                        modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                                                                    ) {
                                                                        Icon(
                                                                            painter = painterResource(Res.drawable.ic_nfc),
                                                                            contentDescription = null, // Decorativo: o texto ao lado descreve a ação
                                                                            tint = MaterialTheme.colorScheme.onSurface,
                                                                            modifier = Modifier.size(20.dp)
                                                                        )
                                                                        Spacer(modifier = Modifier.width(6.dp))
                                                                        Text(
                                                                            text = if (isVinculado) "Alterar tag" else "Vincular tag",
                                                                            style = MaterialTheme.typography.labelLarge,
                                                                            color = MaterialTheme.colorScheme.onSurface
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    // MODAL: Nova Estante (Seção 3.2.1)
    if (showModalNovaEstante) {
        var codEstante by remember { mutableStateOf("") }
        var nomeEstante by remember { mutableStateOf("") }
        var setorEstante by remember { mutableStateOf("Geral") }
        var ledIdEstante by remember { mutableStateOf("") }
        var erroEstante by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showModalNovaEstante = false },
            title = { Text("Cadastrar nova estante", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = codEstante,
                        onValueChange = { codEstante = it.uppercase() },
                        label = { Text("Código (ex: EST-C)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = nomeEstante,
                        onValueChange = { nomeEstante = it },
                        label = { Text("Nome da estante") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = setorEstante,
                        onValueChange = { setorEstante = it },
                        label = { Text("Setor / descrição") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ledIdEstante,
                        onValueChange = { ledIdEstante = it.uppercase() },
                        label = { Text("ID do LED (sinalizador superior)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (erroEstante != null) {
                        ModalValidationError(erroEstante ?: "")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (codEstante.isBlank() || nomeEstante.isBlank() || ledIdEstante.isBlank()) {
                            erroEstante = "Preencha todos os campos obrigatórios."
                            return@Button
                        }
                        coroutineScope.launch {
                            val res = viewModel.inserirEstante(
                                codigo = codEstante,
                                nome = nomeEstante,
                                setor = setorEstante,
                                ledId = ledIdEstante
                            )
                            if (res.isSuccess) {
                                showModalNovaEstante = false
                                snackbarHostState.showSnackbar("Estante $codEstante salva com sucesso!")
                            } else {
                                erroEstante = res.exceptionOrNull()?.message
                            }
                        }
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Salvar estante")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showModalNovaEstante = false },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Cancelar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // MODAL: Nova Prateleira (Seção 3.2.2)
    val estanteSelecionada = selectedEstanteForPrateleira
    if (estanteSelecionada != null) {
        var codPrat by remember { mutableStateOf("") }
        var ledIdPonta by remember { mutableStateOf("") }
        var erroPrat by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { selectedEstanteForPrateleira = null },
            title = {
                Column {
                    Text("Nova prateleira", style = MaterialTheme.typography.titleLarge)
                    Text("Estante: ${estanteSelecionada.codigo} — ${estanteSelecionada.nome}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = codPrat,
                        onValueChange = { codPrat = it.uppercase() },
                        label = { Text("Código da prateleira (ex: PRAT-A3)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ledIdPonta,
                        onValueChange = { ledIdPonta = it.uppercase() },
                        label = { Text("ID do LED de ponta (ex: PRAT_A3_LED1)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                if (ledIdPonta.isNotBlank()) {
                                    coroutineScope.launch {
                                        viewModel.testarLed(estanteSelecionada.ledId, ledIdPonta, null)
                                    }
                                }
                            },
                            modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_lightbulb),
                                contentDescription = null, // Decorativo: o texto "Testar LED" descreve a ação
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Testar LED", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    if (erroPrat != null) {
                        ModalValidationError(erroPrat ?: "")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (codPrat.isBlank() || ledIdPonta.isBlank()) {
                            erroPrat = "Preencha todos os campos obrigatórios."
                            return@Button
                        }
                        coroutineScope.launch {
                            val res = viewModel.inserirPrateleira(
                                estanteId = estanteSelecionada.id,
                                codigo = codPrat,
                                ledIdPonta = ledIdPonta
                            )
                            if (res.isSuccess) {
                                selectedEstanteForPrateleira = null
                                snackbarHostState.showSnackbar("Prateleira $codPrat salva com sucesso!")
                            } else {
                                erroPrat = res.exceptionOrNull()?.message
                            }
                        }
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Salvar prateleira")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { selectedEstanteForPrateleira = null },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Cancelar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // MODAL: Nova Posição (Seção 3.2.3 com validação regex inline de limitesLap)
    val prateleiraSelecionada = selectedPrateleiraForPosicao
    if (prateleiraSelecionada != null) {
        var codPosicao by remember { mutableStateOf("") }
        var limitesLap by remember { mutableStateOf("30x20x40cm") }
        var ledIdPosicao by remember { mutableStateOf("") }
        var erroPosicao by remember { mutableStateOf<String?>(null) }
        val isLapValido = Posicao.validarLimitesLap(limitesLap.trim())

        AlertDialog(
            onDismissRequest = { selectedPrateleiraForPosicao = null },
            title = {
                Column {
                    Text("Nova posição de armazenagem", style = MaterialTheme.typography.titleLarge)
                    Text("Prateleira: ${prateleiraSelecionada.codigo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = codPosicao,
                        onValueChange = { codPosicao = it.uppercase() },
                        label = { Text("Código da posição (ex: POS_A3_01)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = limitesLap,
                        onValueChange = { limitesLap = it },
                        label = { Text("Limites L×A×P") },
                        supportingText = {
                            if (!isLapValido && limitesLap.isNotBlank()) {
                                ModalValidationError("Formato esperado: 30x20x40cm ou 150x80x200mm")
                            } else {
                                Text("Ex: 30x20x40cm", color = InkMuted)
                            }
                        },
                        isError = !isLapValido && limitesLap.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ledIdPosicao,
                        onValueChange = { ledIdPosicao = it.uppercase() },
                        label = { Text("ID do LED posicional") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                if (ledIdPosicao.isNotBlank()) {
                                    coroutineScope.launch {
                                        viewModel.testarLed("EST_A", prateleiraSelecionada.ledIdPonta, ledIdPosicao)
                                    }
                                }
                            },
                            modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_lightbulb),
                                contentDescription = null, // Decorativo: o texto "Testar LED" descreve a ação
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Testar LED", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    if (erroPosicao != null) {
                        ModalValidationError(erroPosicao ?: "")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (codPosicao.isBlank() || ledIdPosicao.isBlank()) {
                            erroPosicao = "Preencha todos os campos obrigatórios."
                            return@Button
                        }
                        if (!isLapValido) {
                            erroPosicao = "Formato esperado: 30x20x40cm ou 150x80x200mm"
                            return@Button
                        }
                        coroutineScope.launch {
                            val res = viewModel.inserirPosicao(
                                prateleiraId = prateleiraSelecionada.id,
                                codigo = codPosicao,
                                limitesLap = limitesLap,
                                ledId = ledIdPosicao
                            )
                            if (res.isSuccess) {
                                selectedPrateleiraForPosicao = null
                                snackbarHostState.showSnackbar("Posição $codPosicao salva com sucesso!")
                            } else {
                                erroPosicao = res.exceptionOrNull()?.message
                            }
                        }
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Salvar posição")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { selectedPrateleiraForPosicao = null },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Cancelar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // MODAL: Vincular / Alterar Etiqueta NFC (Fonte única de verdade: TagNFC)
    val posVinculando = posicaoParaVincularNfc
    if (posVinculando != null) {
        AlertDialog(
            onDismissRequest = { posicaoParaVincularNfc = null },
            title = {
                Text("Vincular etiqueta NFC", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Posição: ${posVinculando.codigo}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text("Aproxime a traseira do celular da etiqueta física (NTAG213) ou utilize o simulador abaixo.", style = MaterialTheme.typography.bodySmall)

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = bindingTagUidInput,
                        onValueChange = {
                            bindingTagUidInput = it.uppercase()
                            bindingErrorMessage = null
                        },
                        label = { Text("UID da tag NFC (7 bytes hex)") },
                        placeholder = { Text("04:A1:2B:3C:4D:5E:01") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (bindingErrorMessage != null) {
                        ModalValidationError(bindingErrorMessage ?: "")
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedButton(
                        onClick = { showNfcSimulatorForBinding = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = dimensions.minTouchTarget),
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_nfc),
                            contentDescription = null, // Decorativo: o texto ao lado descreve a ação
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Simular NFC", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (bindingTagUidInput.isNotBlank()) {
                            coroutineScope.launch {
                                val res = viewModel.vincularTag(posVinculando.id, bindingTagUidInput)
                                if (res.isSuccess) {
                                    snackbarHostState.showSnackbar("Tag ${bindingTagUidInput.trim().uppercase()} vinculada com sucesso!")
                                    posicaoParaVincularNfc = null
                                    bindingTagUidInput = ""
                                    bindingErrorMessage = null
                                } else {
                                    bindingErrorMessage = res.exceptionOrNull()?.message
                                }
                            }
                        }
                    },
                    enabled = bindingTagUidInput.isNotBlank(),
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Concluir cadastro")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { posicaoParaVincularNfc = null },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Cancelar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // Diálogo de Simulação NFC integrado para testes no emulador e Web
    if (showNfcSimulatorForBinding) {
        NfcSimulatorDialog(
            tagsDisponiveis = allTags,
            posicoes = allPosicoes,
            onDismiss = { showNfcSimulatorForBinding = false },
            onTagSelected = { uid ->
                bindingTagUidInput = uid
                bindingErrorMessage = null
                NfcManager.onTagScanned(uid, force = true)
            }
        )
    }
}

@Composable
private fun ModalValidationError(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_error),
            contentDescription = null, // Decorativo: o texto ao lado descreve o erro de validação
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
    }
}
