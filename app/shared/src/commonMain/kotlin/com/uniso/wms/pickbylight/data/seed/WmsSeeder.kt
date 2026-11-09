package com.uniso.wms.pickbylight.data.seed

import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.EstoquePosicao
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.model.TagNFC

/**
 * Seeder oficial da maquete física 3x3 integrada com o ESP32:
 * - 1 estante (EST_A)
 * - 3 prateleiras (PRAT_A1, PRAT_A2, PRAT_A3)
 * - 9 posições (grade 3x3: POS_A1_01..03, POS_A2_01..03, POS_A3_01..03)
 * - 9 vínculos NFC na tabela TagNFC (04:A1:2B:3C:4D:5E:01 a 09)
 * - 5 itens no catálogo
 * - 5 saldos iniciais na tabela EstoquePosicao
 */
object WmsSeeder {

    fun seedEstantes(): List<Estante> = listOf(
        Estante(
            id = "EST_A",
            codigo = "EST-A",
            nome = "Estante Principal",
            ledId = "EST_A_LED1",
            setor = "Geral"
        )
    )

    fun seedPrateleiras(): List<Prateleira> = listOf(
        Prateleira(
            id = "PRAT_A1",
            estanteId = "EST_A",
            codigo = "PRAT-A1",
            ledIdPonta = "PRAT_A1_LED1"
        ),
        Prateleira(
            id = "PRAT_A2",
            estanteId = "EST_A",
            codigo = "PRAT-A2",
            ledIdPonta = "PRAT_A2_LED1"
        ),
        Prateleira(
            id = "PRAT_A3",
            estanteId = "EST_A",
            codigo = "PRAT-A3",
            ledIdPonta = "PRAT_A3_LED1"
        )
    )

    fun seedPosicoes(): List<Posicao> = listOf(
        Posicao(
            id = "POS_A1_01",
            prateleiraId = "PRAT_A1",
            codigo = "POS-01",
            limitesLap = "30x20x40cm",
            ledId = "POS_A1_01"
        ),
        Posicao(
            id = "POS_A1_02",
            prateleiraId = "PRAT_A1",
            codigo = "POS-02",
            limitesLap = "30x20x40cm",
            ledId = "POS_A1_02"
        ),
        Posicao(
            id = "POS_A1_03",
            prateleiraId = "PRAT_A1",
            codigo = "POS-03",
            limitesLap = "30x20x40cm",
            ledId = "POS_A1_03"
        ),
        Posicao(
            id = "POS_A2_01",
            prateleiraId = "PRAT_A2",
            codigo = "POS-01",
            limitesLap = "30x20x40cm",
            ledId = "POS_A2_01"
        ),
        Posicao(
            id = "POS_A2_02",
            prateleiraId = "PRAT_A2",
            codigo = "POS-02",
            limitesLap = "30x20x40cm",
            ledId = "POS_A2_02"
        ),
        Posicao(
            id = "POS_A2_03",
            prateleiraId = "PRAT_A2",
            codigo = "POS-03",
            limitesLap = "30x20x40cm",
            ledId = "POS_A2_03"
        ),
        Posicao(
            id = "POS_A3_01",
            prateleiraId = "PRAT_A3",
            codigo = "POS-01",
            limitesLap = "30x20x40cm",
            ledId = "POS_A3_01"
        ),
        Posicao(
            id = "POS_A3_02",
            prateleiraId = "PRAT_A3",
            codigo = "POS-02",
            limitesLap = "30x20x40cm",
            ledId = "POS_A3_02"
        ),
        Posicao(
            id = "POS_A3_03",
            prateleiraId = "PRAT_A3",
            codigo = "POS-03",
            limitesLap = "30x20x40cm",
            ledId = "POS_A3_03"
        )
    )

