package com.uniso.wms.pickbylight.ui.screens.recebimento

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.nfc.NfcManager
import com.uniso.wms.pickbylight.ui.components.NfcSimulatorDialog
import com.uniso.wms.pickbylight.ui.components.PrimaryButton
import com.uniso.wms.pickbylight.ui.components.QuantityStepper
import com.uniso.wms.pickbylight.ui.theme.AmberWarning
import com.uniso.wms.pickbylight.ui.theme.CoralError
import com.uniso.wms.pickbylight.ui.theme.EmeraldSuccess
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.ShelfRackBlue
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

    // Resolução de endereço completo para listagem manual
    fun formatEndereco(pos: Posicao?): String {
        if (pos == null) return "Endereço não identificado"
        val prat = prateleiras.find { it.id == pos.prateleiraId }
        val est = estantes.find { it.id == prat?.estanteId }
        val estanteStr = est?.nome?.takeIf { it.isNotBlank() } ?: est?.codigo ?: "Estante"
        val pratStr = prat?.codigo?.takeIf { it.isNotBlank() } ?: "Prat"
        return "$estanteStr > $pratStr > ${pos.codigo}"
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

    // Cor de destaque de erro (tela vermelha ao bipar tag desconhecida - Seção 5.4)
    val scanCardBorderColor by animateColorAsState(
        targetValue = if (uiState.isErrorFlashingRed) CoralError else Color.Transparent
    )
    val scanCardBgColor by animateColorAsState(
        targetValue = if (uiState.isErrorFlashingRed) {
            CoralError.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(dimensions.paddingMedium),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cabeçalho da Tela
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recebimento de Itens",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.width(8.dp))

                TextButton(onClick = handleBackNavigation) {
                    Text("Voltar", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Seletor de Modo no topo: [Recebimento Único] e [Recebimento Múltiplo]
            if (uiState.step == RecebimentoStep.SCAN_NFC || uiState.step == RecebimentoStep.MONTAGEM_LISTA) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
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
                            shape = RoundedCornerShape(8.dp),
                            color = if (isUnico) ShelfRackBlue else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { viewModel.selecionarModo(ModoRecebimento.UNICO) }
                        ) {
                            Text(
                                text = "Recebimento Único",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isUnico) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        val isMultiplo = uiState.modo == ModoRecebimento.MULTIPLO
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isMultiplo) ShelfRackBlue else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { viewModel.selecionarModo(ModoRecebimento.MULTIPLO) }
                        ) {
                            Text(
                                text = "Recebimento Múltiplo",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isMultiplo) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(3.dp, scanCardBorderColor, RoundedCornerShape(dimensions.cardCornerRadius)),
                        colors = CardDefaults.cardColors(containerColor = scanCardBgColor),
                        shape = RoundedCornerShape(dimensions.cardCornerRadius)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val infiniteTransition = rememberInfiniteTransition()
                            val scale by infiniteTransition.animateFloat(
                                initialValue = 0.92f,
                                targetValue = 1.08f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000),
                                    repeatMode = RepeatMode.Reverse
                                )
                            )

                            val circleColor = if (uiState.isErrorFlashingRed) CoralError else ShelfRackBlue

                            Box(
                                modifier = Modifier
                                    .size(140.dp)
                                    .scale(scale)
                                    .background(circleColor.copy(alpha = 0.15f), CircleShape)
                                    .border(2.dp, circleColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (uiState.isErrorFlashingRed) "⚠️" else "📡",
                                    style = MaterialTheme.typography.displayMedium
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                text = "Aproxime o celular da posição de destino na prateleira",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.isErrorFlashingRed) CoralError else MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Encoste na tag NFC afixada na posição física para resolver o endereço automaticamente.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            PrimaryButton(
                                text = "Selecionar Endereço Manualmente",
                                onClick = { showManualAddressDialog = true },
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            PrimaryButton(
                                text = "Simular Leitura NFC",
                                onClick = { showNfcSimulator = true },
                                containerColor = ShelfRackBlue
                            )
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
                        colors = CardDefaults.cardColors(containerColor = EmeraldSuccess.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(dimensions.cardCornerRadius)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(EmeraldSuccess, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Endereço Destino Validado:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = uiState.enderecoFormatado.ifBlank { formatEndereco(pos) },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Tag NFC: ${uiState.resolvedTagUid ?: "Seleção Manual"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EmeraldSuccess,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                                Text(
                                    text = "Dados do Item a Receber",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                TextButton(onClick = { showNovoItemDialog = true }) {
                                    Text(
                                        text = "+ Novo Item",
                                        color = ShelfRackBlue,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelLarge
                                    )
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
                                    label = { Text("Selecione o Item (SKU ou Nome)") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = itemDropdownExpanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true),
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp)
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
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "Cat: ${item.categoria} • Nesta posição: $saldoPos ${item.unidade} (Total: $saldoTotal ${item.unidade})",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
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
                                                text = "+ Novo Item (Criar no Catálogo)",
                                                color = ShelfRackBlue,
                                                fontWeight = FontWeight.Bold
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
                                    shape = RoundedCornerShape(6.dp),
                                    color = ShelfRackBlue.copy(alpha = 0.10f),
                                    border = BorderStroke(1.dp, ShelfRackBlue.copy(alpha = 0.30f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "📦 Saldo atual em ${pos.codigo}: $saldoNaPosicao ${selected.unidade} • Após entrada: ${saldoNaPosicao + uiState.quantidade} ${selected.unidade} (Total geral: ${saldoTotal + uiState.quantidade} ${selected.unidade})",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ShelfRackBlue,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Quantidade a Inserir:",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
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
                                shape = RoundedCornerShape(8.dp)
                            )

                            if (uiState.formErrorMessage != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = uiState.formErrorMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CoralError,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            PrimaryButton(
                                text = "Confirmar Entrada",
                                onClick = { viewModel.confirmarEntrada() },
                                enabled = uiState.isFormValid,
                                containerColor = EmeraldSuccess
                            )
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
                                        text = "Adicionar Item ao Lote",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Monte a lista de itens recebidos. As prateleiras serão bipadas passo a passo em seguida.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                TextButton(onClick = { showNovoItemDialog = true }) {
                                    Text(
                                        text = "+ Novo Item",
                                        color = ShelfRackBlue,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelLarge
                                    )
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
                                    label = { Text("Selecione o Item (SKU ou Nome)") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = itemDropdownExpanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true),
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp)
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
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "Cat: ${item.categoria} • Saldo Total: $saldoTotal ${item.unidade}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
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
                                                text = "+ Novo Item (Criar no Catálogo)",
                                                color = ShelfRackBlue,
                                                fontWeight = FontWeight.Bold
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
                                text = "Quantidade:",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
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
                                shape = RoundedCornerShape(8.dp)
                            )

                            if (uiState.formErrorMessage != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = uiState.formErrorMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CoralError,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            PrimaryButton(
                                text = "Adicionar à Lista",
                                onClick = { viewModel.adicionarItemAoLote() },
                                enabled = uiState.canAddToList,
                                containerColor = ShelfRackBlue
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Lista de Itens Adicionados ao Recebimento Múltiplo
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(dimensions.cardCornerRadius)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(dimensions.paddingMedium)
                        ) {
                            Text(
                                text = "📦 Lista para Recebimento (${uiState.itensLote.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            if (uiState.itensLote.isEmpty()) {
                                Text(
                                    text = "Nenhum item adicionado ainda. Selecione um item acima e clique em [Adicionar à Lista].",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                uiState.itensLote.forEachIndexed { idx, loteItem ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${idx + 1}. ${loteItem.item.nome}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "SKU: ${loteItem.item.sku} • Qtd: ${loteItem.quantidade} ${loteItem.item.unidade}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = ShelfRackBlue,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }

                                            TextButton(
                                                onClick = { viewModel.removerItemDoLote(loteItem.id) }
                                            ) {
                                                Text("Remover", color = CoralError, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            PrimaryButton(
                                text = "Iniciar Recebimento Guiado",
                                onClick = { viewModel.iniciarRecebimentoGuiado() },
                                enabled = uiState.itensLote.isNotEmpty(),
                                containerColor = EmeraldSuccess
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
                        // Cabeçalho de Progresso "Passo X de Y: Item A (10 un)"
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Passo ${uiState.passoGuiadoIndex + 1} de ${uiState.totalPassosGuiados}: ${itemAtual.item.nome} (${itemAtual.quantidade} ${itemAtual.item.unidade})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = ShelfRackBlue
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "SKU: ${itemAtual.item.sku} • Quantidade a guardar: ${itemAtual.quantidade} ${itemAtual.item.unidade}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                LinearProgressIndicator(
                                    progress = { uiState.progressoGuiadoFracao },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = ShelfRackBlue
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Card de Leitura NFC da Prateleira onde o item atual será guardado
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(3.dp, scanCardBorderColor, RoundedCornerShape(dimensions.cardCornerRadius)),
                            colors = CardDefaults.cardColors(containerColor = scanCardBgColor),
                            shape = RoundedCornerShape(dimensions.cardCornerRadius)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val infiniteTransition = rememberInfiniteTransition()
                                val scale by infiniteTransition.animateFloat(
                                    initialValue = 0.92f,
                                    targetValue = 1.08f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1000),
                                        repeatMode = RepeatMode.Reverse
                                    )
                                )

                                val circleColor = if (uiState.isErrorFlashingRed) CoralError else EmeraldSuccess

                                Box(
                                    modifier = Modifier
                                        .size(130.dp)
                                        .scale(scale)
                                        .background(circleColor.copy(alpha = 0.15f), CircleShape)
                                        .border(2.dp, circleColor, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (uiState.isErrorFlashingRed) "⚠️" else "🏷️",
                                        style = MaterialTheme.typography.displayMedium
                                    )
                                }

                                Spacer(modifier = Modifier.height(18.dp))

                                Text(
                                    text = "Bipe a Tag NFC da prateleira onde vai guardar:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "${itemAtual.item.nome} (${itemAtual.quantidade} ${itemAtual.item.unidade})",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (uiState.isErrorFlashingRed) CoralError else EmeraldSuccess,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                PrimaryButton(
                                    text = "Simular Leitura NFC",
                                    onClick = { showNfcSimulator = true },
                                    containerColor = ShelfRackBlue
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                PrimaryButton(
                                    text = "Selecionar Endereço Manualmente",
                                    onClick = { showManualAddressDialog = true },
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
                        shape = RoundedCornerShape(dimensions.cardCornerRadius)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "✅",
                                style = MaterialTheme.typography.displayMedium
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Entrada Concluída com Sucesso!",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldSuccess
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = uiState.successMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            if (uiState.resumoRecebimentoMultiplo.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    uiState.resumoRecebimentoMultiplo.forEach { resumo ->
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "• ${resumo.item.nome}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "+${resumo.quantidade} ${resumo.item.unidade}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = EmeraldSuccess
                                                )
                                            }
                                            Text(
                                                text = "📍 ${resumo.enderecoFormatado}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = ShelfRackBlue,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            PrimaryButton(
                                text = "Nova Entrada",
                                onClick = { viewModel.iniciarNovaEntrada() },
                                containerColor = ShelfRackBlue
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            PrimaryButton(
                                text = "Voltar ao Dashboard",
                                onClick = onNavigateBack,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                Text(
                    text = "Tag NFC não cadastrada no sistema!",
                    fontWeight = FontWeight.Bold,
                    color = CoralError
                )
            },
            text = {
                Column {
                    Text("A etiqueta NFC aproximada não possui vínculo ativo no banco de dados do armazém.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = CoralError.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "UID Lido: $uid",
                            fontWeight = FontWeight.Bold,
                            color = CoralError,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Cadastre a posição primeiro ou vincule este UID na Gestão de Endereços.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissUnmappedTagError()
                        onNavigateEnderecosComUid(uid)
                    }
                ) {
                    Text("Cadastrar Agora", color = CoralError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUnmappedTagError() }) {
                    Text("Fechar")
                }
            }
        )
    }

    // Modal de Confirmação: Descartar entrada em andamento (Seção 3.7)
    if (showDiscardConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmDialog = false },
            title = {
                Text("Descartar entrada em andamento?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("Os dados preenchidos deste lote serão perdidos. Deseja realmente descartar?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmDialog = false
                        viewModel.descartarFormularioEVoltarParaScan()
                    }
                ) {
                    Text("Descartar", color = CoralError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmDialog = false }) {
                    Text("Continuar Preenchendo")
                }
            }
        )
    }

    // Diálogo Seletor de Endereço Manual (Contingência / Web - Seção 3.4.1)
    if (showManualAddressDialog) {
        AlertDialog(
            onDismissRequest = { showManualAddressDialog = false },
            title = { Text("Selecionar Endereço Manualmente", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    posicoes.forEach { pos ->
                        val tagVinculada = tagsNFC.find { it.posicaoId == pos.id }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    showManualAddressDialog = false
                                    viewModel.selecionarEnderecoManualmente(pos)
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = formatEndereco(pos),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (tagVinculada != null) "🏷️ Tag: ${tagVinculada.uid}" else "⚠️ Pendente NFC",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (tagVinculada != null) EmeraldSuccess else AmberWarning
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showManualAddressDialog = false }) {
                    Text("Cancelar")
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
            title = { Text("Cadastrar Novo Item no Catálogo", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = novoSku,
                        onValueChange = {
                            novoSku = it.uppercase()
                            novoItemError = null
                        },
                        label = { Text("SKU (Código único)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = novoNome,
                        onValueChange = {
                            novoNome = it
                            novoItemError = null
                        },
                        label = { Text("Nome do Produto") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = novaCategoria,
                        onValueChange = { novaCategoria = it },
                        label = { Text("Categoria") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = novaUnidade,
                        onValueChange = { novaUnidade = it.uppercase() },
                        label = { Text("Unidade (UN, CX, PAR, MT)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (novoItemError != null) {
                        Text(
                            text = novoItemError ?: "",
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
                    }
                ) {
                    Text("Salvar Item", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNovoItemDialog = false }) {
                    Text("Cancelar")
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
