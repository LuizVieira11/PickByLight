package com.uniso.wms.pickbylight.ui.screens.catalogo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
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
import com.uniso.wms.pickbylight.ui.components.QuantityStepper
import com.uniso.wms.pickbylight.ui.components.ScreenHeader
import com.uniso.wms.pickbylight.ui.theme.InkMuted
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.OnStrong
import com.uniso.wms.pickbylight.ui.theme.SurfaceMuted
import com.uniso.wms.pickbylight.ui.theme.WarnAmber
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.FeedbackScenario
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import pickbylight.app.shared.generated.resources.Res
import pickbylight.app.shared.generated.resources.ic_add
import pickbylight.app.shared.generated.resources.ic_edit
import pickbylight.app.shared.generated.resources.ic_error
import pickbylight.app.shared.generated.resources.ic_expand_less
import pickbylight.app.shared.generated.resources.ic_expand_more
import pickbylight.app.shared.generated.resources.ic_inventory_2
import pickbylight.app.shared.generated.resources.ic_location_on
import pickbylight.app.shared.generated.resources.ic_lock
import pickbylight.app.shared.generated.resources.ic_nfc
import pickbylight.app.shared.generated.resources.ic_search
import pickbylight.app.shared.generated.resources.ic_settings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
        if (posId == null) return "Sem endereço"
        val pos = posicoes.find { it.id == posId } ?: return "Posição desconhecida"
        val prat = prateleiras.find { it.id == pos.prateleiraId }
        val est = estantes.find { it.id == prat?.estanteId }
        val estanteStr = est?.codigo?.takeIf { it.isNotBlank() } ?: est?.nome ?: "Est"
        val pratStr = prat?.codigo?.takeIf { it.isNotBlank() } ?: "Prat"
        return "$estanteStr · $pratStr · ${pos.codigo}"
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
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = OnStrong,
                shape = RoundedCornerShape(dimensions.buttonCornerRadius)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_add),
                        contentDescription = null, // Decorativo: o texto "Novo item" ao lado descreve a ação
                        tint = OnStrong,
                        modifier = Modifier.size(24.dp)
                    )
                    Text("Novo item", style = MaterialTheme.typography.titleMedium)
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
                            .padding(horizontal = dimensions.screenHorizontalPadding, vertical = dimensions.paddingMedium)
                    ) {
                        ScreenHeader(
                            title = "Catálogo",
                            actionLabel = "Voltar",
                            onAction = onNavigateBack
                        )

                        EmptyState(
                            title = "Nenhum item cadastrado",
                            message = "O catálogo está vazio. Cadastre o primeiro item para gerenciar seu estoque.",
                            actionLabel = "Cadastrar item",
                            actionLeadingIcon = {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_add),
                                    contentDescription = null, // Decorativo: o rótulo "Cadastrar item" descreve a ação
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
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
                            .padding(horizontal = dimensions.screenHorizontalPadding, vertical = dimensions.paddingMedium)
                    ) {
                        // Cabeçalho da Tela
                        ScreenHeader(
                            title = "Catálogo",
                            actionLabel = "Voltar",
                            onAction = onNavigateBack
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Campo de Busca Reativa (Seção 3.3)
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Buscar por SKU, nome ou categoria...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_search),
                                    contentDescription = null, // Decorativo: o placeholder indica busca no catálogo
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Chips de Filtro sem cortar categorias (FlowRow)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = state.categoriaSelecionada == null,
                                onClick = { viewModel.setCategoria(null) },
                                label = {
                                    Text(
                                        text = "Todas (${state.totalItensCount})",
                                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
                                    )
                                }
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
                                        text = "Abaixo do mínimo (${state.itensAbaixoMinimoCount})",
                                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurface
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
                                        formatPosicaoEndereco = { posId -> formatEndereco(posId) },
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

            // Modal de Tag NFC não cadastrada
            if (unmappedTagErrorUid != null) {
                val uid = unmappedTagErrorUid ?: ""

                AlertDialog(
                    onDismissRequest = { unmappedTagErrorUid = null },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_error),
                                contentDescription = null, // Decorativo: o título ao lado informa o erro
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "Tag NFC não cadastrada",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    text = {
                        Column {
                            Text(
                                text = "A tag aproximada possui o UID:",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = uid,
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Esta tag ainda não foi vinculada a nenhuma posição do armazém.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = InkMuted
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val currentUid = unmappedTagErrorUid
                                unmappedTagErrorUid = null
                                if (currentUid != null && onNavigateEnderecosComUid != null) {
                                    onNavigateEnderecosComUid(currentUid)
                                }
                            },
                            modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                            shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Cadastrar agora")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = { unmappedTagErrorUid = null },
                            modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                            shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text("Fechar", color = MaterialTheme.colorScheme.onSurface)
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
                    text = if (isEditing) "Editar item: ${itemSendoEditado?.sku}" else "Cadastrar novo item",
                    style = MaterialTheme.typography.titleLarge
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
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_add),
                                            contentDescription = null, // Decorativo: o texto ao lado descreve a ação
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Text("Adicionar nova categoria...", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                },
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
                        CatalogoErrorRow(erroModalItem ?: "")
                    }
                }
            },
            confirmButton = {
                Button(
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
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Salvar item")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        itemEmEdicao = null
                        showModalNovoItem = false
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Cancelar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )

        if (showNovaCategoriaDialog) {
            var novaCatNome by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showNovaCategoriaDialog = false },
                title = { Text("Nova categoria", style = MaterialTheme.typography.titleLarge) },
                text = {
                    OutlinedTextField(
                        value = novaCatNome,
                        onValueChange = { novaCatNome = it },
                        label = { Text("Nome da categoria") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (novaCatNome.isNotBlank()) {
                                categoriaInput = novaCatNome.trim()
                                showNovaCategoriaDialog = false
                            }
                        },
                        modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("Adicionar")
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showNovaCategoriaDialog = false },
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
                    Text("Mover item", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${itemTransferindo.item.sku} — ${itemTransferindo.item.nome}",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                        color = InkMuted
                    )
                }
            },
            text = {
                if (estoquesDisponiveis.isEmpty()) {
                    Text(
                        "Este item não possui saldo alocado em nenhuma posição física.",
                        color = WarnAmber
                    )
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Posição de origem", style = MaterialTheme.typography.labelLarge)

                        if (estoquesDisponiveis.size == 1) {
                            val unicaOrigem = estoquesDisponiveis.first()
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                shape = RoundedCornerShape(dimensions.buttonCornerRadius)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = formatEndereco(unicaOrigem.posicaoId),
                                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
                                    )
                                    Text(
                                        text = "Saldo disponível: ${unicaOrigem.quantidade} ${itemTransferindo.item.unidade}",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                        color = InkMuted
                                    )
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

                        OutlinedButton(
                            onClick = { showNfcSimulatorForTransfer = true },
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
                            Text("Bipar tag NFC do destino", color = MaterialTheme.colorScheme.onSurface)
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Quantidade a Mover
                        val saldoOrigem = posOrigemSelecionada?.quantidade ?: 1
                        Text("Quantidade a mover", style = MaterialTheme.typography.labelLarge)

                        QuantityStepper(
                            value = quantidadeTransferir,
                            onValueChange = { quantidadeTransferir = it },
                            min = 1,
                            max = saldoOrigem.coerceAtLeast(1)
                        )

                        val saldoRestanteOrigem = (saldoOrigem - quantidadeTransferir).coerceAtLeast(0)
                        Text(
                            text = if (quantidadeTransferir == saldoOrigem)
                                "Transferência total desta posição (saldo zerado)."
                            else
                                "Saldo restante na origem: $saldoRestanteOrigem ${itemTransferindo.item.unidade}",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                            color = InkMuted
                        )

                        if (erroTransferencia != null) {
                            CatalogoErrorRow(erroTransferencia ?: "")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val origem = posOrigemSelecionada
                        val destino = posDestinoSelecionada
                        if (origem == null) {
                            erroTransferencia = "Selecione a posição de origem."
                            return@Button
                        }
                        if (destino == null) {
                            erroTransferencia = "Selecione a posição de destino."
                            return@Button
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
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Confirmar transferência")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { itemParaMover = null },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Cancelar", color = MaterialTheme.colorScheme.onSurface)
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
    formatPosicaoEndereco: (String) -> String,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onToggleLock: () -> Unit
) {
    val dimensions = LocalWmsDimensions.current
    val item = itemCard.item
    var accordionExpanded by remember { mutableStateOf(itemCard.estoques.size <= 2) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(dimensions.cardCornerRadius)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(dimensions.paddingMedium)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.nome,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = SurfaceMuted
                    ) {
                        Text(
                            text = item.sku,
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = SurfaceMuted
                    ) {
                        Text(
                            text = item.categoria,
                            style = MaterialTheme.typography.labelMedium,
                            color = InkMuted,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (item.emSeparacao) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SurfaceMuted
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_lock),
                                    contentDescription = null, // Decorativo: o texto "Em separação" indica o bloqueio
                                    tint = InkMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Em separação",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = InkMuted
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Saldo total:",
                        style = MaterialTheme.typography.bodyLarge,
                        color = InkMuted
                    )
                    Text(
                        text = "${itemCard.saldoTotal} ${item.unidade}",
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = if (itemCard.isAbaixoMinimo) WarnAmber else MaterialTheme.colorScheme.onSurface
                    )
                    if (itemCard.isAbaixoMinimo) {
                        Text(
                            text = "(mín. ${item.estoqueMinimo})",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                            color = WarnAmber
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // Seção Localizações dentro do mesmo card (sem card dentro de card)
            val posCount = itemCard.estoques.size
            val posLabel = if (posCount == 1) "1 posição" else "$posCount posições"

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = dimensions.minTouchTarget)
                        .clickable { accordionExpanded = !accordionExpanded }
                        .padding(horizontal = dimensions.paddingMedium, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_location_on),
                            contentDescription = null, // Decorativo: o texto "Localizações" descreve a seção
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Localizações ($posLabel)",
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (accordionExpanded) "Ocultar" else "Detalhar",
                            style = MaterialTheme.typography.labelLarge,
                            color = InkMuted
                        )
                        Icon(
                            painter = painterResource(if (accordionExpanded) Res.drawable.ic_expand_less else Res.drawable.ic_expand_more),
                            contentDescription = null, // Decorativo: o texto "Ocultar"/"Detalhar" ao lado descreve o estado
                            tint = InkMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                if (accordionExpanded) {
                    if (itemCard.estoques.isEmpty()) {
                        Text(
                            text = "Nenhum estoque físico alocado.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = WarnAmber,
                            modifier = Modifier.padding(
                                start = dimensions.paddingMedium,
                                end = dimensions.paddingMedium,
                                bottom = 12.dp
                            )
                        )
                    } else {
                        itemCard.estoques.forEach { estoque ->
                            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = dimensions.paddingMedium, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatPosicaoEndereco(estoque.posicaoId),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${estoque.quantidade} ${item.unidade}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // Ações do item em Ink (>= 48 dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onEdit,
                        modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_edit),
                            contentDescription = null, // Decorativo: o texto "Editar" descreve a ação
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Editar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    }

                    TextButton(
                        onClick = onMove,
                        modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_inventory_2),
                            contentDescription = null, // Decorativo: o texto "Mover" descreve a ação
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Mover", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                if (debugMode) {
                    IconButton(
                        onClick = onToggleLock,
                        modifier = Modifier.size(dimensions.minTouchTarget)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_settings),
                            contentDescription = "Simular item em separação",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogoErrorRow(message: String) {
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
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
    }
}
