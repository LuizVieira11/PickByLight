package com.uniso.wms.pickbylight.ui.screens.historico

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.domain.model.Movimentacao
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.ui.components.EmptyState
import com.uniso.wms.pickbylight.ui.components.ScreenHeader
import com.uniso.wms.pickbylight.ui.components.formatUnidade
import com.uniso.wms.pickbylight.ui.theme.InkMuted
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.OkTeal
import com.uniso.wms.pickbylight.ui.theme.WarnAmber
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import com.uniso.wms.pickbylight.util.currentTimeMillis
import org.jetbrains.compose.resources.painterResource
import pickbylight.app.shared.generated.resources.Res
import pickbylight.app.shared.generated.resources.ic_calendar_today
import pickbylight.app.shared.generated.resources.ic_check_circle
import pickbylight.app.shared.generated.resources.ic_file_export
import pickbylight.app.shared.generated.resources.ic_location_on
import pickbylight.app.shared.generated.resources.ic_move_to_inbox
import pickbylight.app.shared.generated.resources.ic_outbox
import pickbylight.app.shared.generated.resources.ic_person
import pickbylight.app.shared.generated.resources.ic_schedule
import pickbylight.app.shared.generated.resources.ic_swap_horiz
import pickbylight.app.shared.generated.resources.ic_warning

enum class FiltroHistorico {
    TODAS,
    ENTRADAS,
    SAIDAS,
    TRANSFERENCIAS,
    MODO_MANUAL
}

enum class PeriodoHistorico(val label: String, val dias: Int?) {
    TODOS("Todo período", null),
    ULTIMOS_7_DIAS("7 dias", 7),
    ULTIMOS_30_DIAS("30 dias", 30)
}

/**
 * Formata um epoch timestamp em milissegundos para "dd/MM/yyyy HH:mm:ss" (UTC-3 / multiplataforma).
 */
