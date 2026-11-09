package com.uniso.wms.pickbylight.data.repository

import com.uniso.wms.pickbylight.data.seed.WmsSeeder
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.EstoquePosicao
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.domain.model.ItemComEstoque
import com.uniso.wms.pickbylight.domain.model.Movimentacao
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.model.TagNFC
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
import com.uniso.wms.pickbylight.domain.model.criarItemComEstoque
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.util.generateUUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Repositório em memória com StateFlow reativo.
 * Implementa validação estrita de integridade referencial, unicidade composta
 * e regras de negócio para Web e ambiente de testes.
 */
class InMemoryWmsRepository(
    autoSeed: Boolean = true
) : WmsRepository {

    private val estantesState = MutableStateFlow<List<Estante>>(emptyList())
    private val prateleirasState = MutableStateFlow<List<Prateleira>>(emptyList())
    private val posicoesState = MutableStateFlow<List<Posicao>>(emptyList())
    private val tagsNfcState = MutableStateFlow<List<TagNFC>>(emptyList())
    private val itensState = MutableStateFlow<List<Item>>(emptyList())
    private val estoquesPosicaoState = MutableStateFlow<List<EstoquePosicao>>(emptyList())
    private val movimentacoesState = MutableStateFlow<List<Movimentacao>>(emptyList())

    init {
        if (autoSeed) {
            popularSeed()
        }
    }

    private fun popularSeed() {
        // Apaga todos os dados anteriores antes de inserir o novo seed da maquete 3x3
        estantesState.value = emptyList()
        prateleirasState.value = emptyList()
        posicoesState.value = emptyList()
        tagsNfcState.value = emptyList()
        itensState.value = emptyList()
        estoquesPosicaoState.value = emptyList()
        movimentacoesState.value = emptyList()

        estantesState.value = WmsSeeder.seedEstantes()
        prateleirasState.value = WmsSeeder.seedPrateleiras()
        posicoesState.value = WmsSeeder.seedPosicoes()
        tagsNfcState.value = WmsSeeder.seedTagsNFC()
        itensState.value = WmsSeeder.seedItens()
        estoquesPosicaoState.value = WmsSeeder.seedEstoquesPosicao()
    }

    // --- Estantes ---
    override fun getEstantes(): Flow<List<Estante>> = estantesState.asStateFlow()

    override suspend fun getEstanteById(id: String): Estante? =
        estantesState.value.find { it.id == id }

    override suspend fun inserirEstante(estante: Estante): Result<Unit> {
        val current = estantesState.value
        if (current.any { it.codigo.equals(estante.codigo, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("Já existe estante com código '${estante.codigo}'"))
        }
        if (current.any { it.ledId.equals(estante.ledId, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("Já existe estante com LED ID '${estante.ledId}'"))
        }
        estantesState.value = current + estante
        return Result.success(Unit)
    }

    // --- Prateleiras ---
    override fun getPrateleiras(): Flow<List<Prateleira>> = prateleirasState.asStateFlow()

    override fun getPrateleirasByEstante(estanteId: String): Flow<List<Prateleira>> =
        prateleirasState.map { list -> list.filter { it.estanteId == estanteId } }

    override suspend fun getPrateleiraById(id: String): Prateleira? =
        prateleirasState.value.find { it.id == id }

    override suspend fun inserirPrateleira(prateleira: Prateleira): Result<Unit> {
        if (estantesState.value.none { it.id == prateleira.estanteId }) {
            return Result.failure(IllegalArgumentException("Estante '${prateleira.estanteId}' não encontrada"))
        }
        val current = prateleirasState.value
        if (current.any { it.estanteId == prateleira.estanteId && it.codigo.equals(prateleira.codigo, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("Já existe prateleira com código '${prateleira.codigo}' nesta estante"))
        }
        if (current.any { it.ledIdPonta.equals(prateleira.ledIdPonta, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("LED ID de ponta '${prateleira.ledIdPonta}' já está em uso"))
        }
        prateleirasState.value = current + prateleira
        return Result.success(Unit)
    }

    // --- Posições ---
    override fun getPosicoes(): Flow<List<Posicao>> = posicoesState.asStateFlow()

    override fun getPosicoesByPrateleira(prateleiraId: String): Flow<List<Posicao>> =
        posicoesState.map { list -> list.filter { it.prateleiraId == prateleiraId } }

    override suspend fun getPosicaoById(id: String): Posicao? =
        posicoesState.value.find { it.id == id }

    override suspend fun inserirPosicao(posicao: Posicao): Result<Unit> {
        if (prateleirasState.value.none { it.id == posicao.prateleiraId }) {
            return Result.failure(IllegalArgumentException("Prateleira '${posicao.prateleiraId}' não encontrada"))
        }
        if (!Posicao.validarLimitesLap(posicao.limitesLap)) {
            return Result.failure(IllegalArgumentException("Formato de limites inválido: '${posicao.limitesLap}'. Esperado: 30x20x40cm ou 150x80x200mm"))
        }
        val current = posicoesState.value
        if (current.any { it.prateleiraId == posicao.prateleiraId && it.codigo.equals(posicao.codigo, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("Já existe posição com código '${posicao.codigo}' nesta prateleira"))
        }
        if (current.any { it.ledId.equals(posicao.ledId, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("LED ID '${posicao.ledId}' já está em uso"))
        }
        posicoesState.value = current + posicao
        return Result.success(Unit)
    }

    // --- Tags NFC (Fonte Única de Verdade) ---
    override fun getTagsNFC(): Flow<List<TagNFC>> = tagsNfcState.asStateFlow()

    override suspend fun getTagByUid(uid: String): TagNFC? =
        tagsNfcState.value.find { it.uid.equals(uid, ignoreCase = true) }

    override suspend fun getTagByPosicaoId(posicaoId: String): TagNFC? =
        tagsNfcState.value.find { it.posicaoId == posicaoId }

    override suspend fun vincularTag(tag: TagNFC): Result<Unit> {
        if (posicoesState.value.none { it.id == tag.posicaoId }) {
            return Result.failure(IllegalArgumentException("Posição '${tag.posicaoId}' inexistente"))
        }
        val current = tagsNfcState.value
        if (current.any { it.uid.equals(tag.uid, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("Tag '${tag.uid}' já está vinculada"))
        }
        if (current.any { it.posicaoId == tag.posicaoId }) {
            return Result.failure(IllegalArgumentException("Posição '${tag.posicaoId}' já possui tag vinculada"))
        }
        tagsNfcState.value = current + tag
        return Result.success(Unit)
    }

    override suspend fun desvincularTag(uid: String): Result<Unit> {
        val current = tagsNfcState.value
        tagsNfcState.value = current.filterNot { it.uid.equals(uid, ignoreCase = true) }
        return Result.success(Unit)
    }

    // --- Itens ---
    override fun getItens(): Flow<List<Item>> = itensState.asStateFlow()

    override suspend fun getItemById(id: String): Item? =
        itensState.value.find { it.id == id }

    override suspend fun getItemBySku(sku: String): Item? =
        itensState.value.find { it.sku.equals(sku, ignoreCase = true) }

    override suspend fun salvarItem(item: Item): Result<Unit> {
        val current = itensState.value
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            if (current.any { it.id != item.id && it.sku.equals(item.sku, ignoreCase = true) }) {
                return Result.failure(IllegalArgumentException("Outro item já utiliza o SKU '${item.sku}'"))
            }
            itensState.value = current.toMutableList().apply { set(index, item) }
        } else {
            if (current.any { it.sku.equals(item.sku, ignoreCase = true) }) {
                return Result.failure(IllegalArgumentException("Item com SKU '${item.sku}' já cadastrado"))
            }
            itensState.value = current + item
        }
        return Result.success(Unit)
    }

    override suspend fun setEmSeparacao(itemId: String, emSeparacao: Boolean): Result<Unit> {
        val current = itensState.value
        val item = current.find { it.id == itemId }
            ?: return Result.failure(IllegalArgumentException("Item '$itemId' não encontrado"))

        if (emSeparacao && item.emSeparacao) {
            return Result.failure(IllegalStateException("Item em separação por outro operador"))
        }

        val updated = item.copy(emSeparacao = emSeparacao)
        itensState.value = current.map { if (it.id == itemId) updated else it }
        return Result.success(Unit)
    }

    // --- Estoques por Posição & Modelo Agregado ---
    override fun getEstoquesPosicao(): Flow<List<EstoquePosicao>> =
        estoquesPosicaoState.asStateFlow()

    override fun getEstoquesByItem(itemId: String): Flow<List<EstoquePosicao>> =
        estoquesPosicaoState.map { list -> list.filter { it.itemId == itemId } }

    override suspend fun getEstoque(itemId: String, posicaoId: String): EstoquePosicao? =
        estoquesPosicaoState.value.find { it.itemId == itemId && it.posicaoId == posicaoId }

    override suspend fun atualizarEstoquePosicao(
        itemId: String,
        posicaoId: String,
        novaQuantidade: Int
    ): Result<Unit> {
        if (novaQuantidade < 0) {
            return Result.failure(IllegalArgumentException("Estoque não pode ser negativo"))
        }
        val current = estoquesPosicaoState.value
        val existingIndex = current.indexOfFirst { it.itemId == itemId && it.posicaoId == posicaoId }

        if (novaQuantidade == 0) {
            if (existingIndex >= 0) {
                estoquesPosicaoState.value = current.filterNot { it.itemId == itemId && it.posicaoId == posicaoId }
            }
            return Result.success(Unit)
        }

        if (existingIndex >= 0) {
            val updated = current[existingIndex].copy(quantidade = novaQuantidade)
            estoquesPosicaoState.value = current.toMutableList().apply { set(existingIndex, updated) }
        } else {
            val novoEstoque = EstoquePosicao(
                id = generateUUID(),
                itemId = itemId,
                posicaoId = posicaoId,
                quantidade = novaQuantidade
            )
            estoquesPosicaoState.value = current + novoEstoque
        }
        return Result.success(Unit)
    }

    override fun getItensComEstoque(): Flow<List<ItemComEstoque>> =
        combine(
            itensState,
            estoquesPosicaoState,
            posicoesState,
            prateleirasState,
            estantesState
        ) { itens, estoques, posicoes, prateleiras, estantes ->
            itens.map { item ->
                criarItemComEstoque(item, estoques, posicoes, prateleiras, estantes)
            }
        }

    override suspend fun getItemComEstoqueById(itemId: String): ItemComEstoque? {
        val item = itensState.value.find { it.id == itemId } ?: return null
        return criarItemComEstoque(
            item,
            estoquesPosicaoState.value,
            posicoesState.value,
            prateleirasState.value,
            estantesState.value
        )
    }

    override suspend fun moverItem(
        itemId: String,
        posicaoOrigemId: String,
        posicaoDestinoId: String,
        quantidade: Int
    ): Result<Unit> {
        if (quantidade <= 0) {
            return Result.failure(IllegalArgumentException("Quantidade a mover deve ser maior que zero"))
        }
        if (posicaoOrigemId == posicaoDestinoId) {
            return Result.failure(IllegalArgumentException("Posição de origem e destino devem ser diferentes"))
        }
        val item = itensState.value.find { it.id == itemId }
            ?: return Result.failure(IllegalArgumentException("Item '$itemId' não encontrado"))

        if (posicoesState.value.none { it.id == posicaoDestinoId }) {
            return Result.failure(IllegalArgumentException("Posição de destino '$posicaoDestinoId' não encontrada"))
        }

        val estoqueOrigem = estoquesPosicaoState.value.find { it.itemId == itemId && it.posicaoId == posicaoOrigemId }
        val saldoDisponivel = estoqueOrigem?.quantidade ?: 0
        if (saldoDisponivel < quantidade) {
            return Result.failure(IllegalArgumentException("Saldo insuficiente na posição de origem ($saldoDisponivel disponível, solicitado: $quantidade)"))
        }

        // Subtrair da origem
        val novaQtdOrigem = saldoDisponivel - quantidade
        atualizarEstoquePosicao(itemId, posicaoOrigemId, novaQtdOrigem)

        // Somar no destino
        val estoqueDestino = estoquesPosicaoState.value.find { it.itemId == itemId && it.posicaoId == posicaoDestinoId }
        val novaQtdDestino = (estoqueDestino?.quantidade ?: 0) + quantidade
        atualizarEstoquePosicao(itemId, posicaoDestinoId, novaQtdDestino)

        // Registrar movimentação de transferência
        registrarMovimentacao(
            Movimentacao(
                tipo = TipoMovimentacao.TRANSFERENCIA,
                itemId = itemId,
                posicaoId = posicaoDestinoId,
                quantidade = quantidade,
                observacao = "Transferência de $posicaoOrigemId para $posicaoDestinoId ($quantidade ${item.unidade})"
            )
        )
        return Result.success(Unit)
    }

    // --- Movimentações ---
    override fun getMovimentacoes(): Flow<List<Movimentacao>> = movimentacoesState.asStateFlow()

    override suspend fun registrarMovimentacao(movimentacao: Movimentacao): Result<Unit> {
        movimentacoesState.value = listOf(movimentacao) + movimentacoesState.value
        return Result.success(Unit)
    }

    // --- Reset ---
    override suspend fun resetarDadosDemo(): Result<Unit> {
        popularSeed()
        return Result.success(Unit)
    }
}
