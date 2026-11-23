package com.uniso.wms.pickbylight.ui.screens.recebimento

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.nfc.NfcManager
import com.uniso.wms.pickbylight.ui.components.NfcSimulatorDialog
import com.uniso.wms.pickbylight.ui.components.PrimaryButton
import com.uniso.wms.pickbylight.ui.components.QuantityStepper
import com.uniso.wms.pickbylight.ui.components.ScreenHeader
import com.uniso.wms.pickbylight.ui.components.formatUnidade
import com.uniso.wms.pickbylight.ui.theme.InkMuted
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.OkTeal
import com.uniso.wms.pickbylight.ui.theme.OnStrong
import com.uniso.wms.pickbylight.ui.theme.SurfaceMuted
import com.uniso.wms.pickbylight.ui.theme.WarnAmber
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import pickbylight.app.shared.generated.resources.Res
import pickbylight.app.shared.generated.resources.ic_add
import pickbylight.app.shared.generated.resources.ic_check_circle
import pickbylight.app.shared.generated.resources.ic_error
import pickbylight.app.shared.generated.resources.ic_inventory_2
import pickbylight.app.shared.generated.resources.ic_location_on
import pickbylight.app.shared.generated.resources.ic_nfc
import pickbylight.app.shared.generated.resources.ic_warning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecebimentoScreen(
    repository: WmsRepository,
    feedbackController: FeedbackController,
    onNavigateBack: () -> Unit,
    onNavigateEnderecosComUid: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecebimentoViewModel = viewModel {
        RecebimentoViewModel(repository, feedbackController)
    }
) {
    val dimensions = LocalWmsDimensions.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsState()

    val estantes by repository.getEstantes().collectAsState(initial = emptyList())
    val prateleiras by repository.getPrateleiras().collectAsState(initial = emptyList())
    val posicoes by repository.getPosicoes().collectAsState(initial = emptyList())
    val tagsNFC by repository.getTagsNFC().collectAsState(initial = emptyList())
    val itens by repository.getItens().collectAsState(initial = emptyList())
    val itensComEstoque by repository.getItensComEstoque().collectAsState(initial = emptyList())

    var itemDropdownExpanded by remember { mutableStateOf(false) }

    // Diálogos de controle locais da UI
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }
    var showNfcSimulator by remember { mutableStateOf(false) }
    var showManualAddressDialog by remember { mutableStateOf(false) }
    var showNovoItemDialog by remember { mutableStateOf(false) }

    // Resolução de endereço completo para listagem manual e formulário
    fun formatEndereco(pos: Posicao?): String {
        if (pos == null) return "Endereço não identificado"
        val prat = prateleiras.find { it.id == pos.prateleiraId }
        val est = estantes.find { it.id == prat?.estanteId }
        val estanteStr = est?.codigo?.takeIf { it.isNotBlank() } ?: est?.nome ?: "Estante"
        val pratStr = prat?.codigo?.takeIf { it.isNotBlank() } ?: "Prat"
        return "$estanteStr · $pratStr · ${pos.codigo}"
    }

    // Exibe Snackbar de confirmação (Seção 3.4.4)
    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbarMessage()
        }
    }

    // Escuta eventos globais do NfcManager quando em etapa de leitura NFC (Único ou Guiado Múltiplo)
    LaunchedEffect(uiState.step, uiState.passoGuiadoIndex) {
        if (uiState.step == RecebimentoStep.SCAN_NFC || uiState.step == RecebimentoStep.GUIADO_NFC) {
            NfcManager.scannedTagFlow.collectLatest { uid ->
                viewModel.processarLeituraNfc(uid)
            }
        }
    }

    // Função unificada do botão Voltar conforme Seção 3.7
    val handleBackNavigation = {
        when (uiState.step) {
            RecebimentoStep.SCAN_NFC -> onNavigateBack()
            RecebimentoStep.MONTAGEM_LISTA -> {
                if (uiState.hasDirtyForm) {
                    showDiscardConfirmDialog = true
                } else {
                    onNavigateBack()
                }
            }
            RecebimentoStep.FORM, RecebimentoStep.GUIADO_NFC -> {
                if (uiState.hasDirtyForm) {
                    showDiscardConfirmDialog = true
                } else {
                    viewModel.descartarFormularioEVoltarParaScan()
                }
            }
            RecebimentoStep.SUCCESS -> {
                viewModel.iniciarNovaEntrada()
            }
        }
    }

    // Interceptação do botão Voltar físico/sistema (Seção 3.7)
    PlatformBackHandler {
        handleBackNavigation()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = dimensions.screenHorizontalPadding, vertical = dimensions.paddingMedium),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScreenHeader(
                title = "Recebimento",
                actionLabel = "Voltar",
                onAction = handleBackNavigation
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Seletor de Modo: [Recebimento único] e [Recebimento múltiplo]
            if (uiState.step == RecebimentoStep.SCAN_NFC || uiState.step == RecebimentoStep.MONTAGEM_LISTA) {
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
                        val isUnico = uiState.modo == ModoRecebimento.UNICO
                        Surface(
                            shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                            color = if (isUnico) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = dimensions.minTouchTarget)
                                .clickable { viewModel.selecionarModo(ModoRecebimento.UNICO) }
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Recebimento único",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isUnico) OnStrong else InkMuted,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        val isMultiplo = uiState.modo == ModoRecebimento.MULTIPLO
                        Surface(
                            shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                            color = if (isMultiplo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = dimensions.minTouchTarget)
                                .clickable { viewModel.selecionarModo(ModoRecebimento.MULTIPLO) }
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Recebimento múltiplo",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isMultiplo) OnStrong else InkMuted,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            when (uiState.step) {
                // =========================================================================
                // MODO ÚNICO — ETAPA 1: Leitura NFC de Destino (Seção 3.4.1)
                // =========================================================================
                RecebimentoStep.SCAN_NFC -> {
                    val isErro = uiState.isErrorFlashingRed
                    val ringColor = if (isErro) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, if (isErro) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        shape = RoundedCornerShape(dimensions.cardCornerRadius)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(dimensions.paddingLarge),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .border(2.dp, ringColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(if (isErro) Res.drawable.ic_error else Res.drawable.ic_nfc),
                                    contentDescription = if (isErro) "Erro na leitura NFC" else "Aguardando leitura NFC",
                                    tint = if (isErro) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(48.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                text = "Aproxime o celular da posição de destino na prateleira",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isErro) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Encoste na tag NFC afixada na posição física para resolver o endereço.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = InkMuted,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            PrimaryButton(
                                text = "Simular leitura NFC",
                                onClick = { showNfcSimulator = true }
                            )

                            Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

                            OutlinedButton(
                                onClick = { showManualAddressDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(dimensions.primaryButtonHeight),
                                shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Text(
                                    text = "Selecionar endereço manualmente",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // =========================================================================
                // MODO ÚNICO — ETAPA 2: Formulário Pós-NFC (Seção 3.4.3)
                // =========================================================================
                RecebimentoStep.FORM -> {
                    val pos = uiState.resolvedPosicao

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        shape = RoundedCornerShape(dimensions.cardCornerRadius)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(dimensions.paddingMedium),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_check_circle),
                                    contentDescription = "Endereço confirmado",
                                    tint = OkTeal,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Endereço confirmado",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = OkTeal
                                    )
                                    Text(
                                        text = uiState.enderecoFormatado.ifBlank { formatEndereco(pos) },
                                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = uiState.resolvedTagUid?.let { "Tag $it" } ?: "Seleção manual",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                        color = InkMuted
                                    )
                                }
                            }

                            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(dimensions.paddingMedium)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Item a receber",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    TextButton(
                                        onClick = { showNovoItemDialog = true },
                                        modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                                    ) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_add),
                                            contentDescription = null, // Decorativo: o texto "Novo item" descreve a ação
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Novo item", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }

                            Spacer(modifier = Modifier.height(8.dp))

                            ExposedDropdownMenuBox(
                                expanded = itemDropdownExpanded,
                                onExpandedChange = { itemDropdownExpanded = !itemDropdownExpanded }
                            ) {
                                OutlinedTextField(
                                    value = uiState.selectedItem?.let { "${it.sku} - ${it.nome}" } ?: uiState.itemSearchQuery,
                                    onValueChange = {
                                        viewModel.setItemSearchQuery(it)
                                        itemDropdownExpanded = true
                                    },
                                    label = { Text("Selecione o item (SKU ou nome)") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = itemDropdownExpanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true),
                                    singleLine = true,
                                    shape = RoundedCornerShape(dimensions.buttonCornerRadius)
                                )

                                val itensFiltrados = itens.filter {
                                    uiState.itemSearchQuery.isBlank() ||
                                        it.sku.contains(uiState.itemSearchQuery, ignoreCase = true) ||
                                        it.nome.contains(uiState.itemSearchQuery, ignoreCase = true)
                                }

                                ExposedDropdownMenu(
                                    expanded = itemDropdownExpanded,
                                    onDismissRequest = { itemDropdownExpanded = false }
                                ) {
                                    itensFiltrados.forEach { item ->
                                        val itemAgr = itensComEstoque.find { it.item.id == item.id }
                                        val saldoTotal = itemAgr?.saldoTotal ?: 0
                                        val saldoPos = itemAgr?.estoques?.find { it.posicaoId == pos?.id }?.quantidade ?: 0

                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = "${item.sku} - ${item.nome}",
                                                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = "${item.categoria} · Nesta posição: $saldoPos ${formatUnidade(item.unidade)} (Total: $saldoTotal ${formatUnidade(item.unidade)})",
                                                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                        color = InkMuted
                                                    )
                                                }
                                            },
                                            onClick = {
                                                viewModel.selecionarItem(item)
                                                itemDropdownExpanded = false
                                            }
                                        )
                                    }

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "+ Novo item (criar no catálogo)",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            itemDropdownExpanded = false
                                            showNovoItemDialog = true
                                        }
                                    )
                                }
                            }

                            val selected = uiState.selectedItem
                            if (selected != null && pos != null) {
                                val itemAgr = itensComEstoque.find { it.item.id == selected.id }
                                val saldoNaPosicao = itemAgr?.estoques?.find { it.posicaoId == pos.id }?.quantidade ?: 0
                                val saldoTotal = itemAgr?.saldoTotal ?: 0

                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                                    color = SurfaceMuted,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_inventory_2),
                                            contentDescription = null, // Decorativo: o texto ao lado informa o saldo da posição
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        val unidFmt = formatUnidade(selected.unidade)
                                        Text(
                                            text = "Em ${pos.codigo}: $saldoNaPosicao $unidFmt · Após entrada: ${saldoNaPosicao + uiState.quantidade} $unidFmt (Total: ${saldoTotal + uiState.quantidade} $unidFmt)",
                                            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Quantidade",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            QuantityStepper(
                                value = uiState.quantidade,
                                onValueChange = { viewModel.setQuantidade(it) },
                                min = 1,
                                max = 99999,
                                expandField = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = uiState.observacao,
                                onValueChange = { viewModel.setObservacao(it) },
                                label = { Text("Observação (NF, lote ou fornecedor)") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(dimensions.buttonCornerRadius)
                            )

                            if (uiState.formErrorMessage != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                RecebimentoErrorRow(uiState.formErrorMessage ?: "")
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            PrimaryButton(
                                text = "Confirmar entrada",
                                onClick = { viewModel.confirmarEntrada() },
                                enabled = uiState.isFormValid
                            )
                        }
                        }
                    }
                }

                // =========================================================================
                // MODO MÚLTIPLO — ETAPA M1: Montagem da Lista de Itens
                // =========================================================================
                RecebimentoStep.MONTAGEM_LISTA -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        shape = RoundedCornerShape(dimensions.cardCornerRadius)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(dimensions.paddingMedium)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Adicionar item ao lote",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Monte a lista recebida. As posições serão bipadas em seguida.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = InkMuted
                                    )
                                }

                                TextButton(
                                    onClick = { showNovoItemDialog = true },
                                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_add),
                                        contentDescription = null, // Decorativo: o texto "Novo item" descreve a ação
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Novo item", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            ExposedDropdownMenuBox(
                                expanded = itemDropdownExpanded,
                                onExpandedChange = { itemDropdownExpanded = !itemDropdownExpanded }
                            ) {
                                OutlinedTextField(
                                    value = uiState.selectedItem?.let { "${it.sku} - ${it.nome}" } ?: uiState.itemSearchQuery,
                                    onValueChange = {
                                        viewModel.setItemSearchQuery(it)
                                        itemDropdownExpanded = true
                                    },
                                    label = { Text("Selecione o item (SKU ou nome)") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = itemDropdownExpanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true),
                                    singleLine = true,
                                    shape = RoundedCornerShape(dimensions.buttonCornerRadius)
                                )

                                val itensFiltrados = itens.filter {
                                    uiState.itemSearchQuery.isBlank() ||
                                        it.sku.contains(uiState.itemSearchQuery, ignoreCase = true) ||
                                        it.nome.contains(uiState.itemSearchQuery, ignoreCase = true)
                                }

                                ExposedDropdownMenu(
                                    expanded = itemDropdownExpanded,
                                    onDismissRequest = { itemDropdownExpanded = false }
                                ) {
                                    itensFiltrados.forEach { item ->
                                        val itemAgr = itensComEstoque.find { it.item.id == item.id }
                                        val saldoTotal = itemAgr?.saldoTotal ?: 0

                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = "${item.sku} - ${item.nome}",
                                                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = "${item.categoria} · Saldo total: $saldoTotal ${formatUnidade(item.unidade)}",
                                                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                        color = InkMuted
                                                    )
                                                }
                                            },
                                            onClick = {
                                                viewModel.selecionarItem(item)
                                                itemDropdownExpanded = false
                                            }
                                        )
                                    }

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "+ Novo item (criar no catálogo)",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            itemDropdownExpanded = false
                                            showNovoItemDialog = true
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Quantidade",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            QuantityStepper(
                                value = uiState.quantidade,
                                onValueChange = { viewModel.setQuantidade(it) },
                                min = 1,
                                max = 99999,
                                expandField = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = uiState.observacao,
                                onValueChange = { viewModel.setObservacao(it) },
                                label = { Text("Observação opcional (NF, lote)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(dimensions.buttonCornerRadius)
                            )

                            if (uiState.formErrorMessage != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                RecebimentoErrorRow(uiState.formErrorMessage ?: "")
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedButton(
                                onClick = { viewModel.adicionarItemAoLote() },
                                enabled = uiState.canAddToList,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(dimensions.primaryButtonHeight),
                                shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Text(
                                    text = "Adicionar à lista",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (uiState.canAddToList) MaterialTheme.colorScheme.onSurface else InkMuted
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Lista de Itens Adicionados ao Recebimento Múltiplo
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        shape = RoundedCornerShape(dimensions.cardCornerRadius)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(dimensions.paddingMedium)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_inventory_2),
                                    contentDescription = null, // Decorativo: o título ao lado identifica a lista
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = "Lista para recebimento (${uiState.itensLote.size})",
                                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (uiState.itensLote.isEmpty()) {
                                Text(
                                    text = "Nenhum item adicionado. Selecione acima e toque em Adicionar à lista.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = InkMuted
                                )
                            } else {
                                uiState.itensLote.forEachIndexed { idx, loteItem ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${idx + 1}. ${loteItem.item.nome}",
                                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "SKU ${loteItem.item.sku} · ${loteItem.quantidade} ${formatUnidade(loteItem.item.unidade)}",
                                                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                color = InkMuted
                                            )
                                        }

                                        TextButton(
                                            onClick = { viewModel.removerItemDoLote(loteItem.id) },
                                            modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                                        ) {
                                            Text("Remover", color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                    if (idx < uiState.itensLote.lastIndex) {
                                        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            PrimaryButton(
                                text = "Iniciar recebimento guiado",
                                onClick = { viewModel.iniciarRecebimentoGuiado() },
                                enabled = uiState.itensLote.isNotEmpty()
                            )
                        }
                    }
                }

                // =========================================================================
                // MODO MÚLTIPLO — ETAPA M2: Guarda Guiada Passo a Passo via NFC
                // =========================================================================
                RecebimentoStep.GUIADO_NFC -> {
                    val itemAtual = uiState.itemGuiadoAtual
                    if (itemAtual != null) {
                        val isErro = uiState.isErrorFlashingRed
                        val ringColor = if (isErro) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Passo ${uiState.passoGuiadoIndex + 1} de ${uiState.totalPassosGuiados}",
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${itemAtual.quantidade} ${formatUnidade(itemAtual.item.unidade)}",
                                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                color = InkMuted
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { uiState.progressoGuiadoFracao },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = SurfaceMuted
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Card de Leitura NFC da Prateleira onde o item atual será guardado
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, if (isErro) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            shape = RoundedCornerShape(dimensions.cardCornerRadius)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(dimensions.paddingLarge),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(120.dp)
                                        .border(2.dp, ringColor, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(if (isErro) Res.drawable.ic_error else Res.drawable.ic_nfc),
                                        contentDescription = if (isErro) "Erro na leitura NFC" else "Aproxime a etiqueta NFC",
                                        tint = if (isErro) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(48.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(18.dp))

                                Text(
                                    text = "Bipe a tag NFC da posição onde vai guardar:",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = InkMuted,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "${itemAtual.item.nome} (${itemAtual.quantidade} ${formatUnidade(itemAtual.item.unidade)})",
                                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                                    color = if (isErro) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    text = "SKU ${itemAtual.item.sku}",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                    color = InkMuted
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                PrimaryButton(
                                    text = "Simular leitura NFC",
                                    onClick = { showNfcSimulator = true }
                                )

                                Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

                                OutlinedButton(
                                    onClick = { showManualAddressDialog = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(dimensions.primaryButtonHeight),
                                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {
                                    Text(
                                        text = "Selecionar endereço manualmente",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // ETAPA FINAL: Pós-Confirmação e Resumo de Sucesso (Único e Múltiplo)
                // =========================================================================
                RecebimentoStep.SUCCESS -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        shape = RoundedCornerShape(dimensions.cardCornerRadius)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(dimensions.paddingLarge),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_check_circle),
                                contentDescription = "Recebimento confirmado",
                                tint = OkTeal,
                                modifier = Modifier.size(48.dp)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Entrada concluída",
                                style = MaterialTheme.typography.headlineMedium,
                                color = OkTeal,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = uiState.successMessage,
                                style = MaterialTheme.typography.bodyLarge,
                                color = InkMuted,
                                textAlign = TextAlign.Center
                            )

                            if (uiState.resumoRecebimentoMultiplo.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    uiState.resumoRecebimentoMultiplo.forEachIndexed { idx, resumo ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = resumo.item.nome,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "+${resumo.quantidade} ${formatUnidade(resumo.item.unidade)}",
                                                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                                    color = OkTeal
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(Res.drawable.ic_location_on),
                                                    contentDescription = null, // Decorativo: o texto ao lado informa o endereço
                                                    tint = InkMuted,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Text(
                                                    text = resumo.enderecoFormatado,
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                    color = InkMuted
                                                )
                                            }
                                        }
                                        if (idx < uiState.resumoRecebimentoMultiplo.lastIndex) {
                                            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            PrimaryButton(
                                text = "Nova entrada",
                                onClick = { viewModel.iniciarNovaEntrada() }
                            )

                            Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

                            OutlinedButton(
                                onClick = onNavigateBack,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(dimensions.primaryButtonHeight),
                                shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Text(
                                    text = "Voltar ao início",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // SnackbarHost em destaque na base da tela (Seção 3.4.4)
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    // Modal de Exceção: Tag NFC Não Cadastrada (Seção 5.4 e Cenário 2)
    if (uiState.unmappedTagErrorUid != null) {
        val uid = uiState.unmappedTagErrorUid ?: ""
        AlertDialog(
            onDismissRequest = { viewModel.dismissUnmappedTagError() },
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
                    Text("A etiqueta NFC aproximada não possui vínculo ativo no armazém.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "UID $uid",
                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Cadastre a posição ou vincule este UID em Endereços.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissUnmappedTagError()
                        onNavigateEnderecosComUid(uid)
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
                    onClick = { viewModel.dismissUnmappedTagError() },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Fechar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // Modal de Confirmação: Descartar entrada em andamento (Seção 3.7)
    if (showDiscardConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmDialog = false },
            title = {
                Text("Descartar entrada em andamento?", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Text("Os dados preenchidos deste lote serão perdidos. Deseja descartar?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirmDialog = false
                        viewModel.descartarFormularioEVoltarParaScan()
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Descartar")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDiscardConfirmDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Continuar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // Diálogo Seletor de Endereço Manual (Contingência / Web - Seção 3.4.1)
    if (showManualAddressDialog) {
        AlertDialog(
            onDismissRequest = { showManualAddressDialog = false },
            title = { Text("Selecionar endereço manualmente", style = MaterialTheme.typography.titleLarge) },
            text = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(dimensions.cardCornerRadius)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        posicoes.forEachIndexed { idx, pos ->
                            val tagVinculada = tagsNFC.find { it.posicaoId == pos.id }
                            val statusColor = if (tagVinculada != null) OkTeal else WarnAmber
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = dimensions.minTouchTarget)
                                    .clickable {
                                        showManualAddressDialog = false
                                        viewModel.selecionarEnderecoManualmente(pos)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = formatEndereco(pos),
                                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(if (tagVinculada != null) Res.drawable.ic_nfc else Res.drawable.ic_warning),
                                        contentDescription = null, // Decorativo: o texto ao lado informa o status da tag NFC
                                        tint = statusColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = if (tagVinculada != null) "Tag ${tagVinculada.uid}" else "NFC pendente",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                        color = statusColor
                                    )
                                }
                            }
                            if (idx < posicoes.lastIndex) {
                                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(
                    onClick = { showManualAddressDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Cancelar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // Diálogo Rápido: Novo Item no Catálogo (Seção 3.4.3 - [+ Novo Item])
    if (showNovoItemDialog) {
        var novoSku by remember { mutableStateOf("") }
        var novoNome by remember { mutableStateOf("") }
        var novaCategoria by remember { mutableStateOf("Geral") }
        var novaUnidade by remember { mutableStateOf("UN") }
        var novoItemError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showNovoItemDialog = false },
            title = { Text("Cadastrar novo item", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = novoSku,
                        onValueChange = {
                            novoSku = it.uppercase()
                            novoItemError = null
                        },
                        label = { Text("SKU (código único)") },
                        singleLine = true,
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = novoNome,
                        onValueChange = {
                            novoNome = it
                            novoItemError = null
                        },
                        label = { Text("Nome do produto") },
                        singleLine = true,
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = novaCategoria,
                        onValueChange = { novaCategoria = it },
                        label = { Text("Categoria") },
                        singleLine = true,
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = novaUnidade,
                        onValueChange = { novaUnidade = it.uppercase() },
                        label = { Text("Unidade (UN, CX, PAR, MT)") },
                        singleLine = true,
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (novoItemError != null) {
                        RecebimentoErrorRow(novoItemError ?: "")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val res = viewModel.cadastrarNovoItemRapido(
                                sku = novoSku,
                                nome = novoNome,
                                categoria = novaCategoria,
                                unidade = novaUnidade
                            )
                            if (res.isSuccess) {
                                showNovoItemDialog = false
                            } else {
                                novoItemError = res.exceptionOrNull()?.message ?: "Erro ao salvar item."
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
                    onClick = { showNovoItemDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Cancelar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // Diálogo de Simulação NFC
    if (showNfcSimulator) {
        NfcSimulatorDialog(
            tagsDisponiveis = tagsNFC,
            posicoes = posicoes,
            onDismiss = { showNfcSimulator = false },
            onTagSelected = { uid ->
                viewModel.processarLeituraNfc(uid)
            }
        )
    }
}

@Composable
private fun RecebimentoErrorRow(message: String) {
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
