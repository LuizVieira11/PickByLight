package com.uniso.wms.pickbylight.ui.screens.historico

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.domain.model.Estante
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

enum class FiltroHistorico {
    TODAS,
    ENTRADAS,
    SAIDAS,
    MODO_MANUAL
}

@Composable
fun HistoricoScreen(
    repository: WmsRepository,
    onNavigateBack: () -> Unit,
    onNavigateRecebimento: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalWmsDimensions.current

    val movimentacoes by repository.getMovimentacoes().collectAsState(initial = emptyList())
    val itens by repository.getItens().collectAsState(initial = emptyList())
    val posicoes by repository.getPosicoes().collectAsState(initial = emptyList())
    val prateleiras by repository.getPrateleiras().collectAsState(initial = emptyList())
    val estantes by repository.getEstantes().collectAsState(initial = emptyList())

    var filtroSelecionado by remember { mutableStateOf(FiltroHistorico.TODAS) }

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

    val listaFiltrada = movimentacoes.filter { mov ->
        when (filtroSelecionado) {
            FiltroHistorico.TODAS -> true
            FiltroHistorico.ENTRADAS -> mov.tipo == TipoMovimentacao.ENTRADA
            FiltroHistorico.SAIDAS -> mov.tipo == TipoMovimentacao.SAIDA
            FiltroHistorico.MODO_MANUAL -> mov.modoManual
        }
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
            TextButton(onClick = onNavigateBack) {
                Text("Voltar", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Chips de Filtro com rolagem horizontal (Seção 3.8)
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
                selected = filtroSelecionado == FiltroHistorico.MODO_MANUAL,
                onClick = { filtroSelecionado = FiltroHistorico.MODO_MANUAL },
                label = { Text("⚠️ Modo Manual") }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (listaFiltrada.isEmpty()) {
            EmptyState(
                title = "Nenhuma movimentação encontrada",
                message = "Nenhuma movimentação registrada no filtro atual.",
                actionLabel = "Ir para Recebimento",
                onActionClick = onNavigateRecebimento
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(listaFiltrada) { mov ->
                    val item = itens.find { it.id == mov.itemId }
                    val isEntrada = mov.tipo == TipoMovimentacao.ENTRADA
                    val badgeColor = when (mov.tipo) {
                        TipoMovimentacao.ENTRADA -> EmeraldSuccess
                        TipoMovimentacao.SAIDA -> CoralError
                        TipoMovimentacao.TRANSFERENCIA -> ShelfRackBlue
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
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
                                    text = if (isEntrada) "+${mov.quantidade}" else "-${mov.quantidade}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor,
                                    maxLines = 1
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = item?.nome ?: "Item: ${mov.itemId}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                text = "SKU: ${item?.sku ?: "N/A"} | ${formatEndereco(mov.posicaoId)}",
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

                            Text(
                                text = "Operador: ${mov.usuario}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
