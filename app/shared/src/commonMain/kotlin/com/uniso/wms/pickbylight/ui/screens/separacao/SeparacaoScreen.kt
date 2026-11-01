package com.uniso.wms.pickbylight.ui.screens.separacao

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.EstoquePosicaoDetalhe
import com.uniso.wms.pickbylight.domain.model.ItemComEstoque
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
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
import com.uniso.wms.pickbylight.ui.theme.ShelfRowOrange
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import kotlinx.coroutines.flow.collectLatest

@Composable
fun SeparacaoScreen(
    repository: WmsRepository,
    mqttService: MqttService,
    feedbackController: FeedbackController,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeparacaoViewModel = remember(repository, mqttService, feedbackController) {
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

    // Escuta eventos NFC durante a Etapa 2 (Validação Poka-Yoke)
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

    // Cor dinâmica de feedback de validação visual
    val cardBorderColor by animateColorAsState(
        targetValue = when {
            uiState.isVisualFlashingGreen -> EmeraldSuccess
            uiState.isVisualFlashingRed -> CoralError
            else -> Color.Transparent
        }
    )

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(dimensions.paddingMedium)
                .padding(bottom = if (uiState.etapa == EtapaSeparacao.SELECAO_E_RESUMO) 110.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        // Banner de Modo Manual (quando ESP32 não responde)
        if (uiState.isModoManual) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CoralError, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "⚠️ MODO MANUAL — LED indisponível. Validação por endereço textual.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Barra Superior da Tela
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Separação Pick-by-Light",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.width(8.dp))

            TextButton(
                onClick = {
                    if (uiState.etapa == EtapaSeparacao.VALIDACAO_NFC || uiState.etapa == EtapaSeparacao.CONFIRMAR_QUANTIDADE) {
                        showCancelPickingConfirmDialog = true
                    } else {
                        onNavigateBack()
                    }
                }
            ) {
                Text("Sair", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (uiState.etapa) {
            // =========================================================================
            // ETAPA 1: Seleção e Resumo do Pedido (Pré-Coleta - Seção 3.5.1)
            // Nenhum comando MQTT é disparado e nenhum LED acende nesta etapa.
            // =========================================================================
            EtapaSeparacao.SELECAO_E_RESUMO -> {
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
                            text = "Seleção de Itens (Pré-Coleta)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Selecione os itens e as quantidades desejadas. O hardware será acionado apenas ao iniciar a coleta.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Campo de Busca
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Buscar item por nome ou SKU...") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        val itensComSaldo = itensComEstoque.filter { it.saldoTotal > 0 }
                        val itensFiltrados = itensComSaldo.filter { itemComEstoque ->
                            searchQuery.isBlank() ||
                                itemComEstoque.item.nome.contains(searchQuery, ignoreCase = true) ||
                                itemComEstoque.item.sku.contains(searchQuery, ignoreCase = true)
                        }

                        if (itensFiltrados.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (searchQuery.isNotBlank()) "Nenhum produto com saldo encontrado para \"$searchQuery\"." else "Nenhum produto possui estoque alocado nas posições.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            itensFiltrados.forEach { itemComEstoque ->
                                val item = itemComEstoque.item
                                val selectedQty = selectedQuantities[item.id] ?: 0

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selectedQty > 0) ShelfRackBlue.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp)
                                    ) {
                                        // Linha 1: Nome do produto e Badge de Bloqueio Otimista
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
                                            if (item.emSeparacao) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .background(CoralError, RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
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

                                        Spacer(modifier = Modifier.height(4.dp))

                                        // Linha 2: SKU: [X] • Saldo Total: [Y] UN
                                        Text(
                                            text = "SKU: ${item.sku} • Saldo Total: ${itemComEstoque.saldoTotal} ${item.unidade}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (itemComEstoque.isAbaixoMinimo) CoralError else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        // Linha 3: Badge/Chip "No pedido: X UN" alinhado à esquerda
                                        if (selectedQty > 0) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = ShelfRackBlue.copy(alpha = 0.14f),
                                                border = BorderStroke(1.dp, ShelfRackBlue.copy(alpha = 0.35f)),
                                                modifier = Modifier.align(Alignment.Start)
                                            ) {
                                                Text(
                                                    text = "No pedido: $selectedQty ${item.unidade}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ShelfRackBlue,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    maxLines = 1
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // Parte inferior: Stepper (- [ 0 ] +) com espaçamento adequado para toque
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (item.emSeparacao) "Item bloqueado para separação" else "Quantidade para separação:",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (item.emSeparacao) CoralError else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            Spacer(modifier = Modifier.width(12.dp))

                                            if (!item.emSeparacao) {
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
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // =============================================================
                        // SEÇÃO DE RESUMO DO PEDIDO (Pré-Coleta - Seção 3.5.1)
                        // Mostra apenas os itens e quantidades totais solicitadas.
                        // =============================================================
                        val itensSelecionadosList = selectedQuantities.filter { it.value > 0 }.mapNotNull { (itemId, qty) ->
                            val itm = itens.find { it.id == itemId } ?: return@mapNotNull null
                            itm to qty
                        }

                        val totalUnidades = itensSelecionadosList.sumOf { it.second }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (totalUnidades > 0) ShelfRackBlue.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "📋 Resumo do Pedido",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                if (totalUnidades == 0) {
                                    Text(
                                        text = "Nenhum item selecionado. Ajuste a quantidade desejada dos itens acima para compor a coleta.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = "$totalUnidades ${if (totalUnidades > 1) "unidades selecionadas" else "unidade selecionada"} em ${itensSelecionadosList.size} ${if (itensSelecionadosList.size > 1) "itens" else "item"}.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ShelfRackBlue
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    itensSelecionadosList.forEach { (item, qty) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "• ${item.nome}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "$qty ${item.unidade}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = ShelfRackBlue
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Botão Primário: [Iniciar Coleta] (Seção 3.5.1)
                        PrimaryButton(
                            text = if (totalUnidades > 0) {
                                "Iniciar Coleta ($totalUnidades un)"
                            } else {
                                "Selecione ao menos 1 item"
                            },
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
                            enabled = totalUnidades > 0,
                            containerColor = EmeraldSuccess
                        )
                    }
                }
            }

            // =========================================================================
            // ETAPA 2: Sequenciamento Guiado & Validação NFC Poka-Yoke (Seção 3.5.2 & 3.5.3)
            // LED aceso na posição atual; agrupa múltiplos itens da mesma posição.
            // =========================================================================
            EtapaSeparacao.VALIDACAO_NFC -> {
                val passo = uiState.passoAtual
                if (passo != null) {
                    // Barra de Progresso "Item X de N"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Item ${uiState.passoAtualIndex + 1} de ${uiState.totalPassos}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = ShelfRackBlue
                                )
                                Text(
                                    text = "⏱️ ${uiState.timeoutOperadorSegundos}s para bipar",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (uiState.timeoutOperadorSegundos < 15) CoralError else AmberWarning
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Parada ${uiState.passoAtualIndex + 1} de ${uiState.totalPassos} • ${passo.itens.size} ${if (passo.itens.size > 1) "produtos agrupados" else "produto"} nesta posição",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { uiState.progressoFracao },
                                modifier = Modifier.fillMaxWidth(),
                                color = ShelfRackBlue,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Card de Orientação com Endereço e Status do LED
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(3.dp, cardBorderColor, RoundedCornerShape(dimensions.cardCornerRadius)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(dimensions.cardCornerRadius)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Badge de Status do LED
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (uiState.isModoManual) CoralError else EmeraldSuccess,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (uiState.isModoManual) "⚠️ MODO MANUAL (LED Indisponível)" else "🟢 LED VERDE ACESO (${passo.posicao.ledId})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Endereço Esperado em Grande Destaque (Estante > Prat > Pos)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ShelfRackBlue.copy(alpha = 0.10f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.5.dp, ShelfRackBlue.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "DESTINO DE COLETA (ESTANTE & POSIÇÃO)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = ShelfRackBlue
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Estante: ${passo.estante.nome}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "📍 ${formatEndereco(passo.estante, passo.prateleira, passo.posicao)}",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = ShelfRackBlue,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Posição: ${passo.posicao.codigo}  •  Prateleira: ${passo.prateleira.codigo}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Lista de Itens a Retirar Nesta Posição (Agrupados)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = if (passo.itens.size > 1) "📦 ITENS AGRUPADOS NESTA POSIÇÃO (${passo.itens.size}):" else "📦 ITEM A RETIRAR:",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = ShelfRackBlue
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    passo.itens.forEach { itemColeta ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = itemColeta.item.nome,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "SKU: ${itemColeta.item.sku}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                text = "Pegue ${itemColeta.quantidadeDesejada} ${itemColeta.item.unidade} na ${passo.posicao.codigo}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = EmeraldSuccess
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            // Animação Pulsante NFC
                            val infiniteTransition = rememberInfiniteTransition()
                            val pulseScale by infiniteTransition.animateFloat(
                                initialValue = 0.94f,
                                targetValue = 1.06f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(900),
                                    repeatMode = RepeatMode.Reverse
                                )
                            )

                            Box(
                                modifier = Modifier
                                    .size(110.dp)
                                    .scale(pulseScale)
                                    .background(EmeraldSuccess.copy(alpha = 0.15f), CircleShape)
                                    .border(2.dp, EmeraldSuccess, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🏷️", style = MaterialTheme.typography.headlineLarge)
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Aproxime o celular da etiqueta NFC na posição iluminada",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // Botão Simular Leitura NFC (para testes no emulador / web)
                            PrimaryButton(
                                text = "Simular Leitura NFC",
                                onClick = { showNfcSimulator = true },
                                containerColor = ShelfRackBlue
                            )

                            // Botão de contingência se em Modo Manual ou sem Tag física
                            if (uiState.isModoManual || passo.tagNfc == null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                PrimaryButton(
                                    text = "Validar Posição Manualmente",
                                    onClick = { viewModel.validarManualmenteSemNfc() },
                                    containerColor = ShelfRowOrange
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Botão Cancelar Coleta (Seção 3.5.3)
                            PrimaryButton(
                                text = "Cancelar Coleta",
                                onClick = { showCancelPickingConfirmDialog = true },
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = CoralError
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // ETAPA 3: Confirmação de Quantidade e Baixa de Todos os Itens do Passo (Seção 3.5.5)
            // =========================================================================
            EtapaSeparacao.CONFIRMAR_QUANTIDADE -> {
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
                        shape = RoundedCornerShape(dimensions.cardCornerRadius)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("✅", style = MaterialTheme.typography.displaySmall)

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Local correto! Confirme as quantidades retiradas.",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldSuccess
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ShelfRackBlue.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "📍 ${formatEndereco(passo.estante, passo.prateleira, passo.posicao)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = ShelfRackBlue
                                    )
                                    Text(
                                        text = "Estante: ${passo.estante.nome}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Exibição e ajuste de quantidade de cada item agrupado
                            passo.itens.forEach { itemColeta ->
                                val confirmedQty = quantidadesConfirmadas[itemColeta.item.id] ?: itemColeta.quantidadeDesejada

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = itemColeta.item.nome,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "SKU: ${itemColeta.item.sku} • Qtd a Retirar: ${itemColeta.quantidadeDesejada} ${itemColeta.item.unidade}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Confirmar: $confirmedQty ${itemColeta.item.unidade}",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = ShelfRackBlue
                                            )

                                            QuantityStepper(
                                                value = confirmedQty,
                                                onValueChange = { quantidadesConfirmadas[itemColeta.item.id] = it },
                                                min = 1,
                                                max = itemColeta.quantidadeDesejada
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            PrimaryButton(
                                text = "Confirmar Retirada da Posição (${passo.itens.size} ${if (passo.itens.size > 1) "itens" else "item"})",
                                onClick = {
                                    viewModel.finalizarRetiradaPassoAtual(quantidadesConfirmadas)
                                },
                                containerColor = EmeraldSuccess
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // ETAPA 4: Conclusão da Coleta (Seção 3.5.6)
            // =========================================================================
            EtapaSeparacao.CONCLUIDO -> {
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
                        Text("🎉", style = MaterialTheme.typography.displayMedium)

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Coleta finalizada!",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldSuccess
                        )

                        Text(
                            text = "${uiState.resumoFinal.size} itens separados com sucesso em ${uiState.totalPassos} paradas físicas.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Resumo dos itens separados
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            uiState.resumoFinal.forEach { itemColeta ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${itemColeta.quantidadeConfirmada}x ${itemColeta.item.nome}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "SKU: ${itemColeta.item.sku}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ShelfRackBlue,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        PrimaryButton(
                            text = "Nova Separação",
                            onClick = { viewModel.resetarParaSelecao() },
                            containerColor = EmeraldSuccess
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        PrimaryButton(
                            text = "Voltar ao Dashboard",
                            onClick = onNavigateBack,
                            containerColor = ShelfRackBlue
                        )
                    }
                }
            }
        }
    }

    // Barra de ação fixa inferior para a Etapa 1 (Seleção e Iniciar Coleta)
        // Permanece sempre visível acima da barra de navegação do Android e acima do teclado virtual.
        if (uiState.etapa == EtapaSeparacao.SELECAO_E_RESUMO) {
            val totalUnidades = selectedQuantities.values.sum()
            val totalItens = selectedQuantities.count { it.value > 0 }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (totalUnidades > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Total no pedido:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$totalUnidades ${if (totalUnidades > 1) "unidades" else "unidade"} ($totalItens ${if (totalItens > 1) "itens" else "item"})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = ShelfRackBlue
                            )
                        }
                    }
                    PrimaryButton(
                        text = if (totalUnidades > 0) {
                            "Iniciar Coleta ($totalUnidades un)"
                        } else {
                            "Selecione ao menos 1 item"
                        },
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
                        enabled = totalUnidades > 0,
                        containerColor = EmeraldSuccess
                    )
                }
            }
        }
    }

    // Modal de Concorrência: Item em Separação por Outro Operador (Seção 5.5)
    if (uiState.showConcurrencyLockError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissConcurrencyLockError() },
            title = {
                Text(
                    text = "Item em Separação por Outro Operador",
                    fontWeight = FontWeight.Bold,
                    color = CoralError
                )
            },
            text = {
                Text(uiState.showConcurrencyLockError ?: "")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissConcurrencyLockError() }) {
                    Text("Entendido", color = CoralError, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Modal de Timeout ESP32 (Seção 5.2)
    if (uiState.showHardwareTimeoutModal) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissHardwareTimeoutModal() },
            title = {
                Text("Hardware não respondeu", fontWeight = FontWeight.Bold, color = CoralError)
            },
            text = {
                Text("O ESP32 não enviou confirmação (ACK) em 5 segundos. Verifique a conexão Wi-Fi e a alimentação dos LEDs.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissHardwareTimeoutModal()
                        viewModel.acionarHardwarePassoAtual()
                    }
                ) {
                    Text("Tentar Novamente", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.continuarEmModoManual()
                    }
                ) {
                    Text("Continuar em Modo Manual", color = ShelfRowOrange)
                }
            }
        )
    }

    // Modal de Confirmação: Cancelar Coleta (Seção 3.7)
    if (showCancelPickingConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCancelPickingConfirmDialog = false },
            title = {
                Text("Cancelar coleta?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("O LED será apagado e os itens serão liberados do lock. Deseja realmente retornar ao Dashboard?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelPickingConfirmDialog = false
                        viewModel.cancelarColetaEVoltar(onComplete = onNavigateBack)
                    }
                ) {
                    Text("Cancelar Coleta", color = CoralError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelPickingConfirmDialog = false }) {
                    Text("Continuar Coleta")
                }
            }
        )
    }

    // Modal Poka-Yoke: Local Incorreto (Seção 3.5.4 & Cenário 4)
    if (uiState.showWrongTagModal != null) {
        val (lida, esperada) = uiState.showWrongTagModal ?: ("" to "")
        AlertDialog(
            onDismissRequest = { viewModel.dismissWrongTagModal() },
            title = {
                Text("Local incorreto!", fontWeight = FontWeight.Bold, color = CoralError)
            },
            text = {
                Column {
                    Text("A etiqueta bipada pertence a outro endereço:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = CoralError.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "❌ Bipado: $lida",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = CoralError,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = EmeraldSuccess.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "🎯 Esperado: $esperada",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldSuccess,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Verifique a luz verde na estante correta e aproxime da etiqueta correta.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissWrongTagModal() }) {
                    Text("Verificar Novamente", color = CoralError, fontWeight = FontWeight.Bold)
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