    fun seedTagsNFC(): List<TagNFC> = listOf(
        TagNFC(
            uid = "04:A1:2B:3C:4D:5E:01",
            posicaoId = "POS_A1_01",
            dataVinculo = 1700000000000L
        ),
        TagNFC(
            uid = "04:A1:2B:3C:4D:5E:02",
            posicaoId = "POS_A1_02",
            dataVinculo = 1700000000000L
        ),
        TagNFC(
            uid = "04:A1:2B:3C:4D:5E:03",
            posicaoId = "POS_A1_03",
            dataVinculo = 1700000000000L
        ),
        TagNFC(
            uid = "04:A1:2B:3C:4D:5E:04",
            posicaoId = "POS_A2_01",
            dataVinculo = 1700000000000L
        ),
        TagNFC(
            uid = "04:A1:2B:3C:4D:5E:05",
            posicaoId = "POS_A2_02",
            dataVinculo = 1700000000000L
        ),
        TagNFC(
            uid = "04:A1:2B:3C:4D:5E:06",
            posicaoId = "POS_A2_03",
            dataVinculo = 1700000000000L
        ),
        TagNFC(
            uid = "04:A1:2B:3C:4D:5E:07",
            posicaoId = "POS_A3_01",
            dataVinculo = 1700000000000L
        ),
        TagNFC(
            uid = "04:A1:2B:3C:4D:5E:08",
            posicaoId = "POS_A3_02",
            dataVinculo = 1700000000000L
        ),
        TagNFC(
            uid = "04:A1:2B:3C:4D:5E:09",
            posicaoId = "POS_A3_03",
            dataVinculo = 1700000000000L
        )
    )

    fun seedItens(): List<Item> = listOf(
        Item(
            id = "ITEM_1",
            sku = "ARD-ESP32",
            nome = "Microcontrolador ESP32 Wi-Fi",
            categoria = "Microcontroladores",
            unidade = "UN",
            estoqueMinimo = 5,
            emSeparacao = false
        ),
        Item(
            id = "ITEM_2",
            sku = "MOD-RC522",
            nome = "Módulo Leitor RFID RC522",
            categoria = "Sensores",
            unidade = "UN",
            estoqueMinimo = 5,
            emSeparacao = false
        ),
        Item(
            id = "ITEM_3",
            sku = "LED-WS2812",
            nome = "Fita de LED Endereçável 5m",
            categoria = "Iluminação",
            unidade = "UN",
            estoqueMinimo = 5,
            emSeparacao = false
        ),
        Item(
            id = "ITEM_4",
            sku = "TAG-NTAG213",
            nome = "Kit Tags NFC NTAG213 Adesivas",
            categoria = "Identificação",
            unidade = "CX",
            estoqueMinimo = 5,
            emSeparacao = false
        ),
        Item(
            id = "ITEM_5",
            sku = "SRV-MTR01",
            nome = "Servo Motor Industrial 20kg",
            categoria = "Atuadores",
            unidade = "UN",
            estoqueMinimo = 5,
            emSeparacao = false
        )
    )

    fun seedEstoquesPosicao(): List<EstoquePosicao> = listOf(
        EstoquePosicao(
            id = "ESTOQUE_1",
            itemId = "ITEM_1",
            posicaoId = "POS_A1_01",
            quantidade = 25
        ),
        EstoquePosicao(
            id = "ESTOQUE_2",
            itemId = "ITEM_2",
            posicaoId = "POS_A1_02",
            quantidade = 18
        ),
        EstoquePosicao(
            id = "ESTOQUE_3",
            itemId = "ITEM_3",
            posicaoId = "POS_A2_01",
            quantidade = 12
        ),
        EstoquePosicao(
            id = "ESTOQUE_4",
            itemId = "ITEM_4",
            posicaoId = "POS_A3_01",
            quantidade = 40
        ),
        EstoquePosicao(
            id = "ESTOQUE_5",
            itemId = "ITEM_5",
            posicaoId = "POS_A3_02",
            quantidade = 8
        )
    )
}
