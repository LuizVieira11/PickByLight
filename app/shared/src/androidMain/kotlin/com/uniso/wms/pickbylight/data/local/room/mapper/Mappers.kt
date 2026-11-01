package com.uniso.wms.pickbylight.data.local.room.mapper

import com.uniso.wms.pickbylight.data.local.room.entity.EstanteEntity
import com.uniso.wms.pickbylight.data.local.room.entity.EstoquePosicaoEntity
import com.uniso.wms.pickbylight.data.local.room.entity.ItemEntity
import com.uniso.wms.pickbylight.data.local.room.entity.MovimentacaoEntity
import com.uniso.wms.pickbylight.data.local.room.entity.PosicaoEntity
import com.uniso.wms.pickbylight.data.local.room.entity.PrateleiraEntity
import com.uniso.wms.pickbylight.data.local.room.entity.TagNfcEntity
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.EstoquePosicao
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.domain.model.Movimentacao
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.model.TagNFC
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao

fun Estante.toEntity(): EstanteEntity = EstanteEntity(
    id = id,
    codigo = codigo,
    nome = nome,
    ledId = ledId,
    setor = setor
)

fun EstanteEntity.toDomain(): Estante = Estante(
    id = id,
    codigo = codigo,
    nome = nome,
    ledId = ledId,
    setor = setor
)

fun Prateleira.toEntity(): PrateleiraEntity = PrateleiraEntity(
    id = id,
    estanteId = estanteId,
    codigo = codigo,
    ledIdPonta = ledIdPonta
)

fun PrateleiraEntity.toDomain(): Prateleira = Prateleira(
    id = id,
    estanteId = estanteId,
    codigo = codigo,
    ledIdPonta = ledIdPonta
)

fun Posicao.toEntity(): PosicaoEntity = PosicaoEntity(
    id = id,
    prateleiraId = prateleiraId,
    codigo = codigo,
    limitesLap = limitesLap,
    ledId = ledId
)

fun PosicaoEntity.toDomain(): Posicao = Posicao(
    id = id,
    prateleiraId = prateleiraId,
    codigo = codigo,
    limitesLap = limitesLap,
    ledId = ledId
)

fun TagNFC.toEntity(): TagNfcEntity = TagNfcEntity(
    uid = uid,
    posicaoId = posicaoId,
    dataVinculo = dataVinculo
)

fun TagNfcEntity.toDomain(): TagNFC = TagNFC(
    uid = uid,
    posicaoId = posicaoId,
    dataVinculo = dataVinculo
)

fun Item.toEntity(): ItemEntity = ItemEntity(
    id = id,
    sku = sku,
    nome = nome,
    categoria = categoria,
    unidade = unidade,
    estoqueMinimo = estoqueMinimo,
    emSeparacao = emSeparacao
)

fun ItemEntity.toDomain(): Item = Item(
    id = id,
    sku = sku,
    nome = nome,
    categoria = categoria,
    unidade = unidade,
    estoqueMinimo = estoqueMinimo,
    emSeparacao = emSeparacao
)

fun EstoquePosicao.toEntity(): EstoquePosicaoEntity = EstoquePosicaoEntity(
    id = id,
    itemId = itemId,
    posicaoId = posicaoId,
    quantidade = quantidade
)

fun EstoquePosicaoEntity.toDomain(): EstoquePosicao = EstoquePosicao(
    id = id,
    itemId = itemId,
    posicaoId = posicaoId,
    quantidade = quantidade
)

fun Movimentacao.toEntity(): MovimentacaoEntity = MovimentacaoEntity(
    id = id,
    tipo = tipo.name,
    itemId = itemId,
    posicaoId = posicaoId,
    quantidade = quantidade,
    observacao = observacao,
    timestamp = timestamp,
    usuario = usuario,
    modoManual = modoManual
)

fun MovimentacaoEntity.toDomain(): Movimentacao = Movimentacao(
    id = id,
    tipo = TipoMovimentacao.valueOf(tipo),
    itemId = itemId,
    posicaoId = posicaoId,
    quantidade = quantidade,
    observacao = observacao,
    timestamp = timestamp,
    usuario = usuario,
    modoManual = modoManual
)
