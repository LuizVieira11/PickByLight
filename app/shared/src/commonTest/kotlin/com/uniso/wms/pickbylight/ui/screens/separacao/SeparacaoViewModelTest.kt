package com.uniso.wms.pickbylight.ui.screens.separacao

import com.uniso.wms.pickbylight.data.mqtt.FakeMqttService
import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.domain.model.EstoquePosicao
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.domain.model.TarefaSeparacao
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
import com.uniso.wms.pickbylight.util.FeedbackController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SeparacaoViewModelTest {

    private lateinit var repository: InMemoryWmsRepository
    private lateinit var mqttService: FakeMqttService
    private lateinit var feedbackController: FeedbackController

    @BeforeTest
    fun setup() = runTest {
        repository = InMemoryWmsRepository()
        repository.resetarDadosDemo()
        mqttService = FakeMqttService()
        feedbackController = FeedbackController()
    }

    @Test
    fun testAgrupamentoMultiplosItensNaMesmaPosicaoFisicaEOrdenacaoDeRota() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        // Criamos um item extra alocado na mesma posição que o ITEM_3 (POS_A2_05)
        val itemExtraNaMesmaPos = Item(
            id = "ITEM_EXTRA_A2_05",
            sku = "SKU-EXTRA-A2-05",
            nome = "Cabo de Programação USB",
            categoria = "Acessórios",
            unidade = "UN",
            estoqueMinimo = 2,
            emSeparacao = false
        )
        repository.salvarItem(itemExtraNaMesmaPos)
        repository.atualizarEstoquePosicao("ITEM_EXTRA_A2_05", "POS_A2_05", 10)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        // Selecionamos:
        // - ITEM_1 na POS_A1_01 (1 unidade)
        // - ITEM_3 na POS_A2_05 (2 unidades)
        // - ITEM_EXTRA_A2_05 na POS_A2_05 (1 unidade)
        val selecao = mapOf(
            "ITEM_1" to 1,
            "ITEM_3" to 2,
            "ITEM_EXTRA_A2_05" to 1
        )

        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = selecao
        )

        // Executa as tarefas agendadas no tempo virtual atual (sem expirar o timer de 60s)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value

        // Validação da Aglutinação (Grouping):
        // Eram 3 itens selecionados, mas como 2 residem em POS_A2_05, foram gerados apenas 2 passos de rota!
        assertEquals(2, state.totalPassos)
        assertEquals(EtapaSeparacao.VALIDACAO_NFC, state.etapa)
        assertEquals(0, state.passoAtualIndex)

        // Validação da Ordenação Sequencial de Rota (POS_A1_01 -> POS_A2_05)
        val primeiroPasso = state.passos[0]
        val segundoPasso = state.passos[1]

        assertEquals("POS_A1_01", primeiroPasso.posicao.id)
        assertEquals(1, primeiroPasso.itens.size)
        assertEquals("ITEM_1", primeiroPasso.itens[0].item.id)

        assertEquals("POS_A2_05", segundoPasso.posicao.id)
        assertEquals(2, segundoPasso.itens.size) // 2 itens agrupados no mesmo endereço!

        val idsNoPasso2 = segundoPasso.itens.map { it.item.id }
        assertTrue(idsNoPasso2.contains("ITEM_3"))
        assertTrue(idsNoPasso2.contains("ITEM_EXTRA_A2_05"))

        // Validação do Lock Otimista
        val itensAtualizados = repository.getItens().first()
        assertTrue(itensAtualizados.find { it.id == "ITEM_1" }!!.emSeparacao)
        assertTrue(itensAtualizados.find { it.id == "ITEM_3" }!!.emSeparacao)
        assertTrue(itensAtualizados.find { it.id == "ITEM_EXTRA_A2_05" }!!.emSeparacao)
    }

    @Test
    fun testValidacaoNfcPokaYokeLocalIncorretoBloqueiaAvanco() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        // Inicia coleta de ITEM_1 (espera tag da POS_A1_01 = "04:A1:2B:3C:4D:5E:01")
        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_1" to 2)
        )
        testScheduler.runCurrent()

        // Bipa uma tag INCORRETA (pertencente a POS_A1_02)
        viewModel.processarLeituraNfc(
            scannedUid = "04:A1:2B:3C:4D:5E:02",
            allTags = tags,
            allPosicoes = posicoes,
            allPrateleiras = prateleiras,
            allEstantes = estantes
        )

        val state = viewModel.uiState.value

        // Poka-Yoke: deve piscar vermelho, abrir modal de erro e permanecer em VALIDACAO_NFC
        assertTrue(state.isVisualFlashingRed)
        assertFalse(state.isVisualFlashingGreen)
        assertNotNull(state.showWrongTagModal)
        assertEquals(EtapaSeparacao.VALIDACAO_NFC, state.etapa)
    }

    @Test
    fun testValidacaoNfcPokaYokeSucessoEAvancoParaConfirmacao() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_1" to 2)
        )
        testScheduler.runCurrent()

        // Bipa a tag CORRETA da POS_A1_01
        viewModel.processarLeituraNfc(
            scannedUid = "04:A1:2B:3C:4D:5E:01",
            allTags = tags,
            allPosicoes = posicoes,
            allPrateleiras = prateleiras,
            allEstantes = estantes
        )

        val state = viewModel.uiState.value

        assertTrue(state.isVisualFlashingGreen)
        assertFalse(state.isVisualFlashingRed)
        assertEquals(null, state.showWrongTagModal)
        assertEquals(EtapaSeparacao.CONFIRMAR_QUANTIDADE, state.etapa)
    }

    @Test
    fun testFinalizacaoRetiradaDebitaEstoqueGeraMovimentacaoLiberaLockEApagaLed() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        val estoqueInicial = repository.getEstoque("ITEM_1", "POS_A1_01")?.quantidade ?: 0

        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_1" to 3)
        )
        testScheduler.runCurrent()

        viewModel.processarLeituraNfc(
            scannedUid = "04:A1:2B:3C:4D:5E:01",
            allTags = tags,
            allPosicoes = posicoes,
            allPrateleiras = prateleiras,
            allEstantes = estantes
        )

        // Finaliza a retirada confirmando 3 unidades
        viewModel.finalizarRetiradaPassoAtual(mapOf("ITEM_1" to 3))
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(EtapaSeparacao.CONCLUIDO, state.etapa)
        assertEquals(1, state.resumoFinal.size)
        assertEquals(3, state.resumoFinal[0].quantidadeConfirmada)

        // Verifica débito do estoque e liberação do lock
        val estoqueDepois = repository.getEstoque("ITEM_1", "POS_A1_01")?.quantidade ?: 0
        assertEquals(estoqueInicial - 3, estoqueDepois)
        val item1Depois = repository.getItens().first().find { it.id == "ITEM_1" }!!
        assertFalse(item1Depois.emSeparacao)

        // Verifica gravação de Movimentacao de SAIDA
        val movs = repository.getMovimentacoes().first()
        val movSaida = movs.find { it.itemId == "ITEM_1" && it.tipo == TipoMovimentacao.SAIDA }
        assertNotNull(movSaida)
        assertEquals(3, movSaida.quantidade)
    }

    @Test
    fun testBloqueioOtimistaPorOutroOperador() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        // Marca ITEM_1 como em separação prévia
        repository.setEmSeparacao("ITEM_1", true)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_1" to 1)
        )
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertNotNull(state.showConcurrencyLockError)
        assertEquals(EtapaSeparacao.SELECAO_E_RESUMO, state.etapa)
    }

    @Test
    fun testCancelarColetaLiberaLocksEApagaLed() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_1" to 2)
        )
        testScheduler.runCurrent()

        assertTrue(repository.getItens().first().find { it.id == "ITEM_1" }!!.emSeparacao)

        var navigateBackCalled = false
        viewModel.cancelarColetaEVoltar {
            navigateBackCalled = true
        }
        testScheduler.runCurrent()

        assertTrue(navigateBackCalled)
        assertFalse(repository.getItens().first().find { it.id == "ITEM_1" }!!.emSeparacao)
        assertEquals(EtapaSeparacao.SELECAO_E_RESUMO, viewModel.uiState.value.etapa)
    }

    @Test
    fun testTimeout60SegundosSemNfcCancelaColetaELiberaLock() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_1" to 2)
        )
        testScheduler.runCurrent()

        assertTrue(repository.getItens().first().find { it.id == "ITEM_1" }!!.emSeparacao)
        assertEquals(EtapaSeparacao.VALIDACAO_NFC, viewModel.uiState.value.etapa)

        // Avança o tempo virtual em 61 segundos (Cenário 5.3: Timeout do operador esgotado sem leitura NFC)
        testScheduler.advanceTimeBy(61_000)
        testScheduler.runCurrent()

        // Deve ter enviado APAGAR, cancelado a coleta, liberado o lock otimista e exibido mensagem de timeout
        assertFalse(repository.getItens().first().find { it.id == "ITEM_1" }!!.emSeparacao)
        assertEquals(EtapaSeparacao.SELECAO_E_RESUMO, viewModel.uiState.value.etapa)
        assertEquals("Tempo esgotado. Operação cancelada.", viewModel.uiState.value.timeoutCancelMessage)
        assertTrue(mqttService.sentCommands.last().contains("APAGAR"))

        viewModel.dismissTimeoutCancelMessage()
        assertEquals(null, viewModel.uiState.value.timeoutCancelMessage)
    }

    @Test
    fun testAlocacaoAutomaticaCenarioABestFitPriorizaMenorSaldoSuficiente() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        // Configura item de teste com estoque em duas posições: 15 na POS_A1_01 e 10 na POS_A1_02
        repository.atualizarEstoquePosicao("ITEM_1", "POS_A1_01", 15)
        repository.atualizarEstoquePosicao("ITEM_1", "POS_A1_02", 10)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        // Operador solicita 10 unidades do ITEM_1
        // Cenário A (Best Fit): Ambas têm >= 10 (15 e 10), mas POS_A1_02 tem o MENOR saldo (10)
        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_1" to 10)
        )
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(EtapaSeparacao.VALIDACAO_NFC, state.etapa)
        assertEquals(1, state.totalPassos)
        val passo = state.passos[0]
        assertEquals("POS_A1_02", passo.posicao.id)
        assertEquals(1, passo.itens.size)
        assertEquals("ITEM_1", passo.itens[0].item.id)
        assertEquals(10, passo.itens[0].quantidadeDesejada)

        // Cancela e testa pedindo 12 unidades: agora apenas POS_A1_01 (15 >= 12) atende
        viewModel.cancelarColetaEVoltar()
        testScheduler.runCurrent()

        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_1" to 12)
        )
        testScheduler.runCurrent()

        val state12 = viewModel.uiState.value
        assertEquals(1, state12.totalPassos)
        assertEquals("POS_A1_01", state12.passos[0].posicao.id)
        assertEquals(12, state12.passos[0].itens[0].quantidadeDesejada)
    }

    @Test
    fun testAlocacaoBestFitExemploPrat1Com3EPrat2Com10() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        // Exemplo da especificação: Tenho 3 na PRAT-1 (POS_A1_01) e 10 na PRAT-2 (POS_A2_01)
        val itemBestFit = Item(
            id = "ITEM_BEST_FIT",
            sku = "SKU-BEST-FIT",
            nome = "Item Best Fit",
            categoria = "Geral",
            unidade = "UN",
            estoqueMinimo = 1,
            emSeparacao = false
        )
        repository.salvarItem(itemBestFit)
        repository.atualizarEstoquePosicao("ITEM_BEST_FIT", "POS_A1_01", 3)
        repository.atualizarEstoquePosicao("ITEM_BEST_FIT", "POS_A2_01", 10)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        // 1) Peço 2 -> aloca na PRAT-1 (POS_A1_01, menor saldo >= 2)
        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_BEST_FIT" to 2)
        )
        testScheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.totalPassos)
        assertEquals("POS_A1_01", viewModel.uiState.value.passos[0].posicao.id)
        assertEquals("PRAT-A1", viewModel.uiState.value.passos[0].prateleira.codigo)
        assertEquals(2, viewModel.uiState.value.passos[0].itens[0].quantidadeDesejada)

        viewModel.cancelarColetaEVoltar()
        testScheduler.runCurrent()

        // 2) Peço 5 -> aloca na PRAT-2 (POS_A2_01, pois PRAT-1 não tem saldo suficiente)
        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_BEST_FIT" to 5)
        )
        testScheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.totalPassos)
        assertEquals("POS_A2_01", viewModel.uiState.value.passos[0].posicao.id)
        assertEquals("PRAT-A2", viewModel.uiState.value.passos[0].prateleira.codigo)
        assertEquals(5, viewModel.uiState.value.passos[0].itens[0].quantidadeDesejada)
    }

    @Test
    fun testAlocacaoAutomaticaCenarioBMultiplosLocaisFatiaPedidoPorSaldoDecrescente() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        // Configura item de teste com 15 na POS_A1_01 e 10 na POS_A1_02
        repository.atualizarEstoquePosicao("ITEM_1", "POS_A1_01", 15)
        repository.atualizarEstoquePosicao("ITEM_1", "POS_A1_02", 10)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        // Operador solicita 20 unidades do ITEM_1
        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_1" to 20)
        )
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(EtapaSeparacao.VALIDACAO_NFC, state.etapa)

        // Cenário B: Nenhuma posição individual possui 20. Fatia em 2 passos por ordem de saldo:
        // Passo 1: 15 na POS_A1_01 (maior saldo)
        // Passo 2: 5 na POS_A1_02 (restante)
        assertEquals(2, state.totalPassos)

        val passo1 = state.passos[0]
        assertEquals("POS_A1_01", passo1.posicao.id)
        assertEquals(15, passo1.itens[0].quantidadeDesejada)

        val passo2 = state.passos[1]
        assertEquals("POS_A1_02", passo2.posicao.id)
        assertEquals(5, passo2.itens[0].quantidadeDesejada)
    }

    @Test
    fun testFuncaoAlocarTarefasSeparacaoEstruturaEDados() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        val estoques = listOf(
            EstoquePosicao(id = "2", itemId = "ITEM_X", posicaoId = "POS_A", quantidade = 15),
            EstoquePosicao(id = "1", itemId = "ITEM_X", posicaoId = "POS_B", quantidade = 10)
        )

        // Cenário A: 10 unidades -> Posição A tem 15, Posição B tem 10 -> Best Fit seleciona POS_B (menor saldo >= 10)
        val tarefasA = viewModel.alocarTarefasSeparacao("ITEM_X", 10, estoques)
        assertEquals(1, tarefasA.size)
        assertEquals(TarefaSeparacao("ITEM_X", "POS_B", 10), tarefasA[0])

        // Cenário B: 20 unidades -> 15 em POS_A + 5 em POS_B (esvazia maior estoque primeiro)
        val tarefasB = viewModel.alocarTarefasSeparacao("ITEM_X", 20, estoques)
        assertEquals(2, tarefasB.size)
        assertEquals(TarefaSeparacao("ITEM_X", "POS_A", 15), tarefasB[0])
        assertEquals(TarefaSeparacao("ITEM_X", "POS_B", 5), tarefasB[1])
    }

    @Test
    fun testWavePickingOtimizacaoProximidadeAgrupaComOutroItemNoMesmoPassoELED() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        // Item A tem estoque na PRAT-A1 (POS_A1_01: 3 un) e na PRAT-A2 (POS_A2_01: 10 un)
        val itemA = Item(
            id = "ITEM_A",
            sku = "SKU-ITEM-A",
            nome = "Item A Proximidade",
            categoria = "Geral",
            unidade = "UN",
            estoqueMinimo = 1,
            emSeparacao = false
        )
        repository.salvarItem(itemA)
        repository.atualizarEstoquePosicao("ITEM_A", "POS_A1_01", 3)
        repository.atualizarEstoquePosicao("ITEM_A", "POS_A2_01", 10)

        // Item B tem estoque APENAS na PRAT-A2 (POS_A2_01: 5 un)
        val itemB = Item(
            id = "ITEM_B",
            sku = "SKU-ITEM-B",
            nome = "Item B Exclusivo PRAT-2",
            categoria = "Geral",
            unidade = "UN",
            estoqueMinimo = 1,
            emSeparacao = false
        )
        repository.salvarItem(itemB)
        repository.atualizarEstoquePosicao("ITEM_B", "POS_A2_01", 5)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        // Operador solicita: Item A (2 un) e Item B (2 un)
        // O algoritmo de Wave Picking deve perceber que o Item B só pode vir de POS_A2_01 (PRAT-A2),
        // e portanto alocar as 2 unidades do Item A também em POS_A2_01 para agrupar em 1 só passo (1 LED).
        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf(
                "ITEM_A" to 2,
                "ITEM_B" to 2
            )
        )
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(EtapaSeparacao.VALIDACAO_NFC, state.etapa)

        // Verificação: apenas 1 passo gerado, agrupando ambos na PRAT-A2 (POS_A2_01)
        assertEquals(1, state.totalPassos)
        val passoUnico = state.passos[0]
        assertEquals("POS_A2_01", passoUnico.posicao.id)
        assertEquals("PRAT-A2", passoUnico.prateleira.codigo)
        assertEquals(2, passoUnico.itens.size)

        val itemIds = passoUnico.itens.map { it.item.id }
        assertTrue(itemIds.contains("ITEM_A"))
        assertTrue(itemIds.contains("ITEM_B"))
    }

    @Test
    fun testWavePickingSemProximidadeUsaBestFitNoMenorSaldo() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        // Item A tem estoque na PRAT-A1 (POS_A1_01: 3 un) e na PRAT-A2 (POS_A2_01: 10 un)
        val itemA = Item(
            id = "ITEM_A_SOLO",
            sku = "SKU-ITEM-A-SOLO",
            nome = "Item A Solo",
            categoria = "Geral",
            unidade = "UN",
            estoqueMinimo = 1,
            emSeparacao = false
        )
        repository.salvarItem(itemA)
        repository.atualizarEstoquePosicao("ITEM_A_SOLO", "POS_A1_01", 3)
        repository.atualizarEstoquePosicao("ITEM_A_SOLO", "POS_A2_01", 10)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        // Operador solicita Item A (2 un) sem outros itens no carrinho
        // Como nenhuma outra prateleira é visitada, First Fit aloca na PRAT-A1 (POS_A1_01)
        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_A_SOLO" to 2)
        )
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(1, state.totalPassos)
        val passo = state.passos[0]
        assertEquals("POS_A1_01", passo.posicao.id)
        assertEquals("PRAT-A1", passo.prateleira.codigo)
        assertEquals(2, passo.itens[0].quantidadeDesejada)
    }

    @Test
    fun testFracionamentoSplitEsvaziaMaiorEstoquePrimeiro12Unidades() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        // Item A tem 3 na PRAT-1 (POS_A1_01) e 10 na PRAT-2 (POS_A2_01)
        val itemA = Item(
            id = "ITEM_A_SPLIT",
            sku = "SKU-ITEM-A-SPLIT",
            nome = "Item A Split",
            categoria = "Geral",
            unidade = "UN",
            estoqueMinimo = 1,
            emSeparacao = false
        )
        repository.salvarItem(itemA)
        repository.atualizarEstoquePosicao("ITEM_A_SPLIT", "POS_A1_01", 3)
        repository.atualizarEstoquePosicao("ITEM_A_SPLIT", "POS_A2_01", 10)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        // Operador solicita 12 unidades (3 na PRAT-1 e 10 na PRAT-2)
        // Deve gerar 2 passos: 10 na PRAT-2 (maior estoque esvaziado primeiro) e 2 na PRAT-1 (resto)
        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_A_SPLIT" to 12)
        )
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(2, state.totalPassos)

        val passoPrat2 = state.passos.find { it.posicao.id == "POS_A2_01" }
        assertNotNull(passoPrat2)
        assertEquals(10, passoPrat2.itens[0].quantidadeDesejada)

        val passoPrat1 = state.passos.find { it.posicao.id == "POS_A1_01" }
        assertNotNull(passoPrat1)
        assertEquals(2, passoPrat1.itens[0].quantidadeDesejada)
    }

    @Test
    fun testRegraFracionamento8UnidadesEncontraPosicaoUnicaQueCaiba() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        // Item A tem 3 na PRAT-1 (POS_A1_01) e 10 na PRAT-2 (POS_A2_01)
        val itemA = Item(
            id = "ITEM_A_8UN",
            sku = "SKU-ITEM-A-8UN",
            nome = "Item A 8 Unidades",
            categoria = "Geral",
            unidade = "UN",
            estoqueMinimo = 1,
            emSeparacao = false
        )
        repository.salvarItem(itemA)
        repository.atualizarEstoquePosicao("ITEM_A_8UN", "POS_A1_01", 3)
        repository.atualizarEstoquePosicao("ITEM_A_8UN", "POS_A2_01", 10)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        // Operador solicita 8 unidades: cabe inteiramente na PRAT-2 (POS_A2_01 tem 10 >= 8)
        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_A_8UN" to 8)
        )
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(1, state.totalPassos)
        val passo = state.passos[0]
        assertEquals("POS_A2_01", passo.posicao.id)
        assertEquals("PRAT-A2", passo.prateleira.codigo)
        assertEquals(8, passo.itens[0].quantidadeDesejada)
    }

    @Test
    fun testSeparacaoFlexivelPickingDinamicoAceitaOutraPosicaoComSaldoSuficienteEDebitaDela() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        // ITEM_1 tem 3 unidades na POS_A1_01 (tag 01) e 10 unidades na POS_A2_01 (tag 03)
        repository.atualizarEstoquePosicao("ITEM_1", "POS_A1_01", 3)
        repository.atualizarEstoquePosicao("ITEM_1", "POS_A2_01", 10)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        // Solicita 2 unidades -> Best Fit sugere inicialmente POS_A1_01 (menor saldo >= 2)
        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_1" to 2)
        )
        testScheduler.runCurrent()

        assertEquals("POS_A1_01", viewModel.uiState.value.passoAtual?.posicao?.id)

        // Operador no chão de fábrica bipa a tag da POS_A2_01 ("04:A1:2B:3C:4D:5E:03"),
        // que TAMBÉM possui o ITEM_1 com saldo suficiente (10 >= 2)!
        viewModel.processarLeituraNfc(
            scannedUid = "04:A1:2B:3C:4D:5E:03",
            allTags = tags,
            allPosicoes = posicoes,
            allPrateleiras = prateleiras,
            allEstantes = estantes
        )
        testScheduler.runCurrent()

        val stateAfterScan = viewModel.uiState.value
        assertEquals(EtapaSeparacao.CONFIRMAR_QUANTIDADE, stateAfterScan.etapa)
        assertTrue(stateAfterScan.isVisualFlashingGreen)
        assertFalse(stateAfterScan.isVisualFlashingRed)
        assertEquals(null, stateAfterScan.showWrongTagModal)
        assertEquals("POS_A2_01", stateAfterScan.passoAtual?.posicao?.id)

        // Finaliza a retirada e verifica que o saldo foi debitado da POS_A2_01 (10 - 2 = 8)
        // enquanto a POS_A1_01 permaneceu intacta (3 unidades)!
        viewModel.finalizarRetiradaPassoAtual(mapOf("ITEM_1" to 2))
        testScheduler.runCurrent()

        assertEquals(EtapaSeparacao.CONCLUIDO, viewModel.uiState.value.etapa)
        assertEquals(8, repository.getEstoque("ITEM_1", "POS_A2_01")?.quantidade)
        assertEquals(3, repository.getEstoque("ITEM_1", "POS_A1_01")?.quantidade)

        val movSaida = repository.getMovimentacoes().first().find {
            it.tipo == TipoMovimentacao.SAIDA && it.itemId == "ITEM_1"
        }
        assertNotNull(movSaida)
        assertEquals("POS_A2_01", movSaida.posicaoId)
        assertEquals(2, movSaida.quantidade)
    }

    @Test
    fun testExecucaoRotaOtimizadaAcendeEApagaUmLedPorVezSequencialmente() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)
        mqttService.clearSentCommands()

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        // Inicia coleta com 2 paradas: ITEM_1 (POS_A1_01) e ITEM_3 (POS_A2_05)
        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf(
                "ITEM_1" to 2,
                "ITEM_3" to 3
            )
        )
        testScheduler.runCurrent()

        // Passo 1 de 2: apenas o LED de POS_A1_01 deve estar aceso
        assertEquals(2, viewModel.uiState.value.totalPassos)
        assertEquals(0, viewModel.uiState.value.passoAtualIndex)
        assertEquals(1, mqttService.sentCommands.size)
        assertTrue(mqttService.sentCommands[0].contains("\"acao\":\"ACENDER\""))
        assertTrue(mqttService.sentCommands[0].contains("\"posicao_led_id\":\"POS_A1_01\""))

        // Operador bipa NFC da POS_A1_01 e confirma retirada do Passo 1
        viewModel.processarLeituraNfc(
            scannedUid = "04:A1:2B:3C:4D:5E:01",
            allTags = tags,
            allPosicoes = posicoes,
            allPrateleiras = prateleiras,
            allEstantes = estantes
        )
        viewModel.finalizarRetiradaPassoAtual(mapOf("ITEM_1" to 2))
        testScheduler.runCurrent()

        // Deve ter apagado o LED de POS_A1_01 e acendido o LED de POS_A2_05 (Passo 2 de 2)
        assertEquals(EtapaSeparacao.VALIDACAO_NFC, viewModel.uiState.value.etapa)
        assertEquals(1, viewModel.uiState.value.passoAtualIndex)
        assertEquals(3, mqttService.sentCommands.size)
        assertTrue(mqttService.sentCommands[1].contains("\"acao\":\"APAGAR\""))
        assertTrue(mqttService.sentCommands[1].contains("\"posicao_led_id\":\"POS_A1_01\""))
        assertTrue(mqttService.sentCommands[2].contains("\"acao\":\"ACENDER\""))
        assertTrue(mqttService.sentCommands[2].contains("\"posicao_led_id\":\"POS_A2_05\""))

        // Operador bipa NFC da POS_A2_05 e confirma retirada do Passo 2
        viewModel.processarLeituraNfc(
            scannedUid = "04:A1:2B:3C:4D:5E:04",
            allTags = tags,
            allPosicoes = posicoes,
            allPrateleiras = prateleiras,
            allEstantes = estantes
        )
        viewModel.finalizarRetiradaPassoAtual(mapOf("ITEM_3" to 3))
        testScheduler.runCurrent()

        // Deve ter apagado o LED de POS_A2_05 e concluído a rota
        assertEquals(EtapaSeparacao.CONCLUIDO, viewModel.uiState.value.etapa)
        assertEquals(4, mqttService.sentCommands.size)
        assertTrue(mqttService.sentCommands[3].contains("\"acao\":\"APAGAR\""))
        assertTrue(mqttService.sentCommands[3].contains("\"posicao_led_id\":\"POS_A2_05\""))
    }

    @Test
    fun testTimeoutAck5SegundosModoManualPreservaPokaYokeERegistraMovimentacaoModoManual() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)
        mqttService.simulateAckTimeout = true

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_1" to 2)
        )
        testScheduler.runCurrent()

        // Inicialmente está aguardando o ACK de 5 segundos
        assertTrue(viewModel.uiState.value.isAguardandoAck)
        assertFalse(viewModel.uiState.value.showHardwareTimeoutModal)

        // Avança 5 segundos: deve disparar o modal de timeout de hardware
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isAguardandoAck)
        assertTrue(viewModel.uiState.value.showHardwareTimeoutModal)

        // Operador escolhe [Continuar em Modo Manual] (Cenário 8 / Seção 5.2)
        viewModel.continuarEmModoManual()
        assertTrue(viewModel.uiState.value.isModoManual)
        assertFalse(viewModel.uiState.value.showHardwareTimeoutModal)

        // Em Modo Manual, o Poka-Yoke NFC continua ativo: bipar tag errada bloqueia
        viewModel.processarLeituraNfc(
            scannedUid = "04:A1:2B:3C:4D:5E:02",
            allTags = tags,
            allPosicoes = posicoes,
            allPrateleiras = prateleiras,
            allEstantes = estantes
        )
        assertTrue(viewModel.uiState.value.isVisualFlashingRed)
        assertNotNull(viewModel.uiState.value.showWrongTagModal)
        assertEquals(EtapaSeparacao.VALIDACAO_NFC, viewModel.uiState.value.etapa)

        viewModel.dismissWrongTagModal()

        // Bipar tag correta avança para confirmação de quantidade
        viewModel.processarLeituraNfc(
            scannedUid = "04:A1:2B:3C:4D:5E:01",
            allTags = tags,
            allPosicoes = posicoes,
            allPrateleiras = prateleiras,
            allEstantes = estantes
        )
        assertEquals(EtapaSeparacao.CONFIRMAR_QUANTIDADE, viewModel.uiState.value.etapa)

        // Finaliza a retirada e verifica que Movimentacao(SAIDA) registrou modoManual = true
        viewModel.finalizarRetiradaPassoAtual(mapOf("ITEM_1" to 2))
        testScheduler.runCurrent()

        assertEquals(EtapaSeparacao.CONCLUIDO, viewModel.uiState.value.etapa)
        val movSaidaManual = repository.getMovimentacoes().first().find {
            it.tipo == TipoMovimentacao.SAIDA && it.itemId == "ITEM_1"
        }
        assertNotNull(movSaidaManual)
        assertTrue(movSaidaManual.modoManual, "A movimentação de saída deve registrar modoManual = true")
    }
}
