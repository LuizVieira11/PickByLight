package com.uniso.wms.pickbylight.domain.model

import com.uniso.wms.pickbylight.util.currentTimeMillis
import com.uniso.wms.pickbylight.util.generateUUID

enum class TipoMovimentacao {
    ENTRADA,
    SAIDA,
    TRANSFERENCIA
}

data class Estante(
    val id: String = generateUUID(),
    val codigo: String,
    val nome: String,
    val ledId: String,
    val setor: String = "Geral"
)

data class Prateleira(
    val id: String = generateUUID(),
    val estanteId: String,
    val codigo: String,
    val ledIdPonta: String
)

data class Posicao(
    val id: String = generateUUID(),
    val prateleiraId: String,
    val codigo: String,
    val limitesLap: String = "30x20x40cm", // Regex: ^\d{1,4}x\d{1,4}x\d{1,4}(cm|mm)$
    val ledId: String
    // tagUid removido: vínculo resolvido exclusivamente via TagNFC (fonte única de verdade)
) {
    companion object {
        val LIMITES_LAP_REGEX = Regex("""^\d{1,4}x\d{1,4}x\d{1,4}(cm|mm)$""")
        fun validarLimitesLap(limites: String): Boolean = LIMITES_LAP_REGEX.matches(limites)
    }
}

data class TagNFC(
    val uid: String, // Formato esperado: "04:A1:2B:3C:4D:5E:01" (7 bytes hex)
    val posicaoId: String,
    val dataVinculo: Long
)

data class Item(
    val id: String = generateUUID(),
    val sku: String,
    val nome: String,
    val categoria: String,
    val unidade: String, // Ex: "UN", "CX", "PAR", "MT"
    val estoqueMinimo: Int = 5,
    val emSeparacao: Boolean = false // Lock otimista para evitar retirada simultânea
)

data class EstoquePosicao(
    val id: String = generateUUID(),
    val itemId: String,
    val posicaoId: String,
    val quantidade: Int
)

data class EstoquePosicaoDetalhe(
    val id: String,
    val itemId: String,
    val posicaoId: String,
    val posicaoCodigo: String,
    val prateleiraCodigo: String,
    val estanteCodigo: String,
    val estanteNome: String,
    val enderecoFormatado: String,
    val quantidade: Int
)

data class ItemComEstoque(
    val item: Item,
    val saldoTotal: Int,
    val estoques: List<EstoquePosicaoDetalhe>
) {
    val isAbaixoMinimo: Boolean get() = saldoTotal < item.estoqueMinimo
}

/**
 * Tarefa de separação resultante do algoritmo de alocação automática de estoque por posição.
 * Contém o item, a posição física de onde deve ser retirado e a quantidade alocada para essa parada.
 */
data class TarefaSeparacao(
    val itemId: String,
    val posicaoId: String,
    val quantidadeAlocada: Int
)

fun formatarDetalheEstoque(
    estoque: EstoquePosicao,
    posicoes: List<Posicao>,
    prateleiras: List<Prateleira>,
    estantes: List<Estante>
): EstoquePosicaoDetalhe {
    val pos = posicoes.find { it.id == estoque.posicaoId }
    val prat = prateleiras.find { it.id == pos?.prateleiraId }
    val est = estantes.find { it.id == prat?.estanteId }
    val posCod = pos?.codigo ?: estoque.posicaoId
    val pratCod = prat?.codigo ?: "?"
    val estCod = est?.codigo ?: "?"
    val estNome = est?.nome ?: ""
    val enderecoFormatado = "$estCod > $pratCod > $posCod"
    return EstoquePosicaoDetalhe(
        id = estoque.id,
        itemId = estoque.itemId,
        posicaoId = estoque.posicaoId,
        posicaoCodigo = posCod,
        prateleiraCodigo = pratCod,
        estanteCodigo = estCod,
        estanteNome = estNome,
        enderecoFormatado = enderecoFormatado,
        quantidade = estoque.quantidade
    )
}

fun criarItemComEstoque(
    item: Item,
    todosEstoques: List<EstoquePosicao>,
    posicoes: List<Posicao>,
    prateleiras: List<Prateleira>,
    estantes: List<Estante>
): ItemComEstoque {
    val estoquesItem = todosEstoques
        .filter { it.itemId == item.id && it.quantidade > 0 }
        .map { formatarDetalheEstoque(it, posicoes, prateleiras, estantes) }
        .sortedWith(compareBy({ it.estanteCodigo }, { it.prateleiraCodigo }, { it.posicaoCodigo }))
    val total = estoquesItem.sumOf { it.quantidade }
    return ItemComEstoque(
        item = item,
        saldoTotal = total,
        estoques = estoquesItem
    )
}

data class Movimentacao(
    val id: String = generateUUID(),
    val tipo: TipoMovimentacao,
    val itemId: String,
    val posicaoId: String,
    val quantidade: Int,
    val observacao: String? = null,
    val timestamp: Long = currentTimeMillis(),
    val usuario: String = "Operador Padrão",
    val modoManual: Boolean = false // true quando operação ocorreu sem sinal luminoso
)
