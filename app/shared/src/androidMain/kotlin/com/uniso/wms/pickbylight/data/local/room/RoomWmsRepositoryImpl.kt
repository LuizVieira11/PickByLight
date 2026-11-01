package com.uniso.wms.pickbylight.data.local.room

import com.uniso.wms.pickbylight.data.local.room.dao.WmsDatabase
import com.uniso.wms.pickbylight.data.local.room.entity.EstoquePosicaoEntity
import com.uniso.wms.pickbylight.data.local.room.mapper.toDomain
import com.uniso.wms.pickbylight.data.local.room.mapper.toEntity
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class RoomWmsRepositoryImpl(
    private val database: WmsDatabase
) : WmsRepository {

    private val estanteDao = database.estanteDao()
    private val prateleiraDao = database.prateleiraDao()
    private val posicaoDao = database.posicaoDao()
    private val tagNfcDao = database.tagNfcDao()
    private val itemDao = database.itemDao()
    private val estoquePosicaoDao = database.estoquePosicaoDao()
    private val movimentacaoDao = database.movimentacaoDao()

    // --- Estantes ---
    override fun getEstantes(): Flow<List<Estante>> =
        estanteDao.getAllEstantes().map { list -> list.map { it.toDomain() } }

    override suspend fun getEstanteById(id: String): Estante? =
        estanteDao.getEstanteById(id)?.toDomain()

    override suspend fun inserirEstante(estante: Estante): Result<Unit> = runCatching {
        estanteDao.insertEstante(estante.toEntity())
    }

    // --- Prateleiras ---
    override fun getPrateleiras(): Flow<List<Prateleira>> =
        prateleiraDao.getAllPrateleiras().map { list -> list.map { it.toDomain() } }

    override fun getPrateleirasByEstante(estanteId: String): Flow<List<Prateleira>> =
        prateleiraDao.getPrateleirasByEstante(estanteId).map { list -> list.map { it.toDomain() } }

    override suspend fun getPrateleiraById(id: String): Prateleira? =
        prateleiraDao.getPrateleiraById(id)?.toDomain()

    override suspend fun inserirPrateleira(prateleira: Prateleira): Result<Unit> = runCatching {
        prateleiraDao.insertPrateleira(prateleira.toEntity())
    }

    // --- Posições ---
    override fun getPosicoes(): Flow<List<Posicao>> =
        posicaoDao.getAllPosicoes().map { list -> list.map { it.toDomain() } }

    override fun getPosicoesByPrateleira(prateleiraId: String): Flow<List<Posicao>> =
        posicaoDao.getPosicoesByPrateleira(prateleiraId).map { list -> list.map { it.toDomain() } }

    override suspend fun getPosicaoById(id: String): Posicao? =
        posicaoDao.getPosicaoById(id)?.toDomain()

    override suspend fun inserirPosicao(posicao: Posicao): Result<Unit> = runCatching {
        if (!Posicao.validarLimitesLap(posicao.limitesLap)) {
            throw IllegalArgumentException("Formato de limites inválido: '${posicao.limitesLap}'")
        }
        posicaoDao.insertPosicao(posicao.toEntity())
    }

    // --- Tags NFC ---
    override fun getTagsNFC(): Flow<List<TagNFC>> =
        tagNfcDao.getAllTags().map { list -> list.map { it.toDomain() } }

    override suspend fun getTagByUid(uid: String): TagNFC? =
        tagNfcDao.getTagByUid(uid)?.toDomain()

    override suspend fun getTagByPosicaoId(posicaoId: String): TagNFC? =
        tagNfcDao.getTagByPosicaoId(posicaoId)?.toDomain()

    override suspend fun vincularTag(tag: TagNFC): Result<Unit> = runCatching {
        tagNfcDao.insertTag(tag.toEntity())
    }

    override suspend fun desvincularTag(uid: String): Result<Unit> = runCatching {
        tagNfcDao.deleteTagByUid(uid)
    }

    // --- Itens ---
    override fun getItens(): Flow<List<Item>> =
        itemDao.getAllItens().map { list -> list.map { it.toDomain() } }

    override suspend fun getItemById(id: String): Item? =
        itemDao.getItemById(id)?.toDomain()

    override suspend fun getItemBySku(sku: String): Item? =
        itemDao.getItemBySku(sku)?.toDomain()

    override suspend fun salvarItem(item: Item): Result<Unit> = runCatching {
        itemDao.insertOrUpdate(item.toEntity())
    }

    override suspend fun setEmSeparacao(itemId: String, emSeparacao: Boolean): Result<Unit> = runCatching {
        val current = itemDao.getItemById(itemId) ?: throw IllegalArgumentException("Item '$itemId' não encontrado")
        if (emSeparacao && current.emSeparacao) {
            throw IllegalStateException("Item em separação por outro operador")
        }
        itemDao.setEmSeparacao(itemId, emSeparacao)
    }

    // --- Estoques por Posição & Modelo Agregado ---
    override fun getEstoquesPosicao(): Flow<List<EstoquePosicao>> =
        estoquePosicaoDao.getAllEstoques().map { list -> list.map { it.toDomain() } }

    override fun getEstoquesByItem(itemId: String): Flow<List<EstoquePosicao>> =
        estoquePosicaoDao.getEstoquesByItem(itemId).map { list -> list.map { it.toDomain() } }

    override suspend fun getEstoque(itemId: String, posicaoId: String): EstoquePosicao? =
        estoquePosicaoDao.getEstoque(itemId, posicaoId)?.toDomain()

    override suspend fun atualizarEstoquePosicao(
        itemId: String,
        posicaoId: String,
        novaQuantidade: Int
    ): Result<Unit> = runCatching {
        if (novaQuantidade < 0) throw IllegalArgumentException("Estoque não pode ser negativo")
        val existing = estoquePosicaoDao.getEstoque(itemId, posicaoId)
        if (novaQuantidade == 0) {
            if (existing != null) {
                estoquePosicaoDao.delete(itemId, posicaoId)
            }
        } else {
            if (existing != null) {
                estoquePosicaoDao.updateQuantidade(itemId, posicaoId, novaQuantidade)
            } else {
                estoquePosicaoDao.insertOrUpdate(
                    EstoquePosicaoEntity(
                        id = generateUUID(),
                        itemId = itemId,
                        posicaoId = posicaoId,
                        quantidade = novaQuantidade
                    )
                )
            }
        }
    }

    override fun getItensComEstoque(): Flow<List<ItemComEstoque>> =
        combine(
            itemDao.getAllItens(),
            estoquePosicaoDao.getAllEstoques(),
            posicaoDao.getAllPosicoes(),
            prateleiraDao.getAllPrateleiras(),
            estanteDao.getAllEstantes()
        ) { itens, estoques, posicoes, prateleiras, estantes ->
            val domainEstoques = estoques.map { it.toDomain() }
            val domainPosicoes = posicoes.map { it.toDomain() }
            val domainPrats = prateleiras.map { it.toDomain() }
            val domainEstantes = estantes.map { it.toDomain() }
            itens.map { itemEntity ->
                criarItemComEstoque(
                    itemEntity.toDomain(),
                    domainEstoques,
                    domainPosicoes,
                    domainPrats,
                    domainEstantes
                )
            }
        }

    override suspend fun getItemComEstoqueById(itemId: String): ItemComEstoque? {
        val item = itemDao.getItemById(itemId)?.toDomain() ?: return null
        val estoques = estoquePosicaoDao.getAllEstoques().first().map { it.toDomain() }
        val posicoes = posicaoDao.getAllPosicoes().first().map { it.toDomain() }
        val prats = prateleiraDao.getAllPrateleiras().first().map { it.toDomain() }
        val estantes = estanteDao.getAllEstantes().first().map { it.toDomain() }
        return criarItemComEstoque(item, estoques, posicoes, prats, estantes)
    }

    override suspend fun moverItem(
        itemId: String,
        posicaoOrigemId: String,
        posicaoDestinoId: String,
        quantidade: Int
    ): Result<Unit> = runCatching {
        if (quantidade <= 0) {
            throw IllegalArgumentException("Quantidade a mover deve ser maior que zero")
        }
        if (posicaoOrigemId == posicaoDestinoId) {
            throw IllegalArgumentException("Posição de origem e destino devem ser diferentes")
        }
        val item = itemDao.getItemById(itemId) ?: throw IllegalArgumentException("Item '$itemId' não encontrado")
        if (posicaoDao.getPosicaoById(posicaoDestinoId) == null) {
            throw IllegalArgumentException("Posição de destino '$posicaoDestinoId' não encontrada")
        }

        val estoqueOrigem = estoquePosicaoDao.getEstoque(itemId, posicaoOrigemId)
        val saldoDisponivel = estoqueOrigem?.quantidade ?: 0
        if (saldoDisponivel < quantidade) {
            throw IllegalArgumentException("Saldo insuficiente na posição de origem ($saldoDisponivel disponível, solicitado: $quantidade)")
        }

        // Subtrai da origem
        val novaQtdOrigem = saldoDisponivel - quantidade
        if (novaQtdOrigem == 0) {
            estoquePosicaoDao.delete(itemId, posicaoOrigemId)
        } else {
            estoquePosicaoDao.updateQuantidade(itemId, posicaoOrigemId, novaQtdOrigem)
        }

        // Soma no destino
        val estoqueDestino = estoquePosicaoDao.getEstoque(itemId, posicaoDestinoId)
        if (estoqueDestino != null) {
            estoquePosicaoDao.updateQuantidade(itemId, posicaoDestinoId, estoqueDestino.quantidade + quantidade)
        } else {
            estoquePosicaoDao.insertOrUpdate(
                EstoquePosicaoEntity(
                    id = generateUUID(),
                    itemId = itemId,
                    posicaoId = posicaoDestinoId,
                    quantidade = quantidade
                )
            )
        }

        movimentacaoDao.insertMovimentacao(
            Movimentacao(
                tipo = TipoMovimentacao.TRANSFERENCIA,
                itemId = itemId,
                posicaoId = posicaoDestinoId,
                quantidade = quantidade,
                observacao = "Transferência de $posicaoOrigemId para $posicaoDestinoId ($quantidade ${item.unidade})"
            ).toEntity()
        )
    }

    // --- Movimentações ---
    override fun getMovimentacoes(): Flow<List<Movimentacao>> =
        movimentacaoDao.getAllMovimentacoes().map { list -> list.map { it.toDomain() } }

    override suspend fun registrarMovimentacao(movimentacao: Movimentacao): Result<Unit> = runCatching {
        movimentacaoDao.insertMovimentacao(movimentacao.toEntity())
    }

    // --- Reset ---
    override suspend fun resetarDadosDemo(): Result<Unit> = runCatching {
        database.popularSeed(
            estantes = WmsSeeder.seedEstantes().map { it.toEntity() },
            prateleiras = WmsSeeder.seedPrateleiras().map { it.toEntity() },
            posicoes = WmsSeeder.seedPosicoes().map { it.toEntity() },
            tags = WmsSeeder.seedTagsNFC().map { it.toEntity() },
            itens = WmsSeeder.seedItens().map { it.toEntity() },
            estoques = WmsSeeder.seedEstoquesPosicao().map { it.toEntity() }
        )
    }
}
