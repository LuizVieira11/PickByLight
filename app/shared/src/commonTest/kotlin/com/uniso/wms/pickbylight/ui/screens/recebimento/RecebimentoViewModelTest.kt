package com.uniso.wms.pickbylight.ui.screens.recebimento

import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
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

class RecebimentoViewModelTest {

    private lateinit var repository: InMemoryWmsRepository
    private lateinit var feedbackController: FeedbackController

    @BeforeTest
    fun setup() = runTest {
        repository = InMemoryWmsRepository()
        repository.resetarDadosDemo()
        feedbackController = FeedbackController()
    }

    @Test
    fun testCenario1LeituraTagNfcValidaResolveEnderecoEAvancaParaFormulario() = runTest {
        val viewModel = RecebimentoViewModel(repository, feedbackController, this)

        assertEquals(RecebimentoStep.SCAN_NFC, viewModel.uiState.value.step)

        // Tag cadastrada no seed para POS_A2_05: "04:A1:2B:3C:4D:5E:04"
        viewModel.processarLeituraNfc("04:A1:2B:3C:4D:5E:04")
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(RecebimentoStep.FORM, state.step)
        assertNotNull(state.resolvedPosicao)
        assertEquals("POS_A2_05", state.resolvedPosicao?.id)
        assertEquals("04:A1:2B:3C:4D:5E:04", state.resolvedTagUid)
        assertTrue(state.enderecoFormatado.contains("PRAT-A2"))
        assertTrue(state.enderecoFormatado.contains("POS-05"))
        assertFalse(state.isErrorFlashingRed)
        assertNull(state.unmappedTagErrorUid)
    }

    @Test
    fun testCenario2LeituraTagNfcDesconhecidaAtivaTelaVermelhaEModalCadastro() = runTest {
        val viewModel = RecebimentoViewModel(repository, feedbackController, this)

        val unknownUid = "04:FF:EE:DD:CC:BB:99"
        viewModel.processarLeituraNfc(unknownUid)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(RecebimentoStep.SCAN_NFC, state.step)
        assertTrue(state.isErrorFlashingRed, "Deve exibir estado de erro visual (tela vermelha)")
        assertEquals(unknownUid, state.unmappedTagErrorUid, "Deve armazenar o UID lido para redirecionar ao cadastro")

        // Ao fechar ou redirecionar, limpa o estado de erro
        viewModel.dismissUnmappedTagError()
        assertFalse(viewModel.uiState.value.isErrorFlashingRed)
        assertNull(viewModel.uiState.value.unmappedTagErrorUid)
    }

    @Test
    fun testFallbackSelecaoManualDeEnderecoAvancaParaFormulario() = runTest {
        val viewModel = RecebimentoViewModel(repository, feedbackController, this)

        // Seleciona manualmente uma posição sem tag NFC vinculada (POS_B2_01)
        val posSemTag = repository.getPosicaoById("POS_B2_01")
        assertNotNull(posSemTag)

        viewModel.selecionarEnderecoManualmente(posSemTag)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(RecebimentoStep.FORM, state.step)
        assertEquals("POS_B2_01", state.resolvedPosicao?.id)
        assertNull(state.resolvedTagUid)
        assertTrue(state.enderecoFormatado.contains("PRAT-B2"))
        assertTrue(state.enderecoFormatado.contains("POS-01"))
    }

    @Test
    fun testConfirmarEntradaAtualizaEstoquePosicaoERegistraMovimentacaoEntrada() = runTest {
        val viewModel = RecebimentoViewModel(repository, feedbackController, this)

        // 1. Bipa tag da POS_A2_05
        viewModel.processarLeituraNfc("04:A1:2B:3C:4D:5E:04")
        testScheduler.runCurrent()

        // 2. Seleciona item LED-WS2812 (que já possui 12 UN em POS_A2_05 no seed)
        val item = repository.getItemBySku("LED-WS2812")
        assertNotNull(item)
        val saldoInicial = repository.getEstoque(item.id, "POS_A2_05")?.quantidade ?: 0
        assertEquals(12, saldoInicial)

        viewModel.selecionarItem(item)
        viewModel.setQuantidade(8)
        viewModel.setObservacao("NF-98765 Lote 2026")

        assertTrue(viewModel.uiState.value.hasDirtyForm)
        assertTrue(viewModel.uiState.value.isFormValid)

        // 3. Confirma a entrada
        viewModel.confirmarEntrada()
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(RecebimentoStep.SUCCESS, state.step)
        assertTrue(state.successMessage.contains("Entrada registrada:"))
        assertTrue(state.successMessage.contains(item.nome))
        assertTrue(state.successMessage.contains("× 8"))
        assertEquals(state.successMessage, state.snackbarMessage)

        // 4. Verifica atualização na tabela EstoquePosicao (12 + 8 = 20)
        val estoqueAtualizado = repository.getEstoque(item.id, "POS_A2_05")
        assertNotNull(estoqueAtualizado)
        assertEquals(20, estoqueAtualizado.quantidade)

        // 5. Verifica gravação na tabela Movimentacao (ENTRADA)
        val movs = repository.getMovimentacoes().first()
        val movEntrada = movs.find {
            it.tipo == TipoMovimentacao.ENTRADA &&
                it.itemId == item.id &&
                it.posicaoId == "POS_A2_05"
        }
        assertNotNull(movEntrada)
        assertEquals(8, movEntrada.quantidade)
        assertEquals("NF-98765 Lote 2026", movEntrada.observacao)
        assertFalse(movEntrada.modoManual)

        // 6. Inicia Nova Entrada e verifica limpeza do estado
        viewModel.iniciarNovaEntrada()
        val resetState = viewModel.uiState.value
        assertEquals(RecebimentoStep.SCAN_NFC, resetState.step)
        assertNull(resetState.selectedItem)
        assertNull(resetState.resolvedPosicao)
        assertEquals(1, resetState.quantidade)
        assertEquals("", resetState.observacao)
        assertFalse(resetState.hasDirtyForm)
    }

