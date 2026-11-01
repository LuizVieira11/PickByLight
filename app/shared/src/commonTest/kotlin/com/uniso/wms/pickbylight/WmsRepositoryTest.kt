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

        // 2 Estantes
        assertEquals(2, estantes.size)
        assertTrue(estantes.any { it.id == "EST_A" && it.codigo == "EST-A" })
        assertTrue(estantes.any { it.id == "EST_B" && it.codigo == "EST-B" })

        // 4 Prateleiras
        assertEquals(4, prateleiras.size)
        assertEquals(2, prateleiras.count { it.estanteId == "EST_A" })
        assertEquals(2, prateleiras.count { it.estanteId == "EST_B" })

        // 8 Posições (6 com tag, 2 pendentes)
        assertEquals(8, posicoes.size)
        assertEquals(6, tagsNFC.size)

        // Posições B2_01 e B2_02 devem estar sem tag para teste ao vivo
        assertNotNull(repository.getTagByPosicaoId("POS_A1_01"))
        assertNotNull(repository.getTagByPosicaoId("POS_A2_05"))
        assertNull(repository.getTagByPosicaoId("POS_B2_01"))
        assertNull(repository.getTagByPosicaoId("POS_B2_02"))

        // 5 Itens
        assertEquals(5, itens.size)
        assertTrue(itens.any { it.sku == "ARD-ESP32" })
        assertTrue(itens.any { it.sku == "MOD-RC522" })
        assertTrue(itens.any { it.sku == "LED-WS2812" })
        assertTrue(itens.any { it.sku == "TAG-NTAG213" })
        assertTrue(itens.any { it.sku == "SRV-MTR01" })

        // 5 Saldos de Estoque por Posição (EstoquePosicao)
        val itensComEstoque = repository.getItensComEstoque().first()
        assertEquals(5, itensComEstoque.size)
        assertTrue(itensComEstoque.any { it.item.sku == "ARD-ESP32" && it.saldoTotal == 25 })
        assertTrue(itensComEstoque.any { it.item.sku == "MOD-RC522" && it.saldoTotal == 18 })
        assertTrue(itensComEstoque.any { it.item.sku == "LED-WS2812" && it.saldoTotal == 12 })
        assertTrue(itensComEstoque.any { it.item.sku == "TAG-NTAG213" && it.saldoTotal == 40 })
        assertTrue(itensComEstoque.any { it.item.sku == "SRV-MTR01" && it.saldoTotal == 8 })
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
        // Inserir código POS-01 na prateleira PRAT_A1 (onde já existe) deve falhar
        val duplicateResult = repository.inserirPosicao(
            Posicao(
                prateleiraId = "PRAT_A1",
                codigo = "POS-01",
                ledId = "POS_NOVO_LED"
            )
        )
        assertTrue(duplicateResult.isFailure)

        // Inserir código POS-01 na prateleira PRAT_B2 (onde ainda não existe) é permitido
        // (Note que o seed já tem POS-01 na PRAT_B2, então vamos tentar POS-03)
        val validResult = repository.inserirPosicao(
            Posicao(
                prateleiraId = "PRAT_B2",
                codigo = "POS-03",
                ledId = "POS_B2_03_LED"
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
                posicaoId = "POS_B2_01",
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

        // 4. Vincular nova tag válida à posição pendente POS_B2_01 deve suceder
        val validResult = repository.vincularTag(
            TagNFC(
                uid = "04:A1:2B:3C:4D:5E:07",
                posicaoId = "POS_B2_01",
                dataVinculo = 1700000000000L
            )
        )
        assertTrue(validResult.isSuccess)
        val tagRecuperada = repository.getTagByUid("04:A1:2B:3C:4D:5E:07")
        assertNotNull(tagRecuperada)
        assertEquals("POS_B2_01", tagRecuperada.posicaoId)
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
        val result = repository.moverItem("ITEM_1", "POS_A1_01", "POS_B1_02", 10)
        assertTrue(result.isSuccess)

        val estoqueOrigem = repository.getEstoque("ITEM_1", "POS_A1_01")
        assertEquals(15, estoqueOrigem?.quantidade)

        val estoqueDestino = repository.getEstoque("ITEM_1", "POS_B1_02")
        assertEquals(10, estoqueDestino?.quantidade)

        val movimentacoes = repository.getMovimentacoes().first()
        val transferencia = movimentacoes.firstOrNull { it.tipo == TipoMovimentacao.TRANSFERENCIA && it.itemId == "ITEM_1" }
        assertNotNull(transferencia)
        assertEquals("POS_B1_02", transferencia.posicaoId)
        assertEquals(10, transferencia.quantidade)
    }
}
