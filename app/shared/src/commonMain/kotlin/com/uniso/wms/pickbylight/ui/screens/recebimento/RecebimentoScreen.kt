package com.uniso.wms.pickbylight.ui.screens.recebimento

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.domain.model.Movimentacao
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.model.TagNFC
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
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
import com.uniso.wms.pickbylight.util.FeedbackScenario
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class RecebimentoStep {
    SCAN_NFC,
    FORM,
    SUCCESS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecebimentoScreen(
    repository: WmsRepository,
    feedbackController: FeedbackController,
    onNavigateBack: () -> Unit,
    onNavigateEnderecosComUid: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalWmsDimensions.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val estantes by repository.getEstantes().collectAsState(initial = emptyList())
    val prateleiras by repository.getPrateleiras().collectAsState(initial = emptyList())
    val posicoes by repository.getPosicoes().collectAsState(initial = emptyList())
    val tagsNFC by repository.getTagsNFC().collectAsState(initial = emptyList())
    val itens by repository.getItens().collectAsState(initial = emptyList())

    var currentStep by remember { mutableStateOf(RecebimentoStep.SCAN_NFC) }
    var resolvedPosicao by remember { mutableStateOf<Posicao?>(null) }
    var resolvedTagUid by remember { mutableStateOf<String?>(null) }

    // Campos do formulário
    var selectedItem by remember { mutableStateOf<Item?>(null) }
    var quantidade by remember { mutableStateOf(1) }
    var observacao by remember { mutableStateOf("") }
    var itemSearchQuery by remember { mutableStateOf("") }
    var itemDropdownExpanded by remember { mutableStateOf(false) }

    // Diálogos de controle
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }
    var showNfcSimulator by remember { mutableStateOf(false) }
    var showManualAddressDialog by remember { mutableStateOf(false) }
    var showNovoItemDialog by remember { mutableStateOf(false) }
    var unmappedTagErrorUid by remember { mutableStateOf<String?>(null) }
    var isSuccessMessage by remember { mutableStateOf("") }

    val hasDirtyForm = selectedItem != null || quantidade > 1 || observacao.isNotBlank()

    // Resolução de endereço completo em string formatada com hierarquia Estante > Prateleira > Posição
    fun formatEndereco(pos: Posicao?): String {
        if (pos == null) return "Endereço não identificado"
        val prat = prateleiras.find { it.id == pos.prateleiraId }
        val est = estantes.find { it.id == prat?.estanteId }
        val estanteStr = est?.nome?.takeIf { it.isNotBlank() } ?: est?.codigo ?: "Estante"
        val pratStr = prat?.codigo?.takeIf { it.isNotBlank() } ?: "Prat"
        return "$estanteStr > $pratStr > ${pos.codigo}"
    }

    // Processamento de leitura NFC (Seção 3.4 & Cenário 1 e 2)
    fun processarLeituraNfc(uid: String) {
        coroutineScope.launch {
            val tag = repository.getTagByUid(uid)
            if (tag == null) {
                // Cenário 5.4: Tag não cadastrada -> vibração longa 600ms + erro sonoro
                feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
                unmappedTagErrorUid = uid
            } else {
                val pos = repository.getPosicaoById(tag.posicaoId)
                if (pos != null) {
                    resolvedPosicao = pos
                    resolvedTagUid = tag.uid
                    feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                    currentStep = RecebimentoStep.FORM
                }
            }
        }
    }

    // Escuta eventos globais do NfcManager quando na etapa SCAN_NFC
    LaunchedEffect(currentStep) {
        if (currentStep == RecebimentoStep.SCAN_NFC) {
            NfcManager.scannedTagFlow.collectLatest { uid ->
                processarLeituraNfc(uid)
            }
        }
    }

    // Interceptação do botão Voltar (Seção 3.7)
    PlatformBackHandler {
        when (currentStep) {
            RecebimentoStep.SCAN_NFC -> onNavigateBack()
            RecebimentoStep.FORM -> {
                if (hasDirtyForm) {
                    showDiscardConfirmDialog = true
                } else {
                    currentStep = RecebimentoStep.SCAN_NFC
                }
            }
            RecebimentoStep.SUCCESS -> {
                currentStep = RecebimentoStep.SCAN_NFC
                selectedItem = null
                quantidade = 1
                observacao = ""
            }
        }
    }

    Column(
        modifier = modifier
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

            TextButton(
                onClick = {
                    if (currentStep == RecebimentoStep.FORM && hasDirtyForm) {
                        showDiscardConfirmDialog = true
                    } else {
                        onNavigateBack()
                    }
                }
            ) {
                Text("Voltar", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (currentStep) {
            // ETAPA 1: Leitura NFC de Destino (Seção 3.4.1)
            RecebimentoStep.SCAN_NFC -> {
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
                        // Animação visual pulsante de ondas NFC
                        val infiniteTransition = rememberInfiniteTransition()
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 0.92f,
                            targetValue = 1.08f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000),
                                repeatMode = RepeatMode.Reverse
                            )
                        )

                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .scale(scale)
                                .background(ShelfRackBlue.copy(alpha = 0.15f), CircleShape)
                                .border(2.dp, ShelfRackBlue, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "📡",
                                style = MaterialTheme.typography.displayMedium
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "Aproxime o celular da posição de destino na prateleira",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Encoste na tag NFC afixada na posição física para resolver o endereço automaticamente.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Botão de seleção manual de contingência (Seção 3.4.1)
                        PrimaryButton(
                            text = "Selecionar Endereço Manualmente",
                            onClick = { showManualAddressDialog = true },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Botão de simulação NFC (para ambiente emulador e web)
                        PrimaryButton(
                            text = "Simular Leitura NFC",
                            onClick = { showNfcSimulator = true },
                            containerColor = ShelfRackBlue
                        )
                    }
                }
            }

            // ETAPA 2: Formulário Pós-NFC (Seção 3.4.3)
            RecebimentoStep.FORM -> {
                // Cabeçalho fixo com o endereço validado e o UID da tag
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
                                text = formatEndereco(resolvedPosicao),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Tag NFC: ${resolvedTagUid ?: "Seleção Manual"}",
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
                        Text(
                            text = "Dados do Item a Receber",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Dropdown com busca integrada por SKU ou Nome + Atalho "+ Novo Item"
                        ExposedDropdownMenuBox(
                            expanded = itemDropdownExpanded,
                            onExpandedChange = { itemDropdownExpanded = !itemDropdownExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedItem?.let { "${it.sku} - ${it.nome}" } ?: itemSearchQuery,
                                onValueChange = {
                                    itemSearchQuery = it
                                    selectedItem = null
                                    itemDropdownExpanded = true
                                },
                                label = { Text("Selecione o Item (SKU ou Nome)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = itemDropdownExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                shape = RoundedCornerShape(8.dp)
                            )

                            val itensFiltrados = itens.filter {
                                itemSearchQuery.isBlank() ||
                                    it.sku.contains(itemSearchQuery, ignoreCase = true) ||
                                    it.nome.contains(itemSearchQuery, ignoreCase = true)
                            }

                            ExposedDropdownMenu(
                                expanded = itemDropdownExpanded,
                                onDismissRequest = { itemDropdownExpanded = false }
                            ) {
                                itensFiltrados.forEach { item ->
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
                                                    text = "Cat: ${item.categoria} | Unidade: ${item.unidade}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedItem = item
                                            itemSearchQuery = "${item.sku} - ${item.nome}"
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

                        Spacer(modifier = Modifier.height(16.dp))

                        // Stepper de Quantidade
                        Text(
                            text = "Quantidade a Inserir:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        QuantityStepper(
                            value = quantidade,
                            onValueChange = { quantidade = it },
                            min = 1,
                            max = 99999
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Campo Observação Opcional
                        OutlinedTextField(
                            value = observacao,
                            onValueChange = { observacao = it },
                            label = { Text("Observação (NF, lote ou fornecedor)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Botão Primário Confirmar Entrada (>= 56dp)
                        val isValid = selectedItem != null && quantidade > 0 && resolvedPosicao != null

                        PrimaryButton(
                            text = "Confirmar Entrada",
                            onClick = {
                                val item = selectedItem ?: return@PrimaryButton
                                val pos = resolvedPosicao ?: return@PrimaryButton

                                coroutineScope.launch {
                                    // Incrementa quantidade na posição selecionada
                                    val estoqueAtual = repository.getEstoque(item.id, pos.id)?.quantidade ?: 0
                                    val novaQtd = estoqueAtual + quantidade
                                    repository.atualizarEstoquePosicao(item.id, pos.id, novaQtd)

                                    // Grava movimentação ENTRADA
                                    val mov = Movimentacao(
                                        tipo = TipoMovimentacao.ENTRADA,
                                        itemId = item.id,
                                        posicaoId = pos.id,
                                        quantidade = quantidade,
                                        observacao = observacao.takeIf { it.isNotBlank() }
                                    )
                                    repository.registrarMovimentacao(mov)

                                    feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                                    isSuccessMessage = "Entrada registrada: ${item.nome} × $quantidade em ${formatEndereco(pos)}"
                                    currentStep = RecebimentoStep.SUCCESS
                                }
                            },
                            enabled = isValid,
                            containerColor = EmeraldSuccess
                        )
                    }
                }
            }

            // ETAPA 3: Pós-Confirmação (Seção 3.4.4)
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
                            text = isSuccessMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        PrimaryButton(
                            text = "Nova Entrada",
                            onClick = {
                                selectedItem = null
                                quantidade = 1
                                observacao = ""
                                resolvedPosicao = null
                                resolvedTagUid = null
                                currentStep = RecebimentoStep.SCAN_NFC
                            },
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

    // Modal de Alerta: Tag NFC Não Cadastrada (Seção 5.4 e Cenário 2)
    if (unmappedTagErrorUid != null) {
        val uid = unmappedTagErrorUid ?: ""
        AlertDialog(
            onDismissRequest = { unmappedTagErrorUid = null },
            title = {
                Text(
                    text = "Tag NFC Não Cadastrada!",
                    fontWeight = FontWeight.Bold,
                    color = CoralError
                )
            },
            text = {
                Column {
                    Text("A etiqueta NFC aproximada não possui vínculo ativo no banco de dados do armazém.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "UID Lido: $uid",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Deseja cadastrar e vincular este endereço agora?")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        unmappedTagErrorUid = null
                        onNavigateEnderecosComUid(uid)
                    }
                ) {
                    Text("Cadastrar Agora", color = CoralError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { unmappedTagErrorUid = null }) {
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
                Text("Os dados preenchidos deste lote serão perdidos. Deseja realmente retornar à leitura NFC?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmDialog = false
                        selectedItem = null
                        quantidade = 1
                        observacao = ""
                        currentStep = RecebimentoStep.SCAN_NFC
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

    // Diálogo Seletor de Endereço Manual (Contingência / Web)
    if (showManualAddressDialog) {
        AlertDialog(
            onDismissRequest = { showManualAddressDialog = false },
            title = { Text("Selecione o Endereço Manualmente", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    posicoes.forEach { pos ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    resolvedPosicao = pos
                                    resolvedTagUid = tagsNFC.find { it.posicaoId == pos.id }?.uid
                                    showManualAddressDialog = false
                                    currentStep = RecebimentoStep.FORM
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text(
                                text = formatEndereco(pos),
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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

    // Diálogo Rápido: Novo Item no Catálogo
    if (showNovoItemDialog) {
        var novoSku by remember { mutableStateOf("") }
        var novoNome by remember { mutableStateOf("") }
        var novaCategoria by remember { mutableStateOf("Geral") }
        var novaUnidade by remember { mutableStateOf("UN") }

        AlertDialog(
            onDismissRequest = { showNovoItemDialog = false },
            title = { Text("Cadastrar Novo Item no Catálogo", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = novoSku,
                        onValueChange = { novoSku = it.uppercase() },
                        label = { Text("SKU (Código único)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = novoNome,
                        onValueChange = { novoNome = it },
                        label = { Text("Nome do Produto") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = novaCategoria,
                        onValueChange = { novaCategoria = it },
                        label = { Text("Categoria") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = novaUnidade,
                        onValueChange = { novaUnidade = it.uppercase() },
                        label = { Text("Unidade (UN, CX, MT)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (novoSku.isNotBlank() && novoNome.isNotBlank()) {
                            val newItem = Item(
                                sku = novoSku.trim(),
                                nome = novoNome.trim(),
                                categoria = novaCategoria.trim(),
                                unidade = novaUnidade.trim()
                            )
                            coroutineScope.launch {
                                repository.salvarItem(newItem)
                                selectedItem = newItem
                                itemSearchQuery = "${newItem.sku} - ${newItem.nome}"
                                showNovoItemDialog = false
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
                processarLeituraNfc(uid)
            }
        )
    }
}
