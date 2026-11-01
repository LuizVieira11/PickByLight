package com.uniso.wms.pickbylight

import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.Item
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
import kotlin.test.assertTrue

class AddressCatalogFlowsTest {

    private lateinit var repository: InMemoryWmsRepository

    @BeforeTest
    fun setup() = runTest {
        repository = InMemoryWmsRepository()
        repository.resetarDadosDemo()
    }

    @Test
    fun testCadastroCascataEstantePrateleiraPosicao() = runTest {
        // 1. Estante
        val estante = Estante(
            codigo = "EST-C",
            nome = "Estante C - Elétrica",
            setor = "Elétrica",
            ledId = "EST_C_LED1"
        )
        val resEst = repository.inserirEstante(estante)
        assertTrue(resEst.isSuccess)

        // 2. Prateleira no contexto da Estante C
        val prateleira = Prateleira(
            estanteId = estante.id,
            codigo = "PRAT-C1",
            ledIdPonta = "PRAT_C1_LED1"
        )
        val resPrat = repository.inserirPrateleira(prateleira)
        assertTrue(resPrat.isSuccess)

        // 3. Validação Regex de Limites L×A×P (Seção 3.2.3)
        assertTrue(Posicao.validarLimitesLap("30x20x40cm"))
        assertTrue(Posicao.validarLimitesLap("150x80x200mm"))
        assertFalse(Posicao.validarLimitesLap("30x20cm"))
        assertFalse(Posicao.validarLimitesLap("invalido"))

        // 4. Posição no contexto da Prateleira C1
        val posicao = Posicao(
            prateleiraId = prateleira.id,
            codigo = "POS_C1_01",
            limitesLap = "30x20x40cm",
            ledId = "POS_C1_01_LED"
        )
        val resPos = repository.inserirPosicao(posicao)
        assertTrue(resPos.isSuccess)

        // Verificação final no banco
        val posCadastrada = repository.getPosicaoById(posicao.id)
        assertNotNull(posCadastrada)
        assertEquals("POS_C1_01", posCadastrada.codigo)
    }

    @Test
    fun testVinculacaoNfcUnicaEExclusiva() = runTest {
        // POS_B2_01 é uma das posições pendentes do seed inicial
        val posId = "POS_B2_01"
        val tagUid = "04:A1:2B:3C:4D:5E:07"

        val novaTag = TagNFC(
            uid = tagUid,
            posicaoId = posId,
            dataVinculo = 123456789L
        )
        val resVincular = repository.vincularTag(novaTag)
        assertTrue(resVincular.isSuccess)

        // Tentativa de vincular a MESMA tag a outra posição (POS_B2_02) -> Deve falhar
        val tagDuplicada = TagNFC(
            uid = tagUid,
            posicaoId = "POS_B2_02",
            dataVinculo = 123456790L
        )
        val resDuplicada = repository.vincularTag(tagDuplicada)
        assertTrue(resDuplicada.isFailure, "Não pode permitir duplicar tag em posições diferentes")

        // Tentativa de vincular OUTRA tag à mesma posição (POS_B2_01) -> Deve falhar
        val outraTag = TagNFC(
            uid = "04:A1:2B:3C:4D:5E:99",
            posicaoId = posId,
            dataVinculo = 123456791L
        )
        val resOutraTag = repository.vincularTag(outraTag)
        assertTrue(resOutraTag.isFailure, "Posição já possui tag vinculada")
    }

    @Test
    fun testTransferenciaInternaMoverItem() = runTest {
        val item = repository.getItemBySku("ARD-ESP32")
        assertNotNull(item)
        val estoqueOrigem = repository.getEstoque(item.id, "POS_A1_01")
        assertNotNull(estoqueOrigem)
        assertEquals(25, estoqueOrigem.quantidade)

        val posDestino = "POS_B1_01"

        // 1. Executa moverItem com origem, destino e quantidade
        val resMover = repository.moverItem(item.id, "POS_A1_01", posDestino, 10)
        assertTrue(resMover.isSuccess)

        // 2. Validações de saldo por posição
        val estoqueOrigemApos = repository.getEstoque(item.id, "POS_A1_01")
        assertEquals(15, estoqueOrigemApos?.quantidade)

        val estoqueDestinoApos = repository.getEstoque(item.id, posDestino)
        assertEquals(10, estoqueDestinoApos?.quantidade)

        val todasMovs = repository.getMovimentacoes().first()
        assertTrue(todasMovs.any { it.tipo == TipoMovimentacao.TRANSFERENCIA && it.itemId == item.id })
    }

    @Test
    fun testCadastroEEdicaoItemComSkuUnico() = runTest {
        val novoItem = Item(
            sku = "SEN-TEMP01",
            nome = "Sensor de Temperatura DS18B20",
            categoria = "Sensores",
            unidade = "UN",
            estoqueMinimo = 10
        )
        val resSalvar = repository.salvarItem(novoItem)
        assertTrue(resSalvar.isSuccess)

        // Edição do mesmo item (mantendo ou alterando propriedades)
        val itemEditado = novoItem.copy(nome = "Sensor de Temperatura Digital DS18B20", estoqueMinimo = 15)
        val resEditar = repository.salvarItem(itemEditado)
        assertTrue(resEditar.isSuccess)

        val recuperado = repository.getItemById(novoItem.id)
        assertNotNull(recuperado)
        assertEquals("Sensor de Temperatura Digital DS18B20", recuperado.nome)
        assertEquals(15, recuperado.estoqueMinimo)

        // Tentativa de criar outro item com o MESMO SKU -> Deve falhar
        val outroItemMesmoSku = Item(
            sku = "SEN-TEMP01",
            nome = "Outro Produto Duplicado",
            categoria = "Geral",
            unidade = "UN"
        )
        val resDuplicado = repository.salvarItem(outroItemMesmoSku)
        assertTrue(resDuplicado.isFailure, "SKU duplicado deve ser rejeitado")
    }
}
