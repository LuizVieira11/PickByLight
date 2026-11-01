package com.uniso.wms.pickbylight.domain.repository

import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.EstoquePosicao
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.domain.model.ItemComEstoque
import com.uniso.wms.pickbylight.domain.model.Movimentacao
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.model.TagNFC
import kotlinx.coroutines.flow.Flow

interface WmsRepository {
    // Estantes
    fun getEstantes(): Flow<List<Estante>>
    suspend fun getEstanteById(id: String): Estante?
    suspend fun inserirEstante(estante: Estante): Result<Unit>

    // Prateleiras
    fun getPrateleiras(): Flow<List<Prateleira>>
    fun getPrateleirasByEstante(estanteId: String): Flow<List<Prateleira>>
    suspend fun getPrateleiraById(id: String): Prateleira?
    suspend fun inserirPrateleira(prateleira: Prateleira): Result<Unit>

    // Posições
    fun getPosicoes(): Flow<List<Posicao>>
    fun getPosicoesByPrateleira(prateleiraId: String): Flow<List<Posicao>>
    suspend fun getPosicaoById(id: String): Posicao?
    suspend fun inserirPosicao(posicao: Posicao): Result<Unit>

    // Tags NFC (Fonte Única de Verdade do vínculo)
    fun getTagsNFC(): Flow<List<TagNFC>>
    suspend fun getTagByUid(uid: String): TagNFC?
    suspend fun getTagByPosicaoId(posicaoId: String): TagNFC?
    suspend fun vincularTag(tag: TagNFC): Result<Unit>
    suspend fun desvincularTag(uid: String): Result<Unit>

    // Itens
    fun getItens(): Flow<List<Item>>
    suspend fun getItemById(id: String): Item?
    suspend fun getItemBySku(sku: String): Item?
    suspend fun salvarItem(item: Item): Result<Unit>
    suspend fun setEmSeparacao(itemId: String, emSeparacao: Boolean): Result<Unit>

    // Estoque por Posição & Modelo Agregado
    fun getEstoquesPosicao(): Flow<List<EstoquePosicao>>
    fun getEstoquesByItem(itemId: String): Flow<List<EstoquePosicao>>
    suspend fun getEstoque(itemId: String, posicaoId: String): EstoquePosicao?
    suspend fun atualizarEstoquePosicao(itemId: String, posicaoId: String, novaQuantidade: Int): Result<Unit>
    fun getItensComEstoque(): Flow<List<ItemComEstoque>>
    suspend fun getItemComEstoqueById(itemId: String): ItemComEstoque?

    // Movimentação TRANSFERENCIA entre posições
    suspend fun moverItem(
        itemId: String,
        posicaoOrigemId: String,
        posicaoDestinoId: String,
        quantidade: Int
    ): Result<Unit>

    // Movimentações (Histórico)
    fun getMovimentacoes(): Flow<List<Movimentacao>>
    suspend fun registrarMovimentacao(movimentacao: Movimentacao): Result<Unit>

    // Seed / Reset
    suspend fun resetarDadosDemo(): Result<Unit>
}
