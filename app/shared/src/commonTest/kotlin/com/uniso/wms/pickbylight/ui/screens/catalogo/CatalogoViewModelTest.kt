package com.uniso.wms.pickbylight.ui.screens.catalogo

import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.data.settings.AppSettings
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.util.FeedbackController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogoViewModelTest {

    private lateinit var repository: InMemoryWmsRepository
    private lateinit var feedbackController: FeedbackController

    @BeforeTest
    fun setup() = runTest {
        repository = InMemoryWmsRepository()
        repository.resetarDadosDemo()
        feedbackController = FeedbackController()
        AppSettings.setModoDebugNfc(false)
    }

    @Test
    fun testEstadoInicialESuccessDoCatalogo() = runTest {
        val viewModel = CatalogoViewModel(repository, feedbackController)

        val state = viewModel.uiState.first { it is CatalogoUiState.Success } as CatalogoUiState.Success

        // O seed padrão contém 5 itens
        assertEquals(5, state.totalItensCount)
        assertEquals(5, state.itens.size)
        assertTrue(state.categoriasDisponiveis.isNotEmpty())
        assertFalse(state.modoDebugNfc)

        // Verifica formatação do endereço completo: Estante A - Principal > Prateleira 1 > POS-01
        val itemEsp32 = state.itens.first { it.item.sku == "ARD-ESP32" }
        assertTrue(itemEsp32.enderecoCompleto.contains("POS-01") || itemEsp32.enderecoCompleto.contains("POS_A1_01"))
        assertTrue(itemEsp32.estoques.isNotEmpty())
    }

    @Test
    fun testFiltroBuscaReativaPorSkuENome() = runTest {
        val viewModel = CatalogoViewModel(repository, feedbackController)

        // 1. Busca por SKU específico
        viewModel.setSearchQuery("ARD-ESP32")
        val stateSku = viewModel.uiState.first {
            it is CatalogoUiState.Success && it.searchQuery == "ARD-ESP32"
        } as CatalogoUiState.Success

        assertEquals(1, stateSku.itens.size)
        assertEquals("ARD-ESP32", stateSku.itens.first().item.sku)

        // 2. Busca por categoria / nome (MOD-RC522 tem categoria "Sensores")
        viewModel.setSearchQuery("Sensores")
        val stateNome = viewModel.uiState.first {
            it is CatalogoUiState.Success && it.searchQuery == "Sensores"
        } as CatalogoUiState.Success

        assertTrue(stateNome.itens.isNotEmpty())
        assertTrue(stateNome.itens.all {
            it.item.nome.contains("Sensores", ignoreCase = true) ||
            it.item.sku.contains("Sensores", ignoreCase = true) ||
            it.item.categoria.contains("Sensores", ignoreCase = true)
        })

        // 3. Limpa busca
        viewModel.setSearchQuery("")
        val stateReset = viewModel.uiState.first {
            it is CatalogoUiState.Success && it.searchQuery.isEmpty()
        } as CatalogoUiState.Success
        assertEquals(5, stateReset.itens.size)
    }

    @Test
    fun testFiltroPorCategoriaECuidadoComCaseInsensitive() = runTest {
        val viewModel = CatalogoViewModel(repository, feedbackController)

        viewModel.setCategoria("Microcontroladores")
        val state = viewModel.uiState.first {
            it is CatalogoUiState.Success && it.categoriaSelecionada == "Microcontroladores"
        } as CatalogoUiState.Success

        assertEquals(1, state.itens.size)
        assertEquals("ARD-ESP32", state.itens.first().item.sku)

        // Reset categoria
        viewModel.setCategoria(null)
        val stateReset = viewModel.uiState.first {
            it is CatalogoUiState.Success && it.categoriaSelecionada == null
        } as CatalogoUiState.Success
        assertEquals(5, stateReset.itens.size)
    }

    @Test
    fun testFiltroApenasAbaixoDoMinimo() = runTest {
        // Inserimos um item com quantidade menor que o estoque mínimo
        val itemAbaixoMinimo = Item(
            sku = "TEST-LOW-STOCK",
            nome = "Item com Estoque Baixo",
            categoria = "Testes",
            unidade = "UN",
            estoqueMinimo = 10
        )
        repository.salvarItem(itemAbaixoMinimo)
        repository.atualizarEstoquePosicao(itemAbaixoMinimo.id, "POS_A1_01", 2)

        val viewModel = CatalogoViewModel(repository, feedbackController)

        viewModel.toggleApenasAbaixoMinimo()
        val state = viewModel.uiState.first {
            it is CatalogoUiState.Success && it.apenasAbaixoMinimo && it.itens.any { itemUi -> itemUi.item.sku == "TEST-LOW-STOCK" }
        } as CatalogoUiState.Success

        assertTrue(state.itens.isNotEmpty())
        assertTrue(state.itens.all { it.isAbaixoMinimo })
        assertEquals(1, state.itens.size)
        assertEquals("TEST-LOW-STOCK", state.itens.first().item.sku)
    }

    @Test
    fun testValidacaoCadastroEEdicaoItem() = runTest {
        val viewModel = CatalogoViewModel(repository, feedbackController)

        // 1. SKU em branco deve falhar
        val itemSemSku = Item(
            sku = "   ",
            nome = "Produto Teste",
            categoria = "Testes",
            unidade = "UN"
        )
        val resSemSku = viewModel.salvarItem(itemSemSku)
        assertTrue(resSemSku.isFailure)

        // 2. Nome em branco deve falhar
        val itemSemNome = Item(
            sku = "SKU-VALIDO",
            nome = "   ",
            categoria = "Testes",
            unidade = "UN"
        )
        val resSemNome = viewModel.salvarItem(itemSemNome)
        assertTrue(resSemNome.isFailure)

        // 3. Estoque mínimo negativo deve falhar
        val itemMinimoNegativo = Item(
            sku = "SKU-VALIDO",
            nome = "Produto Válido",
            categoria = "Testes",
            unidade = "UN",
            estoqueMinimo = -5
        )
        val resMinimoNegativo = viewModel.salvarItem(itemMinimoNegativo)
        assertTrue(resMinimoNegativo.isFailure)

        // 4. Item novo válido deve ter sucesso
        val itemValido = Item(
            sku = "SKU-NOVO-TESTE",
            nome = "Novo Item Para Teste",
            categoria = "Testes",
            unidade = "UN",
            estoqueMinimo = 5
        )
        val resValido = viewModel.salvarItem(itemValido)
        assertTrue(resValido.isSuccess)

        // 5. SKU duplicado com outro item deve falhar
        val itemDuplicado = Item(
            sku = "ARD-ESP32", // Já existe no seed
            nome = "Tentativa de Duplicar SKU",
            categoria = "Testes",
            unidade = "UN"
        )
        val resDuplicado = viewModel.salvarItem(itemDuplicado)
        assertTrue(resDuplicado.isFailure)
    }

    @Test
    fun testMoverItemTransferenciaInternaEValidacoes() = runTest {
        val viewModel = CatalogoViewModel(repository, feedbackController)

        val item = repository.getItemBySku("ARD-ESP32")
        assertNotNull(item)
        val posOrigem = "POS_A1_01"
        val posDestino = "POS_B1_01"

        // 1. Mover para a mesma posição deve falhar
        val resMesmaPos = viewModel.moverItem(item.id, posOrigem, posOrigem, 5)
        assertTrue(resMesmaPos.isFailure)

        // 2. Mover quantidade <= 0 deve falhar
        val resQtdInvalida = viewModel.moverItem(item.id, posOrigem, posDestino, 0)
        assertTrue(resQtdInvalida.isFailure)

        // 3. Mover quantidade maior que saldo disponível na origem deve falhar
        val resQtdExcedente = viewModel.moverItem(item.id, posOrigem, posDestino, 25 + 50)
        assertTrue(resQtdExcedente.isFailure)

        // 4. Movimentação parcial: move 5 de 25 unidades da POS_A1_01 para POS_B1_01
        val resSucessoParcial = viewModel.moverItem(item.id, posOrigem, posDestino, 5)
        assertTrue(resSucessoParcial.isSuccess)

        // Valida que o saldo na POS_A1_01 foi reduzido para 20 e POS_B1_01 possui 5
        val estoqueOrigemAposParcial = repository.getEstoque(item.id, posOrigem)
        assertEquals(20, estoqueOrigemAposParcial?.quantidade)

        val estoqueDestinoAposParcial = repository.getEstoque(item.id, posDestino)
        assertEquals(5, estoqueDestinoAposParcial?.quantidade)

        // Saldo total permanece 25
        val itemComEstoque = repository.getItemComEstoqueById(item.id)
        assertNotNull(itemComEstoque)
        assertEquals(25, itemComEstoque.saldoTotal)
        assertEquals(2, itemComEstoque.estoques.size)

        // 5. Movimentação total do restante da POS_A1_01 (20 unidades para POS_B1_01)
        val resSucessoTotal = viewModel.moverItem(item.id, posOrigem, posDestino, 20)
        assertTrue(resSucessoTotal.isSuccess)

        // Agora POS_A1_01 não possui mais saldo, e POS_B1_01 possui todas as 25 unidades
        val estoqueOrigemAposTotal = repository.getEstoque(item.id, posOrigem)
        assertTrue(estoqueOrigemAposTotal == null || estoqueOrigemAposTotal.quantidade == 0)

        val estoqueDestinoAposTotal = repository.getEstoque(item.id, posDestino)
        assertEquals(25, estoqueDestinoAposTotal?.quantidade)
    }

    @Test
    fun testAlternarEmSeparacaoAcaoDebug() = runTest {
        val viewModel = CatalogoViewModel(repository, feedbackController)

        val item = repository.getItemBySku("ARD-ESP32")
        assertNotNull(item)
        assertFalse(item.emSeparacao)

        // 1. Alterna para true
        val resLock = viewModel.alternarEmSeparacao(item.id)
        assertTrue(resLock.isSuccess)
        assertTrue(resLock.getOrNull() == true)

        val itemComLock = repository.getItemById(item.id)
        assertNotNull(itemComLock)
        assertTrue(itemComLock.emSeparacao)

        // 2. Alterna de volta para false
        val resUnlock = viewModel.alternarEmSeparacao(item.id)
        assertTrue(resUnlock.isSuccess)
        assertFalse(resUnlock.getOrNull() == true)

        val itemSemLock = repository.getItemById(item.id)
        assertNotNull(itemSemLock)
        assertFalse(itemSemLock.emSeparacao)
    }

    @Test
    fun testBuscarPosicaoPorTagUid() = runTest {
        val viewModel = CatalogoViewModel(repository, feedbackController)

        // Tag existente no seed: "04:A1:2B:3C:4D:5E:01" vinculada a "POS_A1_01" (código "POS-01")
        val posicaoEncontrada = viewModel.buscarPosicaoPorTagUid("04:A1:2B:3C:4D:5E:01")
        assertNotNull(posicaoEncontrada)
        assertEquals("POS_A1_01", posicaoEncontrada.id)
        assertEquals("POS-01", posicaoEncontrada.codigo)

        // Tag inexistente
        val posicaoInexistente = viewModel.buscarPosicaoPorTagUid("TAG_INEXISTENTE")
        assertNull(posicaoInexistente)
    }

    @Test
    fun testModoDebugNfcRefletidoNoUiState() = runTest {
        val viewModel = CatalogoViewModel(repository, feedbackController)

        AppSettings.setModoDebugNfc(false)
        val stateSemDebug = viewModel.uiState.first {
            it is CatalogoUiState.Success && !it.modoDebugNfc
        } as CatalogoUiState.Success
        assertFalse(stateSemDebug.modoDebugNfc)

        AppSettings.setModoDebugNfc(true)
        val stateComDebug = viewModel.uiState.first {
            it is CatalogoUiState.Success && it.modoDebugNfc
        } as CatalogoUiState.Success
        assertTrue(stateComDebug.modoDebugNfc)
    }
}
