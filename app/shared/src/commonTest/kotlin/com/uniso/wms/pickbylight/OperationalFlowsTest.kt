package com.uniso.wms.pickbylight

import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.domain.model.Movimentacao
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationalFlowsTest {

    private lateinit var repository: WmsRepository

    @BeforeTest
    fun setup() = runTest {
        repository = InMemoryWmsRepository()
        repository.resetarDadosDemo()
    }

    @Test
    fun testRecebimentoFluxoComTagValida() = runTest {
        // Tag válida do seed vinculada a POS_A2_01 (Fita de LED WS2812)
        val validTagUid = "04:A1:2B:3C:4D:5E:04"
        val tag = repository.getTagByUid(validTagUid)
        assertNotNull(tag, "Tag deve existir no banco")
        assertEquals("POS_A2_01", tag.posicaoId)

        val itemAntes = repository.getItemBySku("LED-WS2812")
        assertNotNull(itemAntes)
        val estoqueInicial = repository.getEstoque(itemAntes.id, tag.posicaoId)?.quantidade ?: 0

        // Executa recebimento de 15 unidades na posição da tag
        val qtdInserida = 15
        val novoEstoque = estoqueInicial + qtdInserida
        repository.atualizarEstoquePosicao(itemAntes.id, tag.posicaoId, novoEstoque)

        val mov = Movimentacao(
            tipo = TipoMovimentacao.ENTRADA,
            itemId = itemAntes.id,
            posicaoId = tag.posicaoId,
            quantidade = qtdInserida,
            observacao = "NF 123456"
        )
        val resMov = repository.registrarMovimentacao(mov)
        assertTrue(resMov.isSuccess)

        // Validações
        val estoqueDepois = repository.getEstoque(itemAntes.id, tag.posicaoId)
        assertNotNull(estoqueDepois)
        assertEquals(novoEstoque, estoqueDepois.quantidade)

        val todasMov = repository.getMovimentacoes().first()
        assertTrue(todasMov.any { it.id == mov.id && it.tipo == TipoMovimentacao.ENTRADA && it.quantidade == qtdInserida })
    }

    @Test
    fun testRecebimentoTagNaoCadastrada() = runTest {
        val unknownTagUid = "04:FF:EE:DD:CC:BB:AA"
        val tag = repository.getTagByUid(unknownTagUid)
        assertNull(tag, "Tag virgem não deve ser resolvida")
    }

    @Test
    fun testSeparacaoFluxoCompletoComLockELiberacao() = runTest {
        val item = repository.getItemBySku("ARD-ESP32")
        assertNotNull(item)
        val estoqueInicial = repository.getEstoque(item.id, "POS_A1_01")?.quantidade ?: 25
        assertFalse(item.emSeparacao, "Item deve iniciar livre")

        // 1. Ativa Lock Otimista
        repository.setEmSeparacao(item.id, true)
        val itemComLock = repository.getItemById(item.id)
        assertNotNull(itemComLock)
        assertTrue(itemComLock.emSeparacao, "Item deve estar travado")

        // 2. Executa Baixa de 5 unidades na posição física
        val qtdRetirada = 5
        val saldoFinal = estoqueInicial - qtdRetirada
        repository.atualizarEstoquePosicao(item.id, "POS_A1_01", saldoFinal)

        val movSaida = Movimentacao(
            tipo = TipoMovimentacao.SAIDA,
            itemId = item.id,
            posicaoId = "POS_A1_01",
            quantidade = qtdRetirada,
            modoManual = false
        )
        repository.registrarMovimentacao(movSaida)

        // 3. Libera Lock
        repository.setEmSeparacao(item.id, false)

        val itemFinal = repository.getItemById(item.id)
        assertNotNull(itemFinal)
        assertFalse(itemFinal.emSeparacao, "Item deve estar liberado após conclusão")

        val estoqueFinal = repository.getEstoque(item.id, "POS_A1_01")
        assertNotNull(estoqueFinal)
        assertEquals(saldoFinal, estoqueFinal.quantidade)
    }

    @Test
    fun testSeparacaoBloqueioConcorrenciaItemEmSeparacao() = runTest {
        val item = repository.getItemBySku("MOD-RC522")
        assertNotNull(item)

        // Simula outro operador que já reservou o item
        repository.setEmSeparacao(item.id, true)

        val itemConsultado = repository.getItemById(item.id)
        assertNotNull(itemConsultado)
        assertTrue(itemConsultado.emSeparacao, "Tentativa de coleta concorrente deve detectar lock ativo")
    }

    @Test
    fun testSeparacaoModoManualRegistraAuditoria() = runTest {
        val item = repository.getItemBySku("SRV-MTR01")
        assertNotNull(item)

        // Simula retirada em modo manual (falha de hardware / sem LED)
        val movManual = Movimentacao(
            tipo = TipoMovimentacao.SAIDA,
            itemId = item.id,
            posicaoId = "POS_A3_02",
            quantidade = 2,
            modoManual = true
        )
        repository.registrarMovimentacao(movManual)

        val movs = repository.getMovimentacoes().first()
        val gravada = movs.find { it.id == movManual.id }
        assertNotNull(gravada)
        assertTrue(gravada.modoManual, "Movimentação em contingência deve registrar modoManual = true")
    }

    @Test
    fun testPokaYokeValidacaoTagsDivergentes() = runTest {
        val tagPosicaoA = repository.getTagByPosicaoId("POS_A1_01")
        val tagPosicaoB = repository.getTagByPosicaoId("POS_A2_01")
        assertNotNull(tagPosicaoA)
        assertNotNull(tagPosicaoB)

        // Poka-Yoke: as tags físicas das duas posições são distintas
        assertFalse(tagPosicaoA.uid.equals(tagPosicaoB.uid, ignoreCase = true))
    }
}
