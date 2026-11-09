package com.uniso.wms.pickbylight

import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.Movimentacao
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.model.TagNFC
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WmsRepositoryTest {

    private lateinit var repository: InMemoryWmsRepository

    @BeforeTest
    fun setup() {
        repository = InMemoryWmsRepository(autoSeed = true)
    }

    @Test
    fun shouldHaveValidSeedDataOnInitialization() = runTest {
        val estantes = repository.getEstantes().first()
        val prateleiras = repository.getPrateleiras().first()
        val posicoes = repository.getPosicoes().first()
        val tagsNFC = repository.getTagsNFC().first()
        val itens = repository.getItens().first()

        // 1 Estante (EST_A)
        assertEquals(1, estantes.size)
        assertTrue(estantes.any {
            it.id == "EST_A" &&
                it.codigo == "EST-A" &&
                it.nome == "Estante Principal" &&
                it.ledId == "EST_A_LED1" &&
                it.setor == "Geral"
        })

        // 3 Prateleiras (PRAT_A1, PRAT_A2, PRAT_A3)
        assertEquals(3, prateleiras.size)
        assertEquals(3, prateleiras.count { it.estanteId == "EST_A" })
        assertTrue(prateleiras.any { it.id == "PRAT_A1" && it.codigo == "PRAT-A1" && it.ledIdPonta == "PRAT_A1_LED1" })
        assertTrue(prateleiras.any { it.id == "PRAT_A2" && it.codigo == "PRAT-A2" && it.ledIdPonta == "PRAT_A2_LED1" })
        assertTrue(prateleiras.any { it.id == "PRAT_A3" && it.codigo == "PRAT-A3" && it.ledIdPonta == "PRAT_A3_LED1" })

        // 9 Posições (grade 3x3, todas com 30x20x40cm e ledId == id)
        assertEquals(9, posicoes.size)
        assertEquals(3, posicoes.count { it.prateleiraId == "PRAT_A1" })
        assertEquals(3, posicoes.count { it.prateleiraId == "PRAT_A2" })
        assertEquals(3, posicoes.count { it.prateleiraId == "PRAT_A3" })
        assertTrue(posicoes.all { it.limitesLap == "30x20x40cm" && it.ledId == it.id })

        // 9 Tags NFC (1 para cada posição)
        assertEquals(9, tagsNFC.size)
        assertEquals("POS_A1_01", repository.getTagByUid("04:A1:2B:3C:4D:5E:01")?.posicaoId)
        assertEquals("POS_A1_02", repository.getTagByUid("04:A1:2B:3C:4D:5E:02")?.posicaoId)
        assertEquals("POS_A1_03", repository.getTagByUid("04:A1:2B:3C:4D:5E:03")?.posicaoId)
        assertEquals("POS_A2_01", repository.getTagByUid("04:A1:2B:3C:4D:5E:04")?.posicaoId)
        assertEquals("POS_A2_02", repository.getTagByUid("04:A1:2B:3C:4D:5E:05")?.posicaoId)
        assertEquals("POS_A2_03", repository.getTagByUid("04:A1:2B:3C:4D:5E:06")?.posicaoId)
        assertEquals("POS_A3_01", repository.getTagByUid("04:A1:2B:3C:4D:5E:07")?.posicaoId)
        assertEquals("POS_A3_02", repository.getTagByUid("04:A1:2B:3C:4D:5E:08")?.posicaoId)
        assertEquals("POS_A3_03", repository.getTagByUid("04:A1:2B:3C:4D:5E:09")?.posicaoId)

        // 5 Itens
        assertEquals(5, itens.size)
        assertTrue(itens.any { it.sku == "ARD-ESP32" && it.nome == "Microcontrolador ESP32 Wi-Fi" })
        assertTrue(itens.any { it.sku == "MOD-RC522" && it.nome == "Módulo Leitor RFID RC522" })
        assertTrue(itens.any { it.sku == "LED-WS2812" && it.nome == "Fita de LED Endereçável 5m" })
        assertTrue(itens.any { it.sku == "TAG-NTAG213" && it.nome == "Kit Tags NFC NTAG213 Adesivas" })
        assertTrue(itens.any { it.sku == "SRV-MTR01" && it.nome == "Servo Motor Industrial 20kg" })

        // 5 Saldos de Estoque por Posição (EstoquePosicao)
        val itensComEstoque = repository.getItensComEstoque().first()
        assertEquals(5, itensComEstoque.size)
        assertEquals(25, repository.getEstoque("ITEM_1", "POS_A1_01")?.quantidade)
        assertEquals(18, repository.getEstoque("ITEM_2", "POS_A1_02")?.quantidade)
        assertEquals(12, repository.getEstoque("ITEM_3", "POS_A2_01")?.quantidade)
        assertEquals(40, repository.getEstoque("ITEM_4", "POS_A3_01")?.quantidade)
        assertEquals(8, repository.getEstoque("ITEM_5", "POS_A3_02")?.quantidade)
    }

    @Test
    fun shouldEnforceCompositeUniquenessForPrateleira() = runTest {
        // Inserir prateleira com mesmo código PRAT-A1 na EST_A deve falhar
        val duplicateResult = repository.inserirPrateleira(
            Prateleira(
                estanteId = "EST_A",
                codigo = "PRAT-A1",
                ledIdPonta = "PRAT_A1_NOVO_LED"
            )
        )
        assertTrue(duplicateResult.isFailure)

        // Inserir mesmo código PRAT-A1 em OUTRA estante EST_B é permitido (unicidade composta)
        repository.inserirEstante(
            Estante(
                id = "EST_B",
                codigo = "EST-B",
                nome = "Estante Auxiliar",
                ledId = "EST_B_LED1",
                setor = "Geral"
            )
        )
        val validDifferentParentResult = repository.inserirPrateleira(
            Prateleira(
                estanteId = "EST_B",
                codigo = "PRAT-A1",
                ledIdPonta = "PRAT_B_A1_LED"
            )
        )
        assertTrue(validDifferentParentResult.isSuccess)
    }

    @Test
    fun shouldEnforceCompositeUniquenessForPosicao() = runTest {
        // As 3 prateleiras do seed já possuem códigos POS-01, POS-02 e POS-03 cada uma (unicidade composta)
        val posicoes = repository.getPosicoes().first()
        assertEquals(3, posicoes.count { it.codigo == "POS-01" })
        assertEquals(3, posicoes.count { it.codigo == "POS-02" })
        assertEquals(3, posicoes.count { it.codigo == "POS-03" })

        // Inserir código POS-01 na prateleira PRAT_A1 (onde já existe) deve falhar
        val duplicateResult = repository.inserirPosicao(
            Posicao(
                prateleiraId = "PRAT_A1",
                codigo = "POS-01",
                ledId = "POS_NOVO_LED"
            )
        )
        assertTrue(duplicateResult.isFailure)

        // Inserir código POS-04 na prateleira PRAT_A1 (onde ainda não existe) é permitido
        val validResult = repository.inserirPosicao(
            Posicao(
                prateleiraId = "PRAT_A1",
                codigo = "POS-04",
                ledId = "POS_A1_04"
            )
        )
        assertTrue(validResult.isSuccess)
    }

    @Test
    fun shouldEnforceTagNfcSingleSourceOfTruthAndReferentialIntegrity() = runTest {
        // 1. Vincular tag a posição inexistente deve falhar
        val invalidPosResult = repository.vincularTag(
            TagNFC(
                uid = "04:FF:FF:FF:FF:FF:01",
                posicaoId = "POS_INEXISTENTE",
                dataVinculo = 1700000000000L
            )
        )
        assertTrue(invalidPosResult.isFailure)

        // 2. Vincular tag já em uso deve falhar
        val duplicateUidResult = repository.vincularTag(
            TagNFC(
                uid = "04:A1:2B:3C:4D:5E:01", // já pertence a POS_A1_01
                posicaoId = "POS_A3_03",
                dataVinculo = 1700000000000L
            )
        )
        assertTrue(duplicateUidResult.isFailure)

        // 3. Vincular segunda tag a uma posição que já possui tag deve falhar
        val duplicatePosResult = repository.vincularTag(
            TagNFC(
                uid = "04:A1:2B:3C:4D:5E:99",
                posicaoId = "POS_A1_01", // já tem tag 04:A1...01
                dataVinculo = 1700000000000L
            )
        )
        assertTrue(duplicatePosResult.isFailure)

        // 4. Desvincular tag de POS_A3_03 e vincular nova tag válida deve suceder
        repository.desvincularTag("04:A1:2B:3C:4D:5E:09")
        val validResult = repository.vincularTag(
            TagNFC(
                uid = "04:A1:2B:3C:4D:5E:99",
                posicaoId = "POS_A3_03",
                dataVinculo = 1700000000000L
            )
        )
        assertTrue(validResult.isSuccess)
        val tagRecuperada = repository.getTagByUid("04:A1:2B:3C:4D:5E:99")
        assertNotNull(tagRecuperada)
        assertEquals("POS_A3_03", tagRecuperada.posicaoId)
    }

    @Test
    fun shouldValidateLimitesLapRegex() {
        assertTrue(Posicao.validarLimitesLap("30x20x40cm"))
        assertTrue(Posicao.validarLimitesLap("150x80x200mm"))
        assertTrue(Posicao.validarLimitesLap("5x5x5cm"))
        assertFalse(Posicao.validarLimitesLap("30x20x40"))
        assertFalse(Posicao.validarLimitesLap("30x20cm"))
        assertFalse(Posicao.validarLimitesLap("larguraxalturaxprofundidade"))
    }

    @Test
    fun shouldRecordMovimentacaoWithModoManualCorrectly() = runTest {
        val movManual = Movimentacao(
            tipo = TipoMovimentacao.SAIDA,
            itemId = "ITEM_1",
            posicaoId = "POS_A1_01",
            quantidade = 2,
            observacao = "Saída emergencial sem LED",
            modoManual = true
        )

        val result = repository.registrarMovimentacao(movManual)
        assertTrue(result.isSuccess)

        val movimentacoes = repository.getMovimentacoes().first()
        val gravada = movimentacoes.find { it.id == movManual.id }
        assertNotNull(gravada)
        assertTrue(gravada.modoManual)
        assertEquals(TipoMovimentacao.SAIDA, gravada.tipo)
        assertEquals(2, gravada.quantidade)
    }

    @Test
    fun shouldHandleOptimisticLockingForSeparacao() = runTest {
        // Bloqueia item
        val lockResult = repository.setEmSeparacao("ITEM_1", true)
        assertTrue(lockResult.isSuccess)

        val itemBloqueado = repository.getItemById("ITEM_1")
        assertNotNull(itemBloqueado)
        assertTrue(itemBloqueado.emSeparacao)

        // Nova tentativa simultânea de lock deve falhar
        val secondLockResult = repository.setEmSeparacao("ITEM_1", true)
        assertTrue(secondLockResult.isFailure)

        // Libera lock
        val unlockResult = repository.setEmSeparacao("ITEM_1", false)
        assertTrue(unlockResult.isSuccess)
        assertFalse(repository.getItemById("ITEM_1")!!.emSeparacao)
    }

    @Test
    fun shouldMoveItemAndRecordTransferencia() = runTest {
        val result = repository.moverItem("ITEM_1", "POS_A1_01", "POS_A1_03", 10)
        assertTrue(result.isSuccess)

        val estoqueOrigem = repository.getEstoque("ITEM_1", "POS_A1_01")
        assertEquals(15, estoqueOrigem?.quantidade)

        val estoqueDestino = repository.getEstoque("ITEM_1", "POS_A1_03")
        assertEquals(10, estoqueDestino?.quantidade)

        val movimentacoes = repository.getMovimentacoes().first()
        val transferencia = movimentacoes.firstOrNull { it.tipo == TipoMovimentacao.TRANSFERENCIA && it.itemId == "ITEM_1" }
        assertNotNull(transferencia)
        assertEquals("POS_A1_03", transferencia.posicaoId)
        assertEquals(10, transferencia.quantidade)
    }

    @Test
    fun shouldResetDadosDemoWipeAndReinsert3x3Seed() = runTest {
        // Suja o banco inserindo estante extra e alterando estoque
        repository.inserirEstante(Estante(id = "EST_EXTRA", codigo = "EST-X", nome = "Extra", ledId = "LED_X", setor = "Teste"))
        repository.atualizarEstoquePosicao("ITEM_1", "POS_A1_01", 999)
        assertEquals(2, repository.getEstantes().first().size)

        // Executa o reset
        val resetRes = repository.resetarDadosDemo()
        assertTrue(resetRes.isSuccess)

        // Deve ter apagado a estante extra e restaurado exatamente 1 Estante, 3 Prateleiras, 9 Posições, 9 Tags e 5 Itens
        assertEquals(1, repository.getEstantes().first().size)
        assertEquals(3, repository.getPrateleiras().first().size)
        assertEquals(9, repository.getPosicoes().first().size)
        assertEquals(9, repository.getTagsNFC().first().size)
        assertEquals(5, repository.getItens().first().size)
        assertEquals(25, repository.getEstoque("ITEM_1", "POS_A1_01")?.quantidade)
    }
}