fun formatarTimestampMovimentacao(epochMillis: Long): String {
    if (epochMillis <= 0L) return "01/01/1970 00:00:00"
    // Ajuste fuso horário Brasília (UTC-3 = -3 * 3600 * 1000 ms)
    val localSeconds = ((epochMillis - 3L * 3600_000L) / 1000L).coerceAtLeast(0L)
    val segDia = (localSeconds % 86400L).toInt()
    val hora = segDia / 3600
    val min = (segDia % 3600) / 60
    val seg = segDia % 60

    var diasDesde1970 = (localSeconds / 86400L).toInt()
    var ano = 1970
    while (true) {
        val bissexto = (ano % 4 == 0 && ano % 100 != 0) || (ano % 400 == 0)
        val diasNoAno = if (bissexto) 366 else 365
        if (diasDesde1970 < diasNoAno) break
        diasDesde1970 -= diasNoAno
        ano++
    }
    val bissexto = (ano % 4 == 0 && ano % 100 != 0) || (ano % 400 == 0)
    val diasPorMes = intArrayOf(31, if (bissexto) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var mes = 1
    for (diasMes in diasPorMes) {
        if (diasDesde1970 < diasMes) break
        diasDesde1970 -= diasMes
        mes++
    }
    val dia = diasDesde1970 + 1

    fun Int.pad2() = toString().padStart(2, '0')
    return "${dia.pad2()}/${mes.pad2()}/$ano ${hora.pad2()}:${min.pad2()}:${seg.pad2()}"
}

/**
 * Gera o conteúdo CSV das movimentações filtradas (Seção 3.8 e Cenário 10).
 */
fun gerarCsvMovimentacoes(
    movimentacoes: List<Movimentacao>,
    itens: List<Item>,
    formatEndereco: (String) -> String
): String {
    val itemMap = itens.associateBy { it.id }
    val sb = StringBuilder()
    sb.appendLine("id,timestamp,data_hora,tipo,sku,item_nome,quantidade,endereco,usuario,modo_manual,observacao")
    for (mov in movimentacoes) {
        val item = itemMap[mov.itemId]
        val sku = item?.sku ?: mov.itemId
        val nome = (item?.nome ?: "").replace(",", " ")
        val endereco = formatEndereco(mov.posicaoId).replace(",", " ")
        val dataHora = formatarTimestampMovimentacao(mov.timestamp)
        val obs = (mov.observacao ?: "").replace(",", " ").replace("\n", " ")
        sb.appendLine(
            "${mov.id},${mov.timestamp},$dataHora,${mov.tipo.name},$sku,$nome,${mov.quantidade},$endereco,${mov.usuario},${mov.modoManual},$obs"
        )
    }
    return sb.toString()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoricoScreen(
    repository: WmsRepository,
    onNavigateBack: () -> Unit,
    onNavigateRecebimento: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalWmsDimensions.current
    val clipboardManager = LocalClipboardManager.current

    val movimentacoes by repository.getMovimentacoes().collectAsState(initial = emptyList())
    val itens by repository.getItens().collectAsState(initial = emptyList())
    val posicoes by repository.getPosicoes().collectAsState(initial = emptyList())
    val prateleiras by repository.getPrateleiras().collectAsState(initial = emptyList())
    val estantes by repository.getEstantes().collectAsState(initial = emptyList())

    var filtroSelecionado by remember { mutableStateOf(FiltroHistorico.TODAS) }
    var periodoSelecionado by remember { mutableStateOf(PeriodoHistorico.TODOS) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedMovimentacaoDetalhe by remember { mutableStateOf<Movimentacao?>(null) }
    var csvExportContent by remember { mutableStateOf<String?>(null) }
    var csvCopiedFeedback by remember { mutableStateOf(false) }

    PlatformBackHandler {
        onNavigateBack()
    }

    fun formatEndereco(posId: String): String {
        val pos = posicoes.find { it.id == posId } ?: return "Endereço: $posId"
        val prat = prateleiras.find { it.id == pos.prateleiraId }
        val est = estantes.find { it.id == prat?.estanteId }
        val estanteStr = est?.codigo?.takeIf { it.isNotBlank() } ?: est?.nome ?: "Est"
        val pratStr = prat?.codigo?.takeIf { it.isNotBlank() } ?: "Prat"
        return "$estanteStr · $pratStr · ${pos.codigo}"
    }

    val agoraMs = currentTimeMillis()
    val listaFiltrada = movimentacoes.filter { mov ->
        val passaTipo = when (filtroSelecionado) {
            FiltroHistorico.TODAS -> true
            FiltroHistorico.ENTRADAS -> mov.tipo == TipoMovimentacao.ENTRADA
            FiltroHistorico.SAIDAS -> mov.tipo == TipoMovimentacao.SAIDA
            FiltroHistorico.TRANSFERENCIAS -> mov.tipo == TipoMovimentacao.TRANSFERENCIA
            FiltroHistorico.MODO_MANUAL -> mov.modoManual
        }
        val passaPeriodo = periodoSelecionado.dias?.let { dias ->
            val limiteMs = agoraMs - dias * 24L * 3600_000L
            mov.timestamp >= limiteMs
        } ?: true

        val item = itens.find { it.id == mov.itemId }
        val enderecoStr = formatEndereco(mov.posicaoId)
        val passaBusca = searchQuery.isBlank() ||
            item?.sku?.contains(searchQuery, ignoreCase = true) == true ||
            item?.nome?.contains(searchQuery, ignoreCase = true) == true ||
            enderecoStr.contains(searchQuery, ignoreCase = true)

        passaTipo && passaPeriodo && passaBusca
    }.sortedByDescending { it.timestamp }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimensions.screenHorizontalPadding, vertical = dimensions.paddingMedium)
    ) {
        ScreenHeader(
            title = "Histórico",
            actionLabel = "Voltar",
            onAction = onNavigateBack,
            trailingContent = {
                TextButton(
                    onClick = {
                        csvCopiedFeedback = false
                        csvExportContent = gerarCsvMovimentacoes(listaFiltrada, itens, ::formatEndereco)
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_file_export),
                            contentDescription = null, // Decorativo: o texto "Exportar CSV" descreve a ação
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Exportar CSV",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Busca por SKU ou código de endereço (Seção 3.8)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Buscar por SKU, item ou endereço...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(dimensions.buttonCornerRadius)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Chips de filtro de tipo sem corte horizontal (FlowRow)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = filtroSelecionado == FiltroHistorico.TODAS,
                onClick = { filtroSelecionado = FiltroHistorico.TODAS },
                label = {
                    Text(
                        text = "Todas (${movimentacoes.size})",
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
                    )
                }
            )
            FilterChip(
                selected = filtroSelecionado == FiltroHistorico.ENTRADAS,
                onClick = { filtroSelecionado = FiltroHistorico.ENTRADAS },
                label = { Text("Entradas") }
            )
            FilterChip(
                selected = filtroSelecionado == FiltroHistorico.SAIDAS,
                onClick = { filtroSelecionado = FiltroHistorico.SAIDAS },
                label = { Text("Saídas") }
            )
            FilterChip(
                selected = filtroSelecionado == FiltroHistorico.TRANSFERENCIAS,
                onClick = { filtroSelecionado = FiltroHistorico.TRANSFERENCIAS },
                label = { Text("Transferências") }
            )
            FilterChip(
                selected = filtroSelecionado == FiltroHistorico.MODO_MANUAL,
                onClick = { filtroSelecionado = FiltroHistorico.MODO_MANUAL },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_warning),
                            contentDescription = null, // Decorativo: o rótulo "Modo manual" descreve o filtro
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("Modo manual")
                    }
                }
            )
        }

        // Seletor de período sem corte horizontal (FlowRow)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PeriodoHistorico.entries.forEach { periodo ->
                FilterChip(
                    selected = periodoSelecionado == periodo,
                    onClick = { periodoSelecionado = periodo },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_calendar_today),
                                contentDescription = null, // Decorativo: o rótulo ao lado descreve o período
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = periodo.label,
                                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
                            )
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (listaFiltrada.isEmpty()) {
            EmptyState(
                title = "Nenhuma movimentação encontrada",
                message = "Registre uma entrada ou separação para visualizar o histórico.",
                actionLabel = "Ir para recebimento",
                onActionClick = onNavigateRecebimento
            )
        } else {
            Card(
                modifier = Modifier.fillMaxSize(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = RoundedCornerShape(dimensions.cardCornerRadius)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(listaFiltrada, key = { _, it -> it.id }) { idx, mov ->
                        val item = itens.find { it.id == mov.itemId }
                        val tipoLabel = when (mov.tipo) {
                            TipoMovimentacao.ENTRADA -> "Entrada"
                            TipoMovimentacao.SAIDA -> "Saída"
                            TipoMovimentacao.TRANSFERENCIA -> "Transferência"
                        }
                        val tipoIcon = when (mov.tipo) {
                            TipoMovimentacao.ENTRADA -> Res.drawable.ic_move_to_inbox
                            TipoMovimentacao.SAIDA -> Res.drawable.ic_outbox
                            TipoMovimentacao.TRANSFERENCIA -> Res.drawable.ic_swap_horiz
                        }
                        val qtdTexto = when (mov.tipo) {
                            TipoMovimentacao.ENTRADA -> "+${mov.quantidade}"
                            TipoMovimentacao.SAIDA -> "-${mov.quantidade}"
                            TipoMovimentacao.TRANSFERENCIA -> "${mov.quantidade}"
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedMovimentacaoDetalhe = mov }
                                .padding(dimensions.paddingMedium)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FlowRow(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    itemVerticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(tipoIcon),
                                            contentDescription = null, // Decorativo: o rótulo ao lado identifica o tipo
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = tipoLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    if (mov.modoManual) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(1.dp, WarnAmber)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(Res.drawable.ic_warning),
                                                    contentDescription = null, // Decorativo: o texto "Modo manual" descreve o estado
                                                    tint = WarnAmber,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = "Modo manual",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = WarnAmber
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = qtdTexto,
                                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "${item?.nome ?: "Item: ${mov.itemId}"} (${item?.sku ?: "N/A"}) × ${mov.quantidade}",
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurface
                            )

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
                                    text = formatEndereco(mov.posicaoId),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                    color = InkMuted
                                )
                            }

                            if (!mov.observacao.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Obs.: ${mov.observacao}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_schedule),
                                        contentDescription = null, // Decorativo: o texto ao lado informa a data e hora
                                        tint = InkMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = formatarTimestampMovimentacao(mov.timestamp),
                                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                        color = InkMuted
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_person),
                                        contentDescription = null, // Decorativo: o texto ao lado informa o operador
                                        tint = InkMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = mov.usuario,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = InkMuted
                                    )
                                }
                            }
                        }

                        if (idx < listaFiltrada.lastIndex) {
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

    // Modal de Detalhes da Movimentação (Seção 3.8)
    val detalhe = selectedMovimentacaoDetalhe
    if (detalhe != null) {
        val item = itens.find { it.id == detalhe.itemId }
        val tipoLabel = when (detalhe.tipo) {
            TipoMovimentacao.ENTRADA -> "Entrada"
            TipoMovimentacao.SAIDA -> "Saída"
            TipoMovimentacao.TRANSFERENCIA -> "Transferência"
        }
        AlertDialog(
            onDismissRequest = { selectedMovimentacaoDetalhe = null },
            title = {
                Text("Detalhes da movimentação", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "ID (UUID): ${detalhe.id}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("Tipo: $tipoLabel", style = MaterialTheme.typography.titleMedium)
                    Text("Item: ${item?.nome ?: detalhe.itemId} (${item?.sku ?: "N/A"})")
                    Text(
                        text = "Quantidade: ${detalhe.quantidade} ${formatUnidade(item?.unidade)}",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
                    )
                    Text(
                        text = "Endereço: ${formatEndereco(detalhe.posicaoId)}",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
                    )
                    Text(
                        text = "Data/hora: ${formatarTimestampMovimentacao(detalhe.timestamp)}",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
                    )
                    Text("Operador: ${detalhe.usuario}")
                    if (detalhe.modoManual) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_warning),
                                contentDescription = null, // Decorativo: o texto ao lado informa que o LED estava indisponível
                                tint = WarnAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Modo manual: sim (LED indisponível)",
                                color = WarnAmber
                            )
                        }
                    } else {
                        Text(
                            text = "Modo manual: não (Pick-by-Light normal)",
                            color = OkTeal
                        )
                    }
                    if (!detalhe.observacao.isNullOrBlank()) {
                        Text("Observação: ${detalhe.observacao}")
                    }
                }
            },
            confirmButton = {
                OutlinedButton(
                    onClick = { selectedMovimentacaoDetalhe = null },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Fechar", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // Modal de Exportação CSV (Seção 3.8 e Cenário 10)
    val csv = csvExportContent
    if (csv != null) {
        AlertDialog(
            onDismissRequest = { csvExportContent = null },
            title = {
                Text(
                    text = "Exportação CSV (${listaFiltrada.size} registros)",
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum")
                )
            },
            text = {
                Column {
                    if (csvCopiedFeedback) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_check_circle),
                                contentDescription = null, // Decorativo: o texto ao lado confirma a cópia do CSV
                                tint = OkTeal,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "CSV copiado para a área de transferência.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = OkTeal
                            )
                        }
                    } else {
                        Text(
                            text = "Conteúdo CSV gerado das movimentações filtradas:",
                            style = MaterialTheme.typography.bodyLarge,
                            color = InkMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = csv,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(csv))
                        csvCopiedFeedback = true
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Copiar CSV")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { csvExportContent = null },
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
