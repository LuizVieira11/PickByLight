package com.uniso.wms.pickbylight.ui.screens.enderecos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.uniso.wms.pickbylight.ui.theme.AmberWarning
import com.uniso.wms.pickbylight.ui.theme.CoralError
import com.uniso.wms.pickbylight.ui.theme.EmeraldSuccess
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.ShelfBinGreen
import com.uniso.wms.pickbylight.ui.theme.ShelfRackBlue
import com.uniso.wms.pickbylight.ui.theme.ShelfRowOrange
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun EnderecosScreen(
    repository: WmsRepository,
    mqttService: MqttService,
    feedbackController: FeedbackController,
    initialTagUid: String? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EnderecosViewModel = remember(repository, mqttService, feedbackController) {
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
                .padding(dimensions.paddingMedium)
        ) {
            // Cabeçalho da Tela
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Gestão de Endereços",
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

            // Notificação de UID pendente recebido de outro fluxo (ex: Recebimento com tag não cadastrada)
            if (!initialTagUid.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AmberWarning.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🏷️ UID lido pronto para vincular: $initialTagUid. Selecione uma posição abaixo para associar.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = AmberWarning,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Barra de Busca
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                label = { Text("Buscar por código, estante, posição ou UID...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(10.dp))

            when (val state = uiState) {
                is EnderecosUiState.Loading -> {
                    LoadingState(message = "Carregando endereços do armazém...")
                }

                is EnderecosUiState.Empty -> {
                    EmptyState(
                        title = "Nenhum endereço cadastrado",
                        message = "Cadastre a primeira estante física para iniciar o mapeamento do armazém.",
                        actionLabel = "➕ Cadastrar Nova Estante",
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
                    // Filtros rápidos por Chips (Seção 3.2)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = state.filtroNfc == FiltroNfc.TODOS,
                            onClick = { viewModel.setFiltroNfc(FiltroNfc.TODOS) },
                            label = { Text("Todos (${state.totalPosicoes})") }
                        )
                        FilterChip(
                            selected = state.filtroNfc == FiltroNfc.VINCULADO,
                            onClick = { viewModel.setFiltroNfc(FiltroNfc.VINCULADO) },
                            label = { Text("NFC Vinculado (${state.posicoesVinculadasCount})") }
                        )
                        FilterChip(
                            selected = state.filtroNfc == FiltroNfc.PENDENTE,
                            onClick = { viewModel.setFiltroNfc(FiltroNfc.PENDENTE) },
                            label = { Text("NFC Pendente (${state.posicoesPendentesCount})") }
                        )
                        TextButton(
                            onClick = { showModalNovaEstante = true }
                        ) {
                            Text("➕ Nova Estante", fontWeight = FontWeight.Bold, color = ShelfRackBlue)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Árvore Expansível em 3 Níveis (Seção 3.2)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.estantes) { estanteItem ->
                            val estante = estanteItem.estante

                            // NÍVEL 1 (Azul): Estante
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    // Linha superior: Título e Expansão
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.toggleEstanteExpanded(estante.id) },
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(6.dp)
                                                    .height(36.dp)
                                                    .background(ShelfRackBlue, RoundedCornerShape(3.dp))
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${estante.codigo} — ${estante.nome}",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ShelfRackBlue,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "LED: ${estante.ledId} • Setor: ${estante.setor}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        Text(
                                            text = if (estanteItem.isExpanded) "▲" else "▼",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }

                                    // Linha de ações da Estante
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    val res = viewModel.testarLed(estante.ledId, null, null)
                                                    if (res.isSuccess) {
                                                        snackbarHostState.showSnackbar("TESTE_LED enviado para estante ${estante.codigo}")
                                                    }
                                                }
                                            }
                                        ) {
                                            Text("💡 Testar LED", style = MaterialTheme.typography.labelSmall)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        TextButton(
                                            onClick = { selectedEstanteForPrateleira = estante }
                                        ) {
                                            Text("➕ Prateleira", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    // NÍVEL 2: Prateleiras da Estante (Laranja)
                                    AnimatedVisibility(visible = estanteItem.isExpanded) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 12.dp, top = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (estanteItem.prateleiras.isEmpty()) {
                                                Text(
                                                    text = "Nenhuma prateleira encontrada para este filtro.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.padding(vertical = 4.dp)
                                                )
                                            }

                                            estanteItem.prateleiras.forEach { pratItem ->
                                                val prateleira = pratItem.prateleira

                                                Card(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                    ),
                                                    shape = RoundedCornerShape(10.dp)
                                                ) {
                                                    Column(modifier = Modifier.padding(10.dp)) {
                                                        // Linha superior: Código da Prateleira e Chevron
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clickable { viewModel.togglePrateleiraExpanded(prateleira.id) },
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.weight(1f),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .width(5.dp)
                                                                        .height(28.dp)
                                                                        .background(ShelfRowOrange, RoundedCornerShape(2.dp))
                                                                )
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                Column(modifier = Modifier.weight(1f)) {
                                                                    Text(
                                                                        text = prateleira.codigo,
                                                                        style = MaterialTheme.typography.titleSmall,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = ShelfRowOrange,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                    Text(
                                                                        text = "LED Ponta: ${prateleira.ledIdPonta}",
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                }
                                                            }

                                                            Text(
                                                                text = if (pratItem.isExpanded) "▲" else "▼",
                                                                style = MaterialTheme.typography.titleSmall,
                                                                color = MaterialTheme.colorScheme.outline,
                                                                modifier = Modifier.padding(start = 8.dp)
                                                            )
                                                        }

                                                        // Linha de ações da Prateleira
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(top = 4.dp),
                                                            horizontalArrangement = Arrangement.End,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            TextButton(
                                                                onClick = {
                                                                    coroutineScope.launch {
                                                                        val res = viewModel.testarLed(estante.ledId, prateleira.ledIdPonta, null)
                                                                        if (res.isSuccess) {
                                                                            snackbarHostState.showSnackbar("TESTE_LED enviado para prateleira ${prateleira.codigo}")
                                                                        }
                                                                    }
                                                                }
                                                            ) {
                                                                Text("💡 Testar LED", style = MaterialTheme.typography.labelSmall)
                                                            }

                                                            Spacer(modifier = Modifier.width(6.dp))

                                                            TextButton(
                                                                onClick = { selectedPrateleiraForPosicao = prateleira }
                                                            ) {
                                                                Text("➕ Posição", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                            }
                                                        }

                                                        // NÍVEL 3: Posições da Prateleira (Verde)
                                                        AnimatedVisibility(visible = pratItem.isExpanded) {
                                                            Column(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(start = 10.dp, top = 6.dp),
                                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                if (pratItem.posicoes.isEmpty()) {
                                                                    Text(
                                                                        text = "Nenhuma posição para o filtro selecionado.",
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        color = MaterialTheme.colorScheme.outline,
                                                                        modifier = Modifier.padding(vertical = 4.dp)
                                                                    )
                                                                }

                                                                pratItem.posicoes.forEach { posItem ->
                                                                    val posicao = posItem.posicao
                                                                    val tag = posItem.tagNfc
                                                                    val isVinculado = posItem.isVinculado

                                                                    Card(
                                                                        modifier = Modifier.fillMaxWidth(),
                                                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                                                        shape = RoundedCornerShape(8.dp)
                                                                    ) {
                                                                        Column(
                                                                            modifier = Modifier
                                                                                .fillMaxWidth()
                                                                                .padding(8.dp)
                                                                        ) {
                                                                            // Linha superior: Código da Posição + Badge de Status NFC
                                                                            Row(
                                                                                modifier = Modifier.fillMaxWidth(),
                                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                                verticalAlignment = Alignment.CenterVertically
                                                                            ) {
                                                                                Row(
                                                                                    modifier = Modifier.weight(1f, fill = false),
                                                                                    verticalAlignment = Alignment.CenterVertically
                                                                                ) {
                                                                                    Box(
                                                                                        modifier = Modifier
                                                                                            .width(4.dp)
                                                                                            .height(20.dp)
                                                                                            .background(ShelfBinGreen, RoundedCornerShape(2.dp))
                                                                                    )
                                                                                    Spacer(modifier = Modifier.width(8.dp))
                                                                                    Text(
                                                                                        text = posicao.codigo,
                                                                                        style = MaterialTheme.typography.titleSmall,
                                                                                        fontWeight = FontWeight.Bold,
                                                                                        color = ShelfBinGreen,
                                                                                        maxLines = 1,
                                                                                        overflow = TextOverflow.Ellipsis
                                                                                    )
                                                                                }

                                                                                // Badge de Status NFC (JOIN com TagNFC)
                                                                                Box(
                                                                                    modifier = Modifier
                                                                                        .background(
                                                                                            if (isVinculado) EmeraldSuccess.copy(alpha = 0.15f) else AmberWarning.copy(alpha = 0.15f),
                                                                                            RoundedCornerShape(4.dp)
                                                                                        )
                                                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                                                    ) {
                                                                                        Text(
                                                                                            text = if (isVinculado) "Vinculado" else "Pendente",
                                                                                            style = MaterialTheme.typography.labelSmall,
                                                                                            color = if (isVinculado) EmeraldSuccess else AmberWarning,
                                                                                            fontWeight = FontWeight.Bold,
                                                                                            maxLines = 1
                                                                                        )
                                                                                    }
                                                                            }

                                                                            Spacer(modifier = Modifier.height(4.dp))

                                                                            // Informações da Posição
                                                                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                                                                Text(
                                                                                    text = "LED: ${posicao.ledId} • L×A×P: ${posicao.limitesLap}",
                                                                                    style = MaterialTheme.typography.bodySmall,
                                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                                    maxLines = 1,
                                                                                    overflow = TextOverflow.Ellipsis
                                                                                )
                                                                                if (isVinculado && tag != null) {
                                                                                    Text(
                                                                                        text = "🏷️ Tag NFC: ${tag.uid}",
                                                                                        style = MaterialTheme.typography.bodySmall,
                                                                                        color = EmeraldSuccess,
                                                                                        fontWeight = FontWeight.SemiBold,
                                                                                        maxLines = 1,
                                                                                        overflow = TextOverflow.Ellipsis
                                                                                    )
                                                                                }
                                                                            }

                                                                            // Ações da Posição
                                                                            Row(
                                                                                modifier = Modifier
                                                                                    .fillMaxWidth()
                                                                                    .padding(top = 4.dp),
                                                                                horizontalArrangement = Arrangement.End,
                                                                                verticalAlignment = Alignment.CenterVertically
                                                                            ) {
                                                                                TextButton(
                                                                                    onClick = {
                                                                                        coroutineScope.launch {
                                                                                            val res = viewModel.testarLed(estante.ledId, prateleira.ledIdPonta, posicao.ledId)
                                                                                            if (res.isSuccess) {
                                                                                                snackbarHostState.showSnackbar("TESTE_LED enviado para posição ${posicao.codigo}")
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                ) {
                                                                                    Text("💡 Testar", style = MaterialTheme.typography.labelSmall)
                                                                                }

                                                                                Spacer(modifier = Modifier.width(6.dp))

                                                                                TextButton(
                                                                                    onClick = {
                                                                                        posicaoParaVincularNfc = posicao
                                                                                        bindingTagUidInput = tag?.uid ?: initialTagUid ?: ""
                                                                                        bindingErrorMessage = null
                                                                                    }
                                                                                ) {
                                                                                    Text(
                                                                                        text = if (isVinculado) "Alterar Tag" else "Vincular",
                                                                                        style = MaterialTheme.typography.labelSmall,
                                                                                        fontWeight = FontWeight.Bold,
                                                                                        color = ShelfRackBlue
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
            title = { Text("Cadastrar Nova Estante", fontWeight = FontWeight.Bold) },
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
                        label = { Text("Nome da Estante") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = setorEstante,
                        onValueChange = { setorEstante = it },
                        label = { Text("Setor / Descrição") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ledIdEstante,
                        onValueChange = { ledIdEstante = it.uppercase() },
                        label = { Text("ID do LED (sinalizador superior)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (erroEstante != null) {
                        Text(erroEstante ?: "", color = CoralError, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (codEstante.isBlank() || nomeEstante.isBlank() || ledIdEstante.isBlank()) {
                            erroEstante = "Preencha todos os campos obrigatórios."
                            return@TextButton
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
                    }
                ) {
                    Text("Salvar Estante", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showModalNovaEstante = false }) {
                    Text("Cancelar")
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
                    Text("Nova Prateleira", fontWeight = FontWeight.Bold)
                    Text("Estante: ${estanteSelecionada.codigo} — ${estanteSelecionada.nome}", style = MaterialTheme.typography.bodySmall, color = ShelfRackBlue)
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
                        label = { Text("Código da Prateleira (ex: PRAT-A3)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ledIdPonta,
                        onValueChange = { ledIdPonta = it.uppercase() },
                        label = { Text("ID do LED de Ponta (ex: PRAT_A3_LED1)") },
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
                            }
                        ) {
                            Text("Testar LED", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (erroPrat != null) {
                        Text(erroPrat ?: "", color = CoralError, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (codPrat.isBlank() || ledIdPonta.isBlank()) {
                            erroPrat = "Preencha todos os campos obrigatórios."
                            return@TextButton
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
                    }
                ) {
                    Text("Salvar Prateleira", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedEstanteForPrateleira = null }) {
                    Text("Cancelar")
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
                    Text("Nova Posição de Armazenagem", fontWeight = FontWeight.Bold)
                    Text("Prateleira: ${prateleiraSelecionada.codigo}", style = MaterialTheme.typography.bodySmall, color = ShelfRowOrange)
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
                        label = { Text("Código da Posição (ex: POS_A3_01)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = limitesLap,
                        onValueChange = { limitesLap = it },
                        label = { Text("Limites L×A×P") },
                        supportingText = {
                            if (!isLapValido && limitesLap.isNotBlank()) {
                                Text("Formato esperado: 30x20x40cm ou 150x80x200mm", color = CoralError)
                            } else {
                                Text("Ex: 30x20x40cm")
                            }
                        },
                        isError = !isLapValido && limitesLap.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ledIdPosicao,
                        onValueChange = { ledIdPosicao = it.uppercase() },
                        label = { Text("ID da Fita de LED Posicional") },
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
                            }
                        ) {
                            Text("Testar LED", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (erroPosicao != null) {
                        Text(erroPosicao ?: "", color = CoralError, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (codPosicao.isBlank() || ledIdPosicao.isBlank()) {
                            erroPosicao = "Preencha todos os campos obrigatórios."
                            return@TextButton
                        }
                        if (!isLapValido) {
                            erroPosicao = "Formato esperado: 30x20x40cm ou 150x80x200mm"
                            return@TextButton
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
                    }
                ) {
                    Text("Salvar Posição", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPrateleiraForPosicao = null }) {
                    Text("Cancelar")
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
                Text("Vincular Etiqueta NFC", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Posição: ${posVinculando.codigo}", fontWeight = FontWeight.SemiBold, color = ShelfRackBlue)
                    Text("Aproxime a traseira do celular da etiqueta física (NTAG213) ou utilize o simulador abaixo.", style = MaterialTheme.typography.bodySmall)

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = bindingTagUidInput,
                        onValueChange = {
                            bindingTagUidInput = it.uppercase()
                            bindingErrorMessage = null
                        },
                        label = { Text("UID da Tag NFC (7 bytes hex)") },
                        placeholder = { Text("04:A1:2B:3C:4D:5E:01") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (bindingErrorMessage != null) {
                        Text(bindingErrorMessage ?: "", color = CoralError, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    PrimaryButton(
                        text = "Simulador de Leitura NFC",
                        onClick = { showNfcSimulatorForBinding = true },
                        containerColor = ShelfRackBlue
                    )
                }
            },
            confirmButton = {
                TextButton(
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
                    enabled = bindingTagUidInput.isNotBlank()
                ) {
                    Text("Concluir Cadastro", fontWeight = FontWeight.Bold, color = EmeraldSuccess)
                }
            },
            dismissButton = {
                TextButton(onClick = { posicaoParaVincularNfc = null }) {
                    Text("Cancelar")
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
