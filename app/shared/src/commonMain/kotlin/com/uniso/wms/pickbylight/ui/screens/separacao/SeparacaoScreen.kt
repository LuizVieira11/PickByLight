package com.uniso.wms.pickbylight.ui.screens.separacao

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.nfc.NfcManager
import com.uniso.wms.pickbylight.ui.components.NfcSimulatorDialog
import com.uniso.wms.pickbylight.ui.components.PrimaryButton
import com.uniso.wms.pickbylight.ui.components.QuantityStepper
import com.uniso.wms.pickbylight.ui.components.ScreenHeader
import com.uniso.wms.pickbylight.ui.theme.InkMuted
import com.uniso.wms.pickbylight.ui.theme.LedAmber
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.OkTeal
import com.uniso.wms.pickbylight.ui.theme.OnStrong
import com.uniso.wms.pickbylight.ui.theme.SurfaceMuted
import com.uniso.wms.pickbylight.ui.theme.WarnAmber
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.painterResource
import pickbylight.app.shared.generated.resources.Res
import pickbylight.app.shared.generated.resources.ic_check_circle
import pickbylight.app.shared.generated.resources.ic_dangerous
import pickbylight.app.shared.generated.resources.ic_error
import pickbylight.app.shared.generated.resources.ic_inventory_2
import pickbylight.app.shared.generated.resources.ic_list_alt
import pickbylight.app.shared.generated.resources.ic_lock
import pickbylight.app.shared.generated.resources.ic_nfc
import pickbylight.app.shared.generated.resources.ic_timer
import pickbylight.app.shared.generated.resources.ic_warning

