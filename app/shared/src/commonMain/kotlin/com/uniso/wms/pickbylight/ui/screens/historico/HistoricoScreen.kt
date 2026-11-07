package com.uniso.wms.pickbylight.ui.screens.historico

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.domain.model.Movimentacao
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.ui.components.EmptyState
import com.uniso.wms.pickbylight.ui.theme.AmberWarning
import com.uniso.wms.pickbylight.ui.theme.CoralError
import com.uniso.wms.pickbylight.ui.theme.EmeraldSuccess
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.ShelfRackBlue
import com.uniso.wms.pickbylight.ui.theme.ShelfRowOrange
import com.uniso.wms.pickbylight.util.PlatformBackHandler
import com.uniso.wms.pickbylight.util.currentTimeMillis

enum class FiltroHistorico {
    TODAS,
    ENTRADAS,
    SAIDAS,
    TRANSFERENCIAS,
    MODO_MANUAL
}

enum class PeriodoHistorico(val label: String, val dias: Int?) {
    TODOS("Todo período", null),
    ULTIMOS_7_DIAS("Últimos 7 dias", 7),
    ULTIMOS_30_DIAS("Últimos 30 dias", 30)
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
        val estanteStr = est?.nome?.takeIf { it.isNotBlank() } ?: est?.codigo ?: "Est"
        val pratStr = prat?.codigo?.takeIf { it.isNotBlank() } ?: "Prat"
        return "$estanteStr > $pratStr > ${pos.codigo}"
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
            .padding(dimensions.paddingMedium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Histórico de Movimentações",
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
                    csvCopiedFeedback = false
                    csvExportContent = gerarCsvMovimentacoes(listaFiltrada, itens, ::formatEndereco)
                }
            ) {
                Text("📤 Exportar CSV", style = MaterialTheme.typography.labelLarge, color = ShelfRackBlue, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onNavigateBack) {
                Text("Voltar", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Busca por SKU ou Código de Endereço (Seção 3.8)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Buscar por SKU, item ou endereço...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Chips de Filtro de Tipo com rolagem horizontal (Seção 3.8)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filtroSelecionado == FiltroHistorico.TODAS,
                onClick = { filtroSelecionado = FiltroHistorico.TODAS },
                label = { Text("Todas (${movimentacoes.size})") }
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
                label = { Text("⚠️ Modo Manual") }
            )
        }

        // Seletor de período (Seção 3.8)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PeriodoHistorico.entries.forEach { periodo ->
                FilterChip(
                    selected = periodoSelecionado == periodo,
                    onClick = { periodoSelecionado = periodo },
                    label = { Text("📅 ${periodo.label}") }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (listaFiltrada.isEmpty()) {
            EmptyState(
                title = "Nenhuma movimentação registrada ainda",
                message = "Nenhuma movimentação registrada ainda. Inicie uma entrada ou separação.",
                actionLabel = "Ir para Recebimento",
                onActionClick = onNavigateRecebimento
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(listaFiltrada, key = { it.id }) { mov ->
                    val item = itens.find { it.id == mov.itemId }
                    val isEntrada = mov.tipo == TipoMovimentacao.ENTRADA
                    val badgeColor = when (mov.tipo) {
                        TipoMovimentacao.ENTRADA -> EmeraldSuccess
                        TipoMovimentacao.SAIDA -> CoralError
                        TipoMovimentacao.TRANSFERENCIA -> ShelfRackBlue
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedMovimentacaoDetalhe = mov },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f, fill = false),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(badgeColor, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = mov.tipo.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                    }

                                    // Badge de Modo Manual (Cenário 8 e Seção 3.8)
                                    if (mov.modoManual) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .background(AmberWarning, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "⚠️ Modo Manual",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = when (mov.tipo) {
                                        TipoMovimentacao.ENTRADA -> "+${mov.quantidade}"
                                        TipoMovimentacao.SAIDA -> "-${mov.quantidade}"
                                        TipoMovimentacao.TRANSFERENCIA -> "⇄ ${mov.quantidade}"
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor,
                                    maxLines = 1
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "${item?.nome ?: "Item: ${mov.itemId}"} [${item?.sku ?: "N/A"}] × ${mov.quantidade}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                text = "📍 ${formatEndereco(mov.posicaoId)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (!mov.observacao.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Obs: ${mov.observacao}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ShelfRowOrange,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🕒 ${formatarTimestampMovimentacao(mov.timestamp)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1
                                )
                                Text(
                                    text = "👤 ${mov.usuario}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1
                                )
                            }
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
        AlertDialog(
            onDismissRequest = { selectedMovimentacaoDetalhe = null },
            title = {
                Text("Detalhes da Movimentação", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ID (UUID): ${detalhe.id}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("Tipo: ${detalhe.tipo.name}", fontWeight = FontWeight.Bold)
                    Text("Item: ${item?.nome ?: detalhe.itemId} (${item?.sku ?: "N/A"})")
                    Text("Quantidade: ${detalhe.quantidade} ${item?.unidade ?: "UN"}")
                    Text("Endereço: ${formatEndereco(detalhe.posicaoId)}")
                    Text("Data/Hora: ${formatarTimestampMovimentacao(detalhe.timestamp)}")
                    Text("Operador: ${detalhe.usuario}")
                    Text(
                        text = "Modo Manual: ${if (detalhe.modoManual) "SIM (⚠️ LED indisponível)" else "NÃO (Pick-by-Light normal)"}",
                        color = if (detalhe.modoManual) AmberWarning else EmeraldSuccess,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!detalhe.observacao.isNullOrBlank()) {
                        Text("Observação: ${detalhe.observacao}")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMovimentacaoDetalhe = null }) {
                    Text("Fechar", fontWeight = FontWeight.Bold)
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
                Text("Exportação CSV (${listaFiltrada.size} registros)", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        text = if (csvCopiedFeedback) "✅ CSV copiado para a área de transferência!" else "Conteúdo CSV gerado das movimentações filtradas:",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (csvCopiedFeedback) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = csv,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp),
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(csv))
                        csvCopiedFeedback = true
                    }
                ) {
                    Text("Copiar CSV", color = ShelfRackBlue, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { csvExportContent = null }) {
                    Text("Fechar")
                }
            }
        )
    }
}
