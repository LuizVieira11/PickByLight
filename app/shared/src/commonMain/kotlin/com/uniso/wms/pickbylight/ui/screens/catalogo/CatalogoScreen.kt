package com.uniso.wms.pickbylight.ui.screens.catalogo

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.EstoquePosicaoDetalhe
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.domain.model.Movimentacao
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.nfc.NfcManager
import com.uniso.wms.pickbylight.ui.components.EmptyState
import com.uniso.wms.pickbylight.ui.components.ErrorState
import com.uniso.wms.pickbylight.ui.components.LoadingState
import com.uniso.wms.pickbylight.ui.components.NfcSimulatorDialog
import com.uniso.wms.pickbylight.ui.components.PrimaryButton
import com.uniso.wms.pickbylight.ui.components.QuantityStepper
import com.uniso.wms.pickbylight.ui.theme.AmberWarning
import com.uniso.wms.pickbylight.ui.theme.CoralError
import com.uniso.wms.pickbylight.ui.theme.EmeraldSuccess
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.ShelfRackBlue
import com.uniso.wms.pickbylight.ui.theme.ShelfRowOrange
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.FeedbackScenario
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogoScreen(
    repository: WmsRepository,
    feedbackController: FeedbackController,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateEnderecosComUid: ((String) -> Unit)? = null,
    viewModel: CatalogoViewModel = viewModel {
        CatalogoViewModel(repository, feedbackController)
    }
) {
    val dimensions = LocalWmsDimensions.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsState()

    val posicoes by repository.getPosicoes().collectAsState(initial = emptyList())
    val prateleiras by repository.getPrateleiras().collectAsState(initial = emptyList())
    val estantes by repository.getEstantes().collectAsState(initial = emptyList())
    val tagsNFC by repository.getTagsNFC().collectAsState(initial = emptyList())

    // Modais
    var itemEmEdicao by remember { mutableStateOf<Item?>(null) }
    var showModalNovoItem by remember { mutableStateOf(false) }
    var itemParaMover by remember { mutableStateOf<ItemCardUi?>(null) }
    var unmappedTagErrorUid by remember { mutableStateOf<String?>(null) }

    fun formatEndereco(posId: String?): String {
        if (posId == null) return "Sem Endereço"
        val pos = posicoes.find { it.id == posId } ?: return "Posição desconhecida"
        val prat = prateleiras.find { it.id == pos.prateleiraId }
        val est = estantes.find { it.id == prat?.estanteId }
        val estanteStr = est?.nome?.takeIf { it.isNotBlank() } ?: est?.codigo ?: "Est"
        val pratStr = prat?.codigo?.takeIf { it.isNotBlank() } ?: "Prat"
        return "$estanteStr > $pratStr > ${pos.codigo}"
    }

    PlatformBackHandler {
        onNavigateBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    itemEmEdicao = null
                    showModalNovoItem = true
                },
                containerColor = ShelfRackBlue,
                contentColor = Color.White
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("➕", fontWeight = FontWeight.Bold)
                    Text("Novo Item", fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                is CatalogoUiState.Loading -> {
                    LoadingState(
                        message = "Carregando catálogo de produtos...",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                is CatalogoUiState.Empty -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(dimensions.paddingMedium)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Catálogo de Itens",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(
                                onClick = onNavigateBack,
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Voltar", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        EmptyState(
                            title = "Nenhum item cadastrado",
                            message = "O catálogo está vazio. Cadastre o primeiro item para gerenciar seu estoque.",
                            actionLabel = "➕ Cadastrar Item",
                            onActionClick = { showModalNovoItem = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is CatalogoUiState.Error -> {
                    ErrorState(
                        message = state.message,
                        onRetry = { /* StateFlow automatically retries */ },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                is CatalogoUiState.Success -> {
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
                                text = "Catálogo de Itens",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            TextButton(
                                onClick = onNavigateBack,
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Voltar", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Campo de Busca Reativa (Seção 3.3)
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Buscar por SKU, nome ou categoria...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            leadingIcon = { Text("🔍") }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Chips de Filtro
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = state.categoriaSelecionada == null,
                                onClick = { viewModel.setCategoria(null) },
                                label = { Text("Todas (${state.totalItensCount})") }
                            )

                            state.categoriasDisponiveis.forEach { cat ->
                                FilterChip(
                                    selected = state.categoriaSelecionada == cat,
                                    onClick = {
                                        if (state.categoriaSelecionada == cat) {
                                            viewModel.setCategoria(null)
                                        } else {
                                            viewModel.setCategoria(cat)
                                        }
                                    },
                                    label = { Text(cat) }
                                )
                            }

                            FilterChip(
                                selected = state.apenasAbaixoMinimo,
                                onClick = { viewModel.toggleApenasAbaixoMinimo() },
                                label = {
                                    Text(
                                        text = "Abaixo do Mínimo (${state.itensAbaixoMinimoCount})",
                                        color = if (state.apenasAbaixoMinimo) CoralError else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Lista Reativa de Cards dos Itens
                        if (state.itens.isEmpty()) {
                            EmptyState(
                                title = "Nenhum item encontrado",
                                message = "Nenhum produto corresponde aos filtros ou busca aplicados.",
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(state.itens, key = { it.item.id }) { itemCard ->
                                    ItemCardComponent(
                                        itemCard = itemCard,
                                        debugMode = state.modoDebugNfc,
                                        onEdit = { itemEmEdicao = itemCard.item },
                                        onMove = { itemParaMover = itemCard },
                                        onToggleLock = {
                                            coroutineScope.launch {
                                                val res = viewModel.alternarEmSeparacao(itemCard.item.id)
                                                if (res.isSuccess) {
                                                    val isLocked = res.getOrNull() == true
                                                    val msg = if (isLocked) {
                                                        "Item [${itemCard.item.sku}] marcado como 'Em Separação'."
                                                    } else {
                                                        "Item [${itemCard.item.sku}] liberado da separação."
                                                    }
                                                    snackbarHostState.showSnackbar(msg)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Overlay Vermelho + Modal de Tag NFC Não Cadastrada
            if (unmappedTagErrorUid != null) {
                val uid = unmappedTagErrorUid ?: ""

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CoralError.copy(alpha = 0.22f))
                )

                AlertDialog(
                    onDismissRequest = { unmappedTagErrorUid = null },
                    title = {
                        Text(
                            text = "Tag NFC não cadastrada no sistema!",
                            color = CoralError,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = "A tag aproximada possui o UID:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uid,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Esta tag ainda não foi vinculada a nenhuma prateleira ou posição do armazém.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        PrimaryButton(
                            text = "Cadastrar Agora",
                            onClick = {
                                val currentUid = unmappedTagErrorUid
                                unmappedTagErrorUid = null
                                if (currentUid != null && onNavigateEnderecosComUid != null) {
                                    onNavigateEnderecosComUid(currentUid)
                                }
                            },
                            containerColor = ShelfRackBlue
                        )
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { unmappedTagErrorUid = null },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Fechar")
                        }
                    }
                )
            }
        }
    }

    // =========================================================================
    // MODAL: Cadastro / Edição de Item
    // =========================================================================
    val itemSendoEditado = itemEmEdicao
    if (itemSendoEditado != null || showModalNovoItem) {
        val isEditing = itemSendoEditado != null
        val categoriasPadrao = listOf(
            "Microcontroladores",
            "Sensores",
            "Iluminação",
            "Identificação",
            "Atuadores"
        )
        val stateCategorias = (uiState as? CatalogoUiState.Success)?.categoriasDisponiveis ?: emptyList()
        val todasCategorias = (categoriasPadrao + stateCategorias).distinct().sorted()

        var skuInput by remember { mutableStateOf(itemSendoEditado?.sku ?: "") }
        var nomeInput by remember { mutableStateOf(itemSendoEditado?.nome ?: "") }
        var categoriaInput by remember { mutableStateOf(itemSendoEditado?.categoria ?: todasCategorias.first()) }
        var dropdownCategoriaExpanded by remember { mutableStateOf(false) }
        var showNovaCategoriaDialog by remember { mutableStateOf(false) }

        var unidadeInput by remember { mutableStateOf(itemSendoEditado?.unidade ?: "UN") }
        var estoqueMinimoInput by remember { mutableStateOf(itemSendoEditado?.estoqueMinimo?.toString() ?: "5") }
        var erroModalItem by remember { mutableStateOf<String?>(null) }
        var isSaving by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                if (!isSaving) {
                    itemEmEdicao = null
                    showModalNovoItem = false
                }
            },
            title = {
                Text(
                    text = if (isEditing) "Editar Item: ${itemSendoEditado?.sku}" else "Cadastrar Novo Item",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = skuInput,
                        onValueChange = { skuInput = it.uppercase() },
                        label = { Text("SKU (Código único)*") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = nomeInput,
                        onValueChange = { nomeInput = it },
                        label = { Text("Nome do Produto*") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    ExposedDropdownMenuBox(
                        expanded = dropdownCategoriaExpanded,
                        onExpandedChange = { dropdownCategoriaExpanded = !dropdownCategoriaExpanded }
                    ) {
                        OutlinedTextField(
                            value = categoriaInput.ifBlank { "Selecione a categoria..." },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Categoria*") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownCategoriaExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            shape = RoundedCornerShape(8.dp)
                        )

                        ExposedDropdownMenu(
                            expanded = dropdownCategoriaExpanded,
                            onDismissRequest = { dropdownCategoriaExpanded = false }
                        ) {
                            todasCategorias.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat) },
                                    onClick = {
                                        categoriaInput = cat
                                        dropdownCategoriaExpanded = false
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("➕ Adicionar Nova Categoria...", color = ShelfRackBlue, fontWeight = FontWeight.Bold) },
                                onClick = {
                                    dropdownCategoriaExpanded = false
                                    showNovaCategoriaDialog = true
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = unidadeInput,
                            onValueChange = { unidadeInput = it.uppercase() },
                            label = { Text("Unidade (UN, CX)*") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = estoqueMinimoInput,
                            onValueChange = { estoqueMinimoInput = it.filter { c -> c.isDigit() } },
                            label = { Text("Estoque Mínimo*") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    if (erroModalItem != null) {
                        Text(
                            text = erroModalItem ?: "",
                            color = CoralError,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val minEstoque = estoqueMinimoInput.toIntOrNull() ?: 5

                        val itemToSave = if (isEditing) {
                            itemSendoEditado!!.copy(
                                sku = skuInput.trim(),
                                nome = nomeInput.trim(),
                                categoria = categoriaInput.trim(),
                                unidade = unidadeInput.trim(),
                                estoqueMinimo = minEstoque
                            )
                        } else {
                            Item(
                                sku = skuInput.trim(),
                                nome = nomeInput.trim(),
                                categoria = categoriaInput.trim(),
                                unidade = unidadeInput.trim(),
                                estoqueMinimo = minEstoque
                            )
                        }

                        coroutineScope.launch {
                            isSaving = true
                            val res = viewModel.salvarItem(itemToSave)
                            isSaving = false
                            if (res.isSuccess) {
                                itemEmEdicao = null
                                showModalNovoItem = false
                                snackbarHostState.showSnackbar("Item '${itemToSave.nome}' salvo com sucesso!")
                            } else {
                                erroModalItem = res.exceptionOrNull()?.message
                            }
                        }
                    },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Salvar Item", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        itemEmEdicao = null
                        showModalNovoItem = false
                    },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Cancelar")
                }
            }
        )

        if (showNovaCategoriaDialog) {
            var novaCatNome by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showNovaCategoriaDialog = false },
                title = { Text("Nova Categoria", fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = novaCatNome,
                        onValueChange = { novaCatNome = it },
                        label = { Text("Nome da Categoria") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (novaCatNome.isNotBlank()) {
                                categoriaInput = novaCatNome.trim()
                                showNovaCategoriaDialog = false
                            }
                        }
                    ) {
                        Text("Adicionar", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNovaCategoriaDialog = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }

    // =========================================================================
    // MODAL: Mover Item (Transferência Multi-Posição)
    // =========================================================================
    val itemTransferindo = itemParaMover
    if (itemTransferindo != null) {
        val estoquesDisponiveis = itemTransferindo.estoques.filter { it.quantidade > 0 }
        var posOrigemSelecionada by remember { mutableStateOf(estoquesDisponiveis.firstOrNull()) }
        var dropdownOrigemExpanded by remember { mutableStateOf(false) }

        var posDestinoSelecionada by remember { mutableStateOf<Posicao?>(null) }
        var dropdownDestinoExpanded by remember { mutableStateOf(false) }
        var showNfcSimulatorForTransfer by remember { mutableStateOf(false) }
        var quantidadeTransferir by remember {
            mutableStateOf(1.coerceAtMost(posOrigemSelecionada?.quantidade ?: 1))
        }
        var erroTransferencia by remember { mutableStateOf<String?>(null) }
        var isMoving by remember { mutableStateOf(false) }

        // Escuta leitura NFC física para selecionar posição de destino
        LaunchedEffect(Unit) {
            NfcManager.scannedTagFlow.collectLatest { uid ->
                val pos = viewModel.buscarPosicaoPorTagUid(uid)
                if (pos != null) {
                    posDestinoSelecionada = pos
                    feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                } else {
                    feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
                    unmappedTagErrorUid = uid
                }
            }
        }

        AlertDialog(
            onDismissRequest = {
                if (!isMoving) itemParaMover = null
            },
            title = {
                Column {
                    Text("Mover Item (Transferência Interna)", fontWeight = FontWeight.Bold)
                    Text(
                        "${itemTransferindo.item.sku} — ${itemTransferindo.item.nome}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ShelfRackBlue
                    )
                }
            },
            text = {
                if (estoquesDisponiveis.isEmpty()) {
                    Text(
                        "Este item não possui saldo alocado em nenhuma posição física para realizar movimentação.",
                        color = AmberWarning
                    )
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Seleção da Posição de Origem
                        Text("Posição de Origem:", fontWeight = FontWeight.SemiBold)

                        if (estoquesDisponiveis.size == 1) {
                            val unicaOrigem = estoquesDisponiveis.first()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Column {
                                    Text(unicaOrigem.enderecoFormatado, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text("Saldo Disponível: ${unicaOrigem.quantidade} ${itemTransferindo.item.unidade}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else {
                            ExposedDropdownMenuBox(
                                expanded = dropdownOrigemExpanded,
                                onExpandedChange = { dropdownOrigemExpanded = !dropdownOrigemExpanded }
                            ) {
                                OutlinedTextField(
                                    value = posOrigemSelecionada?.let { "${it.enderecoFormatado} (${it.quantidade} ${itemTransferindo.item.unidade})" } ?: "Selecione a origem...",
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownOrigemExpanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                                    shape = RoundedCornerShape(8.dp)
                                )

                                ExposedDropdownMenu(
                                    expanded = dropdownOrigemExpanded,
                                    onDismissRequest = { dropdownOrigemExpanded = false }
                                ) {
                                    estoquesDisponiveis.forEach { est ->
                                        DropdownMenuItem(
                                            text = { Text("${est.enderecoFormatado} (${est.quantidade} ${itemTransferindo.item.unidade})") },
                                            onClick = {
                                                posOrigemSelecionada = est
                                                quantidadeTransferir = 1.coerceAtMost(est.quantidade)
                                                dropdownOrigemExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Seleção da Posição de Destino
                        Text("Posição de Destino:", fontWeight = FontWeight.SemiBold)

                        ExposedDropdownMenuBox(
                            expanded = dropdownDestinoExpanded,
                            onExpandedChange = { dropdownDestinoExpanded = !dropdownDestinoExpanded }
                        ) {
                            OutlinedTextField(
                                value = posDestinoSelecionada?.let { formatEndereco(it.id) } ?: "Selecione a posição de destino...",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownDestinoExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                                shape = RoundedCornerShape(8.dp)
                            )

                            ExposedDropdownMenu(
                                expanded = dropdownDestinoExpanded,
                                onDismissRequest = { dropdownDestinoExpanded = false }
                            ) {
                                val origemId = posOrigemSelecionada?.posicaoId
                                posicoes.filter { it.id != origemId }.forEach { pos ->
                                    DropdownMenuItem(
                                        text = { Text(formatEndereco(pos.id)) },
                                        onClick = {
                                            posDestinoSelecionada = pos
                                            dropdownDestinoExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        PrimaryButton(
                            text = "Ou Bipar Tag NFC do Destino",
                            onClick = { showNfcSimulatorForTransfer = true },
                            containerColor = ShelfRackBlue
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Quantidade a Mover
                        val saldoOrigem = posOrigemSelecionada?.quantidade ?: 1
                        Text("Quantidade a Mover:", fontWeight = FontWeight.SemiBold)

                        QuantityStepper(
                            value = quantidadeTransferir,
                            onValueChange = { quantidadeTransferir = it },
                            min = 1,
                            max = saldoOrigem.coerceAtLeast(1)
                        )

                        val saldoRestanteOrigem = (saldoOrigem - quantidadeTransferir).coerceAtLeast(0)
                        Text(
                            text = if (quantidadeTransferir == saldoOrigem)
                                "Transferência total desta posição (o saldo nesta posição será zerado)."
                            else
                                "Saldo que permanecerá na origem: $saldoRestanteOrigem ${itemTransferindo.item.unidade}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (erroTransferencia != null) {
                            Text(
                                text = erroTransferencia ?: "",
                                color = CoralError,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val origem = posOrigemSelecionada
                        val destino = posDestinoSelecionada
                        if (origem == null) {
                            erroTransferencia = "Selecione a posição de origem."
                            return@TextButton
                        }
                        if (destino == null) {
                            erroTransferencia = "Selecione a posição de destino."
                            return@TextButton
                        }

                        coroutineScope.launch {
                            isMoving = true
                            val res = viewModel.moverItem(
                                itemId = itemTransferindo.item.id,
                                posicaoOrigemId = origem.posicaoId,
                                novaPosicaoId = destino.id,
                                quantidade = quantidadeTransferir
                            )
                            isMoving = false
                            if (res.isSuccess) {
                                itemParaMover = null
                                snackbarHostState.showSnackbar(
                                    "Transferência de $quantidadeTransferir ${itemTransferindo.item.unidade} de '${itemTransferindo.item.nome}' para ${destino.codigo} concluída!"
                                )
                            } else {
                                erroTransferencia = res.exceptionOrNull()?.message
                            }
                        }
                    },
                    enabled = posOrigemSelecionada != null && posDestinoSelecionada != null && !isMoving,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Confirmar Transferência", fontWeight = FontWeight.Bold, color = EmeraldSuccess)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { itemParaMover = null },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Cancelar")
                }
            }
        )

        if (showNfcSimulatorForTransfer) {
            NfcSimulatorDialog(
                tagsDisponiveis = tagsNFC,
                posicoes = posicoes,
                onDismiss = { showNfcSimulatorForTransfer = false },
                onTagSelected = { uid ->
                    coroutineScope.launch {
                        val pos = viewModel.buscarPosicaoPorTagUid(uid)
                        showNfcSimulatorForTransfer = false
                        if (pos != null) {
                            posDestinoSelecionada = pos
                            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                        } else {
                            feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
                            unmappedTagErrorUid = uid
                        }
                    }
                }
            )
        }
    }
}

/**
 * Card individual do Item com visualização expansível (Accordion) do estoque por posição.
 */
@Composable
private fun ItemCardComponent(
    itemCard: ItemCardUi,
    debugMode: Boolean,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onToggleLock: () -> Unit
) {
    val item = itemCard.item
    var accordionExpanded by remember { mutableStateOf(itemCard.estoques.size <= 2) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.emSeparacao) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Linha 1: Nome do produto e SKU
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.nome,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(ShelfRackBlue.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = item.sku,
                        style = MaterialTheme.typography.labelSmall,
                        color = ShelfRackBlue,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Linha 2: Etiquetas (Categoria, SKU e Badge "Em Separação")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.categoria,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (item.emSeparacao) {
                    Box(
                        modifier = Modifier
                            .background(CoralError, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "🔒 Em Separação",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Linha 3: Saldo Total em Destaque (Posicionado abaixo das etiquetas conforme solicitação)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Saldo Total: ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${itemCard.saldoTotal} ${item.unidade}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (itemCard.isAbaixoMinimo) CoralError else EmeraldSuccess,
                        maxLines = 1
                    )
                    if (itemCard.isAbaixoMinimo) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(Abaixo do mín: ${item.estoqueMinimo})",
                            style = MaterialTheme.typography.labelSmall,
                            color = CoralError,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Linha 3: Accordion / Detalhamento de Estoque por Posição
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { accordionExpanded = !accordionExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📍 Localizações (${itemCard.estoques.size} posições)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ShelfRackBlue
                        )
                        Text(
                            text = if (accordionExpanded) "▲ Ocultar" else "▼ Detalhar",
                            style = MaterialTheme.typography.labelSmall,
                            color = ShelfRackBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (accordionExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (itemCard.estoques.isEmpty()) {
                            Text(
                                text = "Nenhum estoque físico alocado para este item.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AmberWarning
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                itemCard.estoques.forEach { estoque ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.surface,
                                                RoundedCornerShape(6.dp)
                                            )
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "• ${estoque.enderecoFormatado}:",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${estoque.quantidade} ${item.unidade}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = EmeraldSuccess
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Linha 4: Ações Rápidas no Card
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onEdit,
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text("✏️ Editar", style = MaterialTheme.typography.labelMedium)
                    }

                    TextButton(
                        onClick = onMove,
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(
                            text = "📦 Mover",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ShelfRowOrange
                        )
                    }
                }

                if (debugMode) {
                    IconButton(
                        onClick = onToggleLock,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Text("⚙️", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