    @Test
    fun testCadastrarNovoItemRapidoNoRecebimentoSelecionaItemAutomaticamente() = runTest {
        val viewModel = RecebimentoViewModel(repository, feedbackController, this)

        viewModel.processarLeituraNfc("04:A1:2B:3C:4D:5E:01")
        testScheduler.runCurrent()

        val res = viewModel.cadastrarNovoItemRapido(
            sku = "novo-sensor-01",
            nome = "Sensor Ultrassônico HC-SR04",
            categoria = "Sensores",
            unidade = "un"
        )
        assertTrue(res.isSuccess)

        val criado = res.getOrNull()
        assertNotNull(criado)
        assertEquals("NOVO-SENSOR-01", criado.sku)
        assertEquals("UN", criado.unidade)

        // Deve já estar selecionado no formulário de recebimento
        assertEquals(criado.id, viewModel.uiState.value.selectedItem?.id)
        assertTrue(viewModel.uiState.value.isFormValid)

        // Confirma recebimento de 10 unidades do novo item em POS_A1_01
        viewModel.setQuantidade(10)
        viewModel.confirmarEntrada()
        testScheduler.runCurrent()

        val estoqueNovoItem = repository.getEstoque(criado.id, "POS_A1_01")
        assertNotNull(estoqueNovoItem)
        assertEquals(10, estoqueNovoItem.quantidade)
    }

    @Test
    fun testModoRecebimentoMultiploGuiadoPassoAPasso() = runTest {
        val viewModel = RecebimentoViewModel(repository, feedbackController, this)

        // 1. Seleciona o Modo de Recebimento Múltiplo
        viewModel.selecionarModo(ModoRecebimento.MULTIPLO)
        assertEquals(ModoRecebimento.MULTIPLO, viewModel.uiState.value.modo)
        assertEquals(RecebimentoStep.MONTAGEM_LISTA, viewModel.uiState.value.step)

        val itemA = repository.getItemBySku("ARD-ESP32")
        val itemB = repository.getItemBySku("MOD-RC522")
        assertNotNull(itemA)
        assertNotNull(itemB)

        val estoqueInicialA = repository.getEstoque(itemA.id, "POS_A1_01")?.quantidade ?: 0
        val estoqueInicialB = repository.getEstoque(itemB.id, "POS_A1_02")?.quantidade ?: 0

        // 2. Adiciona Item A (10 un) e Item B (5 un) à lista
        viewModel.selecionarItem(itemA)
        viewModel.setQuantidade(10)
        viewModel.adicionarItemAoLote()

        viewModel.selecionarItem(itemB)
        viewModel.setQuantidade(5)
        viewModel.adicionarItemAoLote()

        assertEquals(2, viewModel.uiState.value.itensLote.size)

        // 3. Inicia o Recebimento Guiado -> Passo 1 de 2: Item A (10 un)
        viewModel.iniciarRecebimentoGuiado()
        val statePasso1 = viewModel.uiState.value
        assertEquals(RecebimentoStep.GUIADO_NFC, statePasso1.step)
        assertEquals(0, statePasso1.passoGuiadoIndex)
        assertEquals(2, statePasso1.totalPassosGuiados)
        assertEquals(itemA.id, statePasso1.itemGuiadoAtual?.item?.id)
        assertEquals(10, statePasso1.itemGuiadoAtual?.quantidade)

        // 4. Bipa a Tag NFC da POS_A1_01 ("04:A1:2B:3C:4D:5E:01") para guardar o Item A
        viewModel.processarLeituraNfc("04:A1:2B:3C:4D:5E:01")
        testScheduler.runCurrent()

        // Deve ter confirmado a entrada do Item A e avançado para o Passo 2 de 2: Item B (5 un)
        val statePasso2 = viewModel.uiState.value
        assertEquals(RecebimentoStep.GUIADO_NFC, statePasso2.step)
        assertEquals(1, statePasso2.passoGuiadoIndex)
        assertEquals(itemB.id, statePasso2.itemGuiadoAtual?.item?.id)
        assertEquals(5, statePasso2.itemGuiadoAtual?.quantidade)
        assertEquals(estoqueInicialA + 10, repository.getEstoque(itemA.id, "POS_A1_01")?.quantidade)

        // 5. Bipa a Tag NFC da POS_A1_02 ("04:A1:2B:3C:4D:5E:02") para guardar o Item B
        viewModel.processarLeituraNfc("04:A1:2B:3C:4D:5E:02")
        testScheduler.runCurrent()

        // Deve ter concluído o recebimento guiado e exibido o resumo de sucesso
        val stateFinal = viewModel.uiState.value
        assertEquals(RecebimentoStep.SUCCESS, stateFinal.step)
        assertEquals(2, stateFinal.resumoRecebimentoMultiplo.size)
        assertEquals(estoqueInicialB + 5, repository.getEstoque(itemB.id, "POS_A1_02")?.quantidade)
    }
}
