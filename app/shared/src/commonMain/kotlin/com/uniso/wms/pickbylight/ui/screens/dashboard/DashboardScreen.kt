package com.uniso.wms.pickbylight.ui.screens.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.uniso.wms.pickbylight.ui.components.ScreenHeader
import com.uniso.wms.pickbylight.ui.responsive.WindowSizeClass
import com.uniso.wms.pickbylight.ui.theme.InkMuted
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.OkTeal
import com.uniso.wms.pickbylight.ui.theme.OnStrong
import com.uniso.wms.pickbylight.ui.theme.WarnAmber
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.FeedbackScenario
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import pickbylight.app.shared.generated.resources.Res
import pickbylight.app.shared.generated.resources.ic_chevron_right
import pickbylight.app.shared.generated.resources.ic_inventory_2
import pickbylight.app.shared.generated.resources.ic_lightbulb
import pickbylight.app.shared.generated.resources.ic_list_alt
import pickbylight.app.shared.generated.resources.ic_move_to_inbox
import pickbylight.app.shared.generated.resources.ic_route
import pickbylight.app.shared.generated.resources.ic_sensors
import pickbylight.app.shared.generated.resources.ic_settings
import pickbylight.app.shared.generated.resources.ic_shelves
import pickbylight.app.shared.generated.resources.ic_warning
import pickbylight.app.shared.generated.resources.ic_wifi

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
            title = { Text("Sair do aplicativo", style = MaterialTheme.typography.titleLarge) },
            text = { Text("Deseja encerrar o sistema WMS Pick-by-Light?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        onCloseApp?.invoke()
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                ) {
                    Text("Sair", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExitDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                ) {
                    Text("Cancelar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

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
            .padding(horizontal = dimensions.screenHorizontalPadding, vertical = dimensions.paddingMedium),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ScreenHeader(
            title = "WMS Pick-by-Light",
            trailingContent = {
                IconButton(onClick = onNavigateHistorico) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_list_alt),
                        contentDescription = "Histórico",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(onClick = onNavigateAjustes) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_settings),
                        contentDescription = "Ajustes",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Linha do Broker MQTT direta sobre Paper; chip só quando conectado (banner já cobre offline/reconectando)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Broker MQTT",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = brokerUrl,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    color = InkMuted
                )
            }

            if (mqttState == MqttConnectionState.CONNECTED) {
                Surface(
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    color = OkTeal
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_wifi),
                            contentDescription = null, // Decorativo: o rótulo ao lado informa o estado da conexão
                            tint = OnStrong,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Conectado",
                            style = MaterialTheme.typography.labelMedium,
                            color = OnStrong
                        )
                    }
                }
            }
        }

        if (pendingCommands > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_warning),
                    contentDescription = null, // Decorativo: o texto ao lado informa os comandos pendentes
                    tint = WarnAmber,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Comandos pendentes na fila: $pendingCommands",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = WarnAmber
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Métricas: UM card plano com 4 linhas separadas por HorizontalDivider
        val nfcPendente = posicoes.size > tagsNFC.size
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(dimensions.cardCornerRadius)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MetricRow(
                    label = "Total em estoque",
                    value = "$totalEstoque un (${itens.size} SKUs)"
                )
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                val semMovimentos = entradasHoje == 0 && saidasHoje == 0
                val movimentosText = if (semMovimentos) {
                    "Nenhum"
                } else {
                    val entradasLabel = if (entradasHoje == 1) "entrada" else "entradas"
                    val saidasLabel = if (saidasHoje == 1) "saída" else "saídas"
                    "+$entradasHoje $entradasLabel · \u2212$saidasHoje $saidasLabel"
                }
                MetricRow(
                    label = "Movimentos hoje",
                    value = movimentosText,
                    valueColor = if (semMovimentos) InkMuted else MaterialTheme.colorScheme.onSurface
                )
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                MetricRow(
                    label = "Posições NFC",
                    value = "${tagsNFC.size}/${posicoes.size}",
                    valueColor = if (nfcPendente) WarnAmber else MaterialTheme.colorScheme.onSurface
                )
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                MetricRow(
                    label = "Estoque baixo",
                    value = itensBaixoEstoque.toString(),
                    valueColor = if (itensBaixoEstoque > 0) WarnAmber else MaterialTheme.colorScheme.onSurface,
                    leadingIcon = if (itensBaixoEstoque > 0) Res.drawable.ic_warning else null
                )
            }
        }

        Spacer(modifier = Modifier.height(dimensions.paddingLarge))

        // Navegação: 1 botão primário no topo + 1 card de lista com divisórias
        PrimaryButton(
            text = "Separação",
            onClick = onNavigateSeparacao,
            leadingIcon = {
                Icon(
                    painter = painterResource(Res.drawable.ic_route),
                    contentDescription = null, // Decorativo: o rótulo "Separação" descreve a ação
                    tint = OnStrong,
                    modifier = Modifier.size(24.dp)
                )
            }
        )

        Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(dimensions.cardCornerRadius)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ActionListRow(
                    icon = Res.drawable.ic_move_to_inbox,
                    label = "Recebimento",
                    onClick = onNavigateRecebimento
                )
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                ActionListRow(
                    icon = Res.drawable.ic_shelves,
                    label = "Endereços",
                    onClick = onNavigateEnderecos
                )
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                ActionListRow(
                    icon = Res.drawable.ic_inventory_2,
                    label = "Catálogo",
                    onClick = onNavigateCatalogo
                )
            }
        }

        Spacer(modifier = Modifier.height(dimensions.paddingLarge))

        // Seção Testes de hardware (título 22 sp BrandWine; lista em 1 card plano)
        Text(
            text = "Testes de hardware",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(dimensions.minButtonSpacing))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(dimensions.cardCornerRadius)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ActionListRow(
                    icon = Res.drawable.ic_sensors,
                    label = if (isPinging) "Pingando ESP32…" else "Ping ESP32",
                    enabled = !isPinging,
                    isLoading = isPinging,
                    onClick = {
                        coroutineScope.launch {
                            isPinging = true
                            val result = mqttService.enviarPing()
                            isPinging = false
                            if (result.isSuccess) {
                                val latencia = result.getOrNull() ?: 0L
                                pingFeedbackText = "Ping OK: ${latencia} ms"
                                feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                            } else {
                                pingFeedbackText = "Sem resposta do ping: ${result.exceptionOrNull()?.message ?: "verifique a conexão"}"
                                feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
                            }
                        }
                    }
                )
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                ActionListRow(
                    icon = Res.drawable.ic_lightbulb,
                    label = "Testar LED",
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
                    }
                )
            }
        }

        if (pingFeedbackText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            val isOk = pingFeedbackText?.startsWith("Ping OK") == true
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isOk) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_warning),
                        contentDescription = null, // Decorativo: o texto ao lado informa a falha do ping
                        tint = WarnAmber,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = pingFeedbackText ?: "",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = if (isOk) OkTeal else WarnAmber
                )
            }
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingIcon: DrawableResource? = null
) {
    val dimensions = LocalWmsDimensions.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimensions.paddingMedium, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = InkMuted
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (leadingIcon != null) {
                Icon(
                    painter = painterResource(leadingIcon),
                    contentDescription = null, // Decorativo: o valor numérico ao lado indica o estado
                    tint = valueColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = valueColor
            )
        }
    }
}

@Composable
private fun ActionListRow(
    icon: DrawableResource,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    val dimensions = LocalWmsDimensions.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimensions.primaryButtonHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = dimensions.paddingMedium, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null, // Decorativo: o rótulo ao lado descreve a ação
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Icon(
                painter = painterResource(Res.drawable.ic_chevron_right),
                contentDescription = null, // Decorativo: indica navegação na linha
                tint = InkMuted,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