@Composable
fun SeparacaoScreen(
    repository: WmsRepository,
    mqttService: MqttService,
    feedbackController: FeedbackController,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateEnderecosComUid: ((String) -> Unit)? = null,
    viewModel: SeparacaoViewModel = viewModel {
        SeparacaoViewModel(repository, mqttService, feedbackController)
    }
) {
    val dimensions = LocalWmsDimensions.current
    val scrollState = rememberScrollState()

    val uiState by viewModel.uiState.collectAsState()

    val estantes by repository.getEstantes().collectAsState(initial = emptyList())
    val prateleiras by repository.getPrateleiras().collectAsState(initial = emptyList())
    val posicoes by repository.getPosicoes().collectAsState(initial = emptyList())
    val tagsNFC by repository.getTagsNFC().collectAsState(initial = emptyList())
    val itens by repository.getItens().collectAsState(initial = emptyList())
    val itensComEstoque by repository.getItensComEstoque().collectAsState(initial = emptyList())

    // Estado da Etapa 1: Chave é itemId -> quantidade total desejada
    var searchQuery by remember { mutableStateOf("") }
    val selectedQuantities = remember { mutableStateMapOf<String, Int>() }

    // Diálogos de controle locais
    var showCancelPickingConfirmDialog by remember { mutableStateOf(false) }
    var showNfcSimulator by remember { mutableStateOf(false) }

    // Formatação de endereço hierárquico
    fun formatEndereco(est: Estante?, prat: Prateleira?, pos: Posicao?): String {
        val estanteStr = est?.nome?.takeIf { it.isNotBlank() } ?: est?.codigo ?: "Estante"
        val pratStr = prat?.codigo?.takeIf { it.isNotBlank() } ?: "Prat"
        val posStr = pos?.codigo?.takeIf { it.isNotBlank() } ?: "Pos"
        return "$estanteStr > $pratStr > $posStr"
    }

    // Interceptação do botão Voltar (Seção 3.7 da Especificação)
    PlatformBackHandler {
        when (uiState.etapa) {
            EtapaSeparacao.SELECAO_E_RESUMO -> onNavigateBack()
            EtapaSeparacao.VALIDACAO_NFC, EtapaSeparacao.CONFIRMAR_QUANTIDADE -> {
                showCancelPickingConfirmDialog = true
            }
            EtapaSeparacao.CONCLUIDO -> {
                viewModel.resetarParaSelecao()
                onNavigateBack()
            }
        }
    }

    // Escuta eventos NFC durante a Etapa 2 (Validação Poka-Yoke / Picking Flexível)
    LaunchedEffect(uiState.etapa, uiState.passoAtualIndex) {
        if (uiState.etapa == EtapaSeparacao.VALIDACAO_NFC) {
            NfcManager.scannedTagFlow.collectLatest { scannedUid ->
                viewModel.processarLeituraNfc(
                    scannedUid = scannedUid,
                    allTags = tagsNFC,
                    allPosicoes = posicoes,
                    allPrateleiras = prateleiras,
                    allEstantes = estantes
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            bottomBar = {
                if (uiState.etapa == EtapaSeparacao.SELECAO_E_RESUMO) {
                    val totalUnidades = selectedQuantities.values.sum()
                    val totalItens = selectedQuantities.count { it.value > 0 }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding(),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = dimensions.screenHorizontalPadding, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_list_alt),
                                        contentDescription = null, // Decorativo: o texto "Resumo do pedido" descreve a seção
                                        tint = InkMuted,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = "Resumo do pedido:",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = InkMuted
                                    )
                                }
                                Text(
                                    text = "$totalItens ${if (totalItens == 1) "item" else "itens"} • $totalUnidades un",
                                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                    color = if (totalUnidades > 0) MaterialTheme.colorScheme.onSurface else InkMuted
                                )
                            }

                            PrimaryButton(
                                text = if (totalUnidades > 0) "Iniciar coleta" else "Selecione ao menos 1 item",
                                onClick = {
                                    viewModel.iniciarColeta(
                                        itensDisponiveis = itens,
                                        posicoes = posicoes,
                                        prateleiras = prateleiras,
                                        estantes = estantes,
                                        tagsNfc = tagsNFC,
                                        quantidadesSelecionadas = selectedQuantities
                                    )
                                },
                                enabled = totalUnidades > 0
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = dimensions.screenHorizontalPadding, vertical = dimensions.paddingMedium),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScreenHeader(
                title = "Separação",
                actionLabel = "Sair",
                onAction = {
                    if (uiState.etapa == EtapaSeparacao.VALIDACAO_NFC || uiState.etapa == EtapaSeparacao.CONFIRMAR_QUANTIDADE) {
                        showCancelPickingConfirmDialog = true
                    } else {
                        onNavigateBack()
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Banner de Modo Manual (aviso sem cor de erro)
            if (uiState.isModoManual) {
                Surface(
                    shape = RoundedCornerShape(dimensions.cardCornerRadius),
                    color = SurfaceMuted,
                    border = BorderStroke(1.dp, WarnAmber),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_warning),
                            contentDescription = null, // Decorativo: o texto ao lado informa o modo manual
                            tint = WarnAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Modo manual: confirme pela leitura NFC.",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = WarnAmber
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Alerta de Timeout de 60s Expirado (erro que cancelou a operação)
            if (uiState.timeoutCancelMessage != null) {
                Surface(
                    shape = RoundedCornerShape(dimensions.cardCornerRadius),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_error),
                                contentDescription = null, // Decorativo: o texto ao lado informa o tempo esgotado
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = uiState.timeoutCancelMessage ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        TextButton(
                            onClick = { viewModel.dismissTimeoutCancelMessage() },
                            modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                        ) {
                            Text("OK", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            when (uiState.etapa) {
                // =========================================================================
                // ETAPA 1: Seleção de Itens (Pré-Coleta - Seção 3.5.1)
                // Resumo e botão ficam fixos no bottomBar do Scaffold.
                // =========================================================================
                EtapaSeparacao.SELECAO_E_RESUMO -> {
                    val itensComSaldo = itensComEstoque.filter { it.saldoTotal > 0 }
                    val itensFiltrados = itensComSaldo.filter { itemComEstoque ->
                        searchQuery.isBlank() ||
                            itemComEstoque.item.nome.contains(searchQuery, ignoreCase = true) ||
                            itemComEstoque.item.sku.contains(searchQuery, ignoreCase = true)
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Selecione os itens e quantidades. O LED acende ao iniciar a coleta.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = InkMuted
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("Buscar por nome ou SKU...") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(dimensions.buttonCornerRadius)
                                )
                            }
                        }

                        if (itensFiltrados.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (searchQuery.isNotBlank()) {
                                            "Nenhum produto com saldo para \"$searchQuery\"."
                                        } else {
                                            "Nenhum produto possui estoque alocado."
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = InkMuted
                                    )
                                }
                            }
                        } else {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    shape = RoundedCornerShape(dimensions.cardCornerRadius)
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        itensFiltrados.forEachIndexed { idx, itemComEstoque ->
                                            val item = itemComEstoque.item
                                            val selectedQty = selectedQuantities[item.id] ?: 0

                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        if (selectedQty > 0) SurfaceMuted.copy(alpha = 0.45f)
                                                        else MaterialTheme.colorScheme.surface
                                                    )
                                                    .padding(dimensions.paddingMedium)
                                            ) {
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
                                                    if (item.emSeparacao) {
                                                        Spacer(modifier = Modifier.width(8.dp))
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
                                                                    modifier = Modifier.size(20.dp)
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

                                                Spacer(modifier = Modifier.height(4.dp))

                                                Text(
                                                    text = "SKU: ${item.sku} • Saldo: ${itemComEstoque.saldoTotal} ${item.unidade}",
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                    color = if (itemComEstoque.isAbaixoMinimo) WarnAmber else InkMuted
                                                )

                                                if (selectedQty > 0) {
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = SurfaceMuted
                                                    ) {
                                                        Text(
                                                            text = "No pedido: $selectedQty ${item.unidade}",
                                                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(10.dp))

                                                if (item.emSeparacao) {
                                                    Text(
                                                        text = "Item em separação por outro operador",
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        color = InkMuted
                                                    )
                                                } else {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = "Qtd.",
                                                            style = MaterialTheme.typography.labelLarge,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )

                                                        QuantityStepper(
                                                            value = selectedQty,
                                                            onValueChange = { newQty ->
                                                                if (newQty <= 0) {
                                                                    selectedQuantities.remove(item.id)
                                                                } else {
                                                                    selectedQuantities[item.id] = newQty.coerceAtMost(itemComEstoque.saldoTotal)
                                                                }
                                                            },
                                                            min = 0,
                                                            max = itemComEstoque.saldoTotal
                                                        )
                                                    }
                                                }
                                            }

                                            if (idx < itensFiltrados.lastIndex) {
                                                HorizontalDivider(
                                                    thickness = 1.dp,
                                                    color = MaterialTheme.colorScheme.outlineVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // ETAPA 2: Sequenciamento Guiado & Validação NFC (Seção 3.5.2 & 3.5.3)
                // =========================================================================
                EtapaSeparacao.VALIDACAO_NFC -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val passo = uiState.passoAtual
                        if (passo != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Parada ${uiState.passoAtualIndex + 1} de ${uiState.totalPassos}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_timer),
                                        contentDescription = null, // Decorativo: o texto ao lado informa o tempo restante
                                        tint = WarnAmber,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = "${uiState.timeoutOperadorSegundos}s",
                                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                        color = WarnAmber
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { uiState.progressoFracao },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.secondary,
                                trackColor = SurfaceMuted
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Card único de orientação (1 nível de card, sem sombra)
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
                                        .padding(dimensions.paddingMedium),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (uiState.isModoManual) {
                                        Surface(
                                            shape = RoundedCornerShape(dimensions.cardCornerRadius),
                                            color = SurfaceMuted,
                                            border = BorderStroke(1.dp, WarnAmber),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    painter = painterResource(Res.drawable.ic_warning),
                                                    contentDescription = null, // Decorativo: o texto ao lado informa o modo manual
                                                    tint = WarnAmber,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Modo manual: confirme pela leitura NFC.",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = WarnAmber
                                                )
                                            }
                                        }
                                    } else {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .background(LedAmber, CircleShape)
                                                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                            )
                                            Text(
                                                text = "LED aceso em ${passo.posicao.ledId}",
                                                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Modo manual já indicado acima; guia textual fica no endereço-herói abaixo

                                    Text(
                                        text = "${passo.estante.codigo} · ${passo.estante.nome}",
                                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                        color = InkMuted,
                                        textAlign = TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = "${passo.prateleira.codigo} · ${passo.posicao.codigo}",
                                        style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    HorizontalDivider(
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(Res.drawable.ic_inventory_2),
                                                contentDescription = null, // Decorativo: o título ao lado identifica os itens a retirar
                                                tint = InkMuted,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Text(
                                                text = "Itens a retirar (${passo.itens.size})",
                                                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        passo.itens.forEachIndexed { idx, itemColeta ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = itemColeta.item.nome,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = "SKU: ${itemColeta.item.sku}",
                                                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                        color = InkMuted
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "${itemColeta.quantidadeDesejada} ${itemColeta.item.unidade}",
                                                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                                    color = OkTeal
                                                )
                                            }
                                            if (idx < passo.itens.lastIndex) {
                                                HorizontalDivider(
                                                    thickness = 1.dp,
                                                    color = MaterialTheme.colorScheme.outlineVariant
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))

                                    HorizontalDivider(
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Icon(
                                        painter = painterResource(Res.drawable.ic_nfc),
                                        contentDescription = "Aproxime a etiqueta NFC",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(48.dp)
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = "Aproxime o celular da etiqueta NFC",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    if (passo.tagNfc == null) {
                                        PrimaryButton(
                                            text = "Validar sem NFC",
                                            onClick = { viewModel.validarManualmenteSemNfc() }
                                        )
                                        Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))
                                        OutlinedButton(
                                            onClick = { showNfcSimulator = true },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .defaultMinSize(minHeight = dimensions.minTouchTarget),
                                            shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                        ) {
                                            Text("Simular NFC", color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    } else {
                                        PrimaryButton(
                                            text = "Simular NFC",
                                            onClick = { showNfcSimulator = true }
                                        )
                                    }

                                    if (uiState.isModoManual) {
                                        Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))
                                        OutlinedButton(
                                            onClick = { viewModel.acionarHardwarePassoAtual(forceRetry = true) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .defaultMinSize(minHeight = dimensions.minTouchTarget),
                                            shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                        ) {
                                            Text("Tentar LED novamente", color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(dimensions.paddingSmall))

                                    TextButton(
                                        onClick = { showCancelPickingConfirmDialog = true },
                                        modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                                    ) {
                                        Text(
                                            text = "Cancelar coleta",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // ETAPA 3: Confirmação de Quantidade e Baixa de Todos os Itens do Passo (Seção 3.5.5)
                // =========================================================================
                EtapaSeparacao.CONFIRMAR_QUANTIDADE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val passo = uiState.passoAtual
                        if (passo != null) {
                            val quantidadesConfirmadas = remember(uiState.passoAtualIndex) {
                                mutableStateMapOf<String, Int>().apply {
                                    passo.itens.forEach { put(it.item.id, it.quantidadeDesejada) }
                                }
                            }

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
                                        .padding(dimensions.paddingMedium),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_check_circle),
                                        contentDescription = "Passo confirmado",
                                        tint = OkTeal,
                                        modifier = Modifier.size(48.dp)
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = "Local correto. Confirme as quantidades.",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = OkTeal,
                                        textAlign = TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = "${passo.prateleira.codigo} · ${passo.posicao.codigo}",
                                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${passo.estante.codigo} · ${passo.estante.nome}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = InkMuted
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    HorizontalDivider(
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )

                                    // Ajuste de quantidade de cada item agrupado (sem card dentro de card)
                                    passo.itens.forEachIndexed { idx, itemColeta ->
                                        val confirmedQty = quantidadesConfirmadas[itemColeta.item.id] ?: itemColeta.quantidadeDesejada

                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp)
                                        ) {
                                            Text(
                                                text = itemColeta.item.nome,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "SKU: ${itemColeta.item.sku} • Pedido: ${itemColeta.quantidadeDesejada} ${itemColeta.item.unidade}",
                                                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                color = InkMuted
                                            )

                                            Spacer(modifier = Modifier.height(10.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Qtd.",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )

                                                QuantityStepper(
                                                    value = confirmedQty,
                                                    onValueChange = { quantidadesConfirmadas[itemColeta.item.id] = it },
                                                    min = 1,
                                                    max = itemColeta.quantidadeDesejada
                                                )
                                            }
                                        }

                                        if (idx < passo.itens.lastIndex) {
                                            HorizontalDivider(
                                                thickness = 1.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(dimensions.paddingLarge))

                                    PrimaryButton(
                                        text = "Confirmar retirada (${passo.itens.size})",
                                        onClick = {
                                            viewModel.finalizarRetiradaPassoAtual(quantidadesConfirmadas)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // ETAPA 4: Conclusão da Coleta (Seção 3.5.6)
                // =========================================================================
                EtapaSeparacao.CONCLUIDO -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
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
                                    contentDescription = "Separação concluída",
                                    tint = OkTeal,
                                    modifier = Modifier.size(48.dp)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "Coleta finalizada",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = OkTeal,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "${uiState.resumoFinal.size} itens em ${uiState.totalPassos} paradas.",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                    color = InkMuted,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    uiState.resumoFinal.forEachIndexed { idx, itemColeta ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${itemColeta.quantidadeConfirmada}x ${itemColeta.item.nome}",
                                                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = itemColeta.item.sku,
                                                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                                color = InkMuted
                                            )
                                        }
                                        if (idx < uiState.resumoFinal.lastIndex) {
                                            HorizontalDivider(
                                                thickness = 1.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(dimensions.paddingLarge))

                                PrimaryButton(
                                    text = "Nova separação",
                                    onClick = { viewModel.resetarParaSelecao() }
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
        }
        }

        // Overlay Poka-Yoke em tela cheia: Tag errada / não cadastrada (DESIGN.md seção 4)
        if (uiState.isVisualFlashingRed || uiState.showWrongTagModal != null) {
            val (lida, esperada) = uiState.showWrongTagModal ?: ("" to "")
            val unmappedUid = uiState.unmappedTagUid
            val passo = uiState.passoAtual
            val enderecoEsperado = when {
                esperada.isNotBlank() -> esperada
                passo != null -> "${passo.prateleira.codigo} · ${passo.posicao.codigo}"
                else -> "esperado"
            }

            PokaYokeErrorOverlay(
                lida = lida,
                enderecoEsperado = enderecoEsperado,
                unmappedUid = unmappedUid,
                onDismiss = { viewModel.dismissWrongTagModal() },
                onCadastrarAgora = if (unmappedUid != null && onNavigateEnderecosComUid != null) {
                    {
                        viewModel.dismissWrongTagModal()
                        viewModel.cancelarColetaEVoltar {
                            onNavigateEnderecosComUid(unmappedUid)
                        }
                    }
                } else null
            )
        }
    }

    // Modal de Concorrência: Item em Separação por Outro Operador (erro bloqueante)
    if (uiState.showConcurrencyLockError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissConcurrencyLockError() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_error),
                        contentDescription = null, // Decorativo: o título ao lado descreve o erro de concorrência
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Item em separação por outro operador",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            text = {
                Text(uiState.showConcurrencyLockError ?: "")
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.dismissConcurrencyLockError() },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                ) {
                    Text("Entendido", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    // Modal de Timeout ESP32 (aviso com ação de recuperação em WarnAmber)
    if (uiState.showHardwareTimeoutModal) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissHardwareTimeoutModal() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_warning),
                        contentDescription = null, // Decorativo: o título ao lado descreve o aviso
                        tint = WarnAmber,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Hardware não respondeu",
                        style = MaterialTheme.typography.titleLarge,
                        color = WarnAmber
                    )
                }
            },
            text = {
                Text("O ESP32 não confirmou em 5 segundos. Verifique Wi-Fi e alimentação dos LEDs.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissHardwareTimeoutModal()
                        viewModel.acionarHardwarePassoAtual(forceRetry = true)
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                ) {
                    Text("Tentar novamente", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.continuarEmModoManual() },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                ) {
                    Text("Modo manual", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // Modal de Confirmação: Cancelar Coleta (ação destrutiva neutra, sem vermelho)
    if (showCancelPickingConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCancelPickingConfirmDialog = false },
            title = {
                Text("Cancelar coleta?", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Text("O LED será apagado e os itens serão liberados. Deseja retornar ao início?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelPickingConfirmDialog = false
                        viewModel.cancelarColetaEVoltar(onComplete = onNavigateBack)
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                ) {
                    Text("Cancelar coleta", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCancelPickingConfirmDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                ) {
                    Text("Continuar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // Simulador NFC (para testes no emulador e Web)
    if (showNfcSimulator) {
        NfcSimulatorDialog(
            tagsDisponiveis = tagsNFC,
            posicoes = posicoes,
            onDismiss = { showNfcSimulator = false },
            onTagSelected = { uid ->
                NfcManager.onTagScanned(uid)
            }
        )
    }
}

@Composable
private fun PokaYokeErrorOverlay(
    lida: String,
    enderecoEsperado: String,
    unmappedUid: String?,
    onDismiss: () -> Unit,
    onCadastrarAgora: (() -> Unit)?
) {
    val dimensions = LocalWmsDimensions.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.error)
            .drawBehind {
                val bandHeight = 16.dp.toPx()
                val stripeWidth = 14.dp.toPx()
                val step = stripeWidth * 2f
                val stripeColor = OnStrong.copy(alpha = 0.35f)
                clipRect(0f, 0f, size.width, bandHeight) {
                    var x = -bandHeight
                    while (x < size.width + bandHeight) {
                        drawLine(
                            color = stripeColor,
                            start = Offset(x, bandHeight),
                            end = Offset(x + bandHeight, 0f),
                            strokeWidth = stripeWidth
                        )
                        x += step
                    }
                }
                val bottomTop = size.height - bandHeight
                clipRect(0f, bottomTop, size.width, size.height) {
                    var x = -bandHeight
                    while (x < size.width + bandHeight) {
                        drawLine(
                            color = stripeColor,
                            start = Offset(x, size.height),
                            end = Offset(x + bandHeight, bottomTop),
                            strokeWidth = stripeWidth
                        )
                        x += step
                    }
                }
            }
            .navigationBarsPadding()
            .padding(horizontal = dimensions.screenHorizontalPadding, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_dangerous),
                contentDescription = "Tag NFC incorreta",
                tint = OnStrong,
                modifier = Modifier.size(96.dp)
            )

            Text(
                text = if (unmappedUid != null) {
                    "Tag errada. Etiqueta não cadastrada. Devolva ao endereço $enderecoEsperado."
                } else {
                    "Tag errada. Devolva ao endereço $enderecoEsperado."
                },
                style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"),
                color = OnStrong,
                textAlign = TextAlign.Center
            )

            if (lida.isNotBlank()) {
                Text(
                    text = "Bipado: $lida",
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = OnStrong,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimensions.primaryButtonHeight),
                shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text(
                    text = "Verificar novamente",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (onCadastrarAgora != null) {
                OutlinedButton(
                    onClick = onCadastrarAgora,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dimensions.primaryButtonHeight),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.5.dp, OnStrong)
                ) {
                    Text(
                        text = "Cadastrar agora",
                        style = MaterialTheme.typography.titleMedium,
                        color = OnStrong
                    )
                }
            }
        }
    }
}
