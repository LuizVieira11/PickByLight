package com.uniso.wms.pickbylight.ui.screens.dashboard

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
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
import com.uniso.wms.pickbylight.data.mqtt.MqttConnectionState
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.data.settings.AppSettings
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.ui.components.PrimaryButton
import com.uniso.wms.pickbylight.ui.responsive.WindowSizeClass
import com.uniso.wms.pickbylight.ui.theme.AmberWarning
import com.uniso.wms.pickbylight.ui.theme.CoralError
import com.uniso.wms.pickbylight.ui.theme.EmeraldSuccess
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.ShelfBinGreen
import com.uniso.wms.pickbylight.ui.theme.ShelfRackBlue
import com.uniso.wms.pickbylight.ui.theme.ShelfRowOrange
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.FeedbackScenario
import kotlinx.coroutines.launch

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.uniso.wms.pickbylight.util.PlatformBackHandler

@Composable
fun DashboardScreen(
    windowSize: WindowSizeClass,
    repository: WmsRepository,
    mqttService: MqttService,
    feedbackController: FeedbackController,
    onNavigateRecebimento: () -> Unit,
    onNavigateSeparacao: () -> Unit,
    onNavigateEnderecos: () -> Unit,
    onNavigateCatalogo: () -> Unit,
    onNavigateHistorico: () -> Unit,
    onNavigateAjustes: () -> Unit,
    modifier: Modifier = Modifier,
    onCloseApp: (() -> Unit)? = null
) {
    val dimensions = LocalWmsDimensions.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var showExitDialog by remember { mutableStateOf(false) }

    PlatformBackHandler(enabled = true) {
        showExitDialog = true
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Sair do Aplicativo", fontWeight = FontWeight.Bold) },
            text = { Text("Deseja realmente sair do sistema WMS Pick-by-Light?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        onCloseApp?.invoke()
                    }
                ) {
                    Text("Sair", color = CoralError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    val estantes by repository.getEstantes().collectAsState(initial = emptyList())
    val prateleiras by repository.getPrateleiras().collectAsState(initial = emptyList())
    val posicoes by repository.getPosicoes().collectAsState(initial = emptyList())
    val tagsNFC by repository.getTagsNFC().collectAsState(initial = emptyList())
    val itens by repository.getItens().collectAsState(initial = emptyList())
    val itensComEstoque by repository.getItensComEstoque().collectAsState(initial = emptyList())
    val movimentacoes by repository.getMovimentacoes().collectAsState(initial = emptyList())

    val mqttState by mqttService.connectionState.collectAsState()
    val pendingCommands by mqttService.pendingCommandsCount.collectAsState()
    val brokerUrl by AppSettings.brokerUrlState.collectAsState()

    var pingFeedbackText by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }

    val totalEstoque = itensComEstoque.sumOf { it.saldoTotal }
    val itensBaixoEstoque = itensComEstoque.count { it.isAbaixoMinimo }
    val entradasHoje = movimentacoes.count { it.tipo == TipoMovimentacao.ENTRADA }
    val saidasHoje = movimentacoes.count { it.tipo == TipoMovimentacao.SAIDA }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(dimensions.paddingMedium),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Cabeçalho de Status IoT e Ações Superiores (Seção 3.1)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(dimensions.cardCornerRadius)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Linha 1: Título do App isolado com destaque + Ações (Histórico e Ajustes)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "WMS Pick-by-Light",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Botão Histórico (Seção 3.1 & 3.8)
                        IconButton(onClick = onNavigateHistorico) {
                            Text("📋", style = MaterialTheme.typography.titleMedium)
                        }

                        // Ícone de Ajustes ⚙️ (Seção 3.1 & 3.6)
                        IconButton(onClick = onNavigateAjustes) {
                            Text("⚙️", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Linha 2: Informações do Broker e status MQTT organizados sem quebra de título
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "BROKER MQTT",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = brokerUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Badge MQTT
                        val (badgeBg, badgeText) = when (mqttState) {
                            MqttConnectionState.CONNECTED -> EmeraldSuccess to "Conectado"
                            MqttConnectionState.CONNECTING -> AmberWarning to "Reconectando..."
                            MqttConnectionState.DISCONNECTED -> CoralError to "Offline"
                            MqttConnectionState.ERROR -> CoralError to "Erro MQTT"
                        }

                        Box(
                            modifier = Modifier
                                .background(badgeBg, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }

                if (pendingCommands > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚠️ Comandos pendentes na fila: $pendingCommands",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberWarning,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

        // Cards Resumo Operacional em Grade Adaptativa (2x2 Compact / 4x1 Medium/Expanded)
        val isCompact = windowSize.widthSizeClass.isCompact
        val gridColumns = if (isCompact) GridCells.Fixed(2) else GridCells.Fixed(4)
        val gridHeight = if (isCompact) 215.dp else 105.dp

        LazyVerticalGrid(
            columns = gridColumns,
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false
        ) {
            item {
                StatusCard(
                    title = "Total Itens",
                    value = "$totalEstoque un",
                    subtitle = "${itens.size} SKUs",
                    accentColor = ShelfRackBlue
                )
            }
            item {
                StatusCard(
                    title = "Movimentos",
                    value = "+$entradasHoje / -$saidasHoje",
                    subtitle = "Entradas / Saídas",
                    accentColor = ShelfRowOrange
                )
            }
            item {
                StatusCard(
                    title = "Posições NFC",
                    value = "${tagsNFC.size}/${posicoes.size}",
                    subtitle = if (tagsNFC.size == posicoes.size) "100% Mapeadas" else "${posicoes.size - tagsNFC.size} Pendentes",
                    accentColor = if (tagsNFC.size == posicoes.size) EmeraldSuccess else AmberWarning
                )
            }
            item {
                StatusCard(
                    title = "Estoque Baixo",
                    value = itensBaixoEstoque.toString(),
                    subtitle = if (itensBaixoEstoque > 0) "Atenção!" else "OK",
                    accentColor = if (itensBaixoEstoque > 0) CoralError else EmeraldSuccess
                )
            }
        }

        Spacer(modifier = Modifier.height(dimensions.paddingLarge))

        // Menu de Ações Principais (>= 56dp) (Seção 3.1)
        Text(
            text = "Menu de Operações WMS",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

        PrimaryButton(
            text = "Recebimento de Itens",
            onClick = onNavigateRecebimento,
            containerColor = ShelfRackBlue
        )

        Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

        PrimaryButton(
            text = "Separação de Pedidos (Pick-by-Light)",
            onClick = onNavigateSeparacao,
            containerColor = EmeraldSuccess
        )

        Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

        PrimaryButton(
            text = "Gestão de Endereços",
            onClick = onNavigateEnderecos,
            containerColor = ShelfRowOrange
        )

        Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

        PrimaryButton(
            text = "Catálogo de Itens",
            onClick = onNavigateCatalogo,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(dimensions.paddingLarge))

        // Seção Hardware IoT & Diagnóstico Rápido
        Text(
            text = "Comunicação IoT & Testes de Hardware",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

        PrimaryButton(
            text = if (isPinging) "Pingando ESP32..." else "Ping ESP32 (PING)",
            onClick = {
                coroutineScope.launch {
                    isPinging = true
                    val result = mqttService.enviarPing()
                    isPinging = false
                    if (result.isSuccess) {
                        val latencia = result.getOrNull() ?: 0L
                        pingFeedbackText = "Ping OK: ${latencia}ms de latência"
                        feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                    } else {
                        pingFeedbackText = "Falha no Ping: ${result.exceptionOrNull()?.message}"
                        feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
                    }
                }
            },
            isLoading = isPinging,
            containerColor = ShelfRackBlue
        )

        if (pingFeedbackText != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = pingFeedbackText ?: "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (pingFeedbackText?.startsWith("Ping OK") == true) EmeraldSuccess else CoralError
            )
        }

        Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

        PrimaryButton(
            text = "Testar LED ESP32 (TESTE_LED)",
            onClick = {
                coroutineScope.launch {
                    val result = mqttService.testarLed(
                        estanteLedId = "EST_A_LED1",
                        prateleiraLedId = "PRAT_A1_LED1",
                        posicaoLedId = "POS_A1_01",
                        cor = "AZUL",
                        duracaoMs = 3000
                    )
                    if (result.isSuccess) {
                        feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                    } else {
                        feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
                    }
                }
            },
            containerColor = ShelfRowOrange
        )

        Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

        PrimaryButton(
            text = "Resetar Dados de Demonstração (Seed)",
            onClick = {
                coroutineScope.launch {
                    repository.resetarDadosDemo()
                    feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                }
            },
            containerColor = AmberWarning,
            contentColor = Color.White
        )
    }
}

@Composable
private fun StatusCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .height(4.dp)
                    .background(accentColor, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
