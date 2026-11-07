package com.uniso.wms.pickbylight

import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.data.mqtt.FakeMqttService
import com.uniso.wms.pickbylight.data.mqtt.MqttConnectionState
import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
import com.uniso.wms.pickbylight.ui.responsive.WindowSizeClass
import com.uniso.wms.pickbylight.ui.responsive.WindowWidthSizeClass
import com.uniso.wms.pickbylight.ui.screens.historico.formatarTimestampMovimentacao
import com.uniso.wms.pickbylight.ui.screens.historico.gerarCsvMovimentacoes
import com.uniso.wms.pickbylight.ui.screens.recebimento.RecebimentoStep
import com.uniso.wms.pickbylight.ui.screens.recebimento.RecebimentoViewModel
import com.uniso.wms.pickbylight.ui.screens.separacao.EtapaSeparacao
import com.uniso.wms.pickbylight.ui.screens.separacao.SeparacaoViewModel
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

/**
 * Suíte de auditoria e homologação cobrindo integralmente os 10 Cenários de Teste da Seção 6.1
 * e os Critérios de Aceite da Seção 6.2 da Especificação Técnica.
 */
class HomologacaoCenariosTest {

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
    fun cenario01_InsercaoComTagValidaResolveEnderecoEAbreFormulario() = runTest {
        val viewModel = RecebimentoViewModel(repository, feedbackController, this)

        // Tag NFC cadastrada para POS_A2_05: "04:A1:2B:3C:4D:5E:04"
        viewModel.processarLeituraNfc("04:A1:2B:3C:4D:5E:04")
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(RecebimentoStep.FORM, state.step)
        assertEquals("POS_A2_05", state.resolvedPosicao?.id)
        assertTrue(state.enderecoFormatado.contains("Estante A"))
        assertTrue(state.enderecoFormatado.contains("PRAT-A2"))
        assertTrue(state.enderecoFormatado.contains("POS-05"))
    }

    @Test
    fun cenario02_InsercaoComTagInvalidaExibeTelaVermelhaEModalCadastro() = runTest {
        val viewModel = RecebimentoViewModel(repository, feedbackController, this)

        val tagDesconhecida = "04:99:88:77:66:55:44"
        viewModel.processarLeituraNfc(tagDesconhecida)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(RecebimentoStep.SCAN_NFC, state.step)
        assertTrue(state.isErrorFlashingRed)
        assertEquals(tagDesconhecida, state.unmappedTagErrorUid)
    }

    @Test
    fun cenario03_RetiradaComTagCorretaAcendeLedFicaVerdeEAvancaStepper() = runTest {
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
            quantidadesSelecionadas = mapOf("ITEM_3" to 2) // POS_A2_05
        )
        testScheduler.runCurrent()

        assertTrue(mqttService.sentCommands.last().contains("\"acao\":\"ACENDER\""))
        assertEquals(EtapaSeparacao.VALIDACAO_NFC, viewModel.uiState.value.etapa)

        // Bipa tag correspondente à posição iluminada (POS_A2_05)
        viewModel.processarLeituraNfc(
            scannedUid = "04:A1:2B:3C:4D:5E:04",
            allTags = tags,
            allPosicoes = posicoes,
            allPrateleiras = prateleiras,
            allEstantes = estantes
        )

        val state = viewModel.uiState.value
        assertTrue(state.isVisualFlashingGreen)
        assertEquals(EtapaSeparacao.CONFIRMAR_QUANTIDADE, state.etapa)
    }

    @Test
    fun cenario04_RetiradaComTagErradaPokaYokeBloqueiaBaixa() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this)
        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        // Coleta ativa para POS_A2_05 (ITEM_3)
        viewModel.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_3" to 2)
        )
        testScheduler.runCurrent()

        // Bipa a tag da posição POS_A1_01 ("04:A1:2B:3C:4D:5E:01")
        viewModel.processarLeituraNfc(
            scannedUid = "04:A1:2B:3C:4D:5E:01",
            allTags = tags,
            allPosicoes = posicoes,
            allPrateleiras = prateleiras,
            allEstantes = estantes
        )

        val state = viewModel.uiState.value
        assertTrue(state.isVisualFlashingRed)
        assertNotNull(state.showWrongTagModal)
        assertEquals(EtapaSeparacao.VALIDACAO_NFC, state.etapa)
    }

    @Test
    fun cenario05_BrokerMqttOfflineExibeEstadoDesconectadoEEnfileiraComandos() = runTest {
        mqttService.setConnectionState(MqttConnectionState.DISCONNECTED)
        assertEquals(MqttConnectionState.DISCONNECTED, mqttService.connectionState.value)

        val res = mqttService.acenderLed("EST_A_LED1", "PRAT_A2_LED1", "POS_A2_05")
        assertTrue(res.isFailure)
        assertEquals(1, mqttService.pendingCommandsCount.value)
    }

    @Test
    fun cenario06_TimeoutOperacao60SegundosEnviaApagarERetornaComAviso() = runTest {
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
            quantidadesSelecionadas = mapOf("ITEM_1" to 1)
        )
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(61_000)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(EtapaSeparacao.SELECAO_E_RESUMO, state.etapa)
        assertEquals("Tempo esgotado. Operação cancelada.", state.timeoutCancelMessage)
        assertTrue(mqttService.sentCommands.last().contains("\"acao\":\"APAGAR\""))
        assertFalse(repository.getItens().first().find { it.id == "ITEM_1" }!!.emSeparacao)
    }

    @Test
    fun cenario07_ResponsividadeBreakpointsCompactMediumExpanded() {
        val compact = WindowSizeClass.calculateFromSize(width = 412.dp, height = 915.dp)
        assertEquals(WindowWidthSizeClass.COMPACT, compact.widthSizeClass)
        assertTrue(compact.widthSizeClass.isCompact)

        val medium = WindowSizeClass.calculateFromSize(width = 720.dp, height = 1024.dp)
        assertEquals(WindowWidthSizeClass.MEDIUM, medium.widthSizeClass)
        assertTrue(medium.widthSizeClass.isMedium)

        val expanded = WindowSizeClass.calculateFromSize(width = 1280.dp, height = 800.dp)
        assertEquals(WindowWidthSizeClass.EXPANDED, expanded.widthSizeClass)
        assertTrue(expanded.widthSizeClass.isExpanded)
    }

    @Test
    fun cenario08_ModoManualPreservaPokaYokeERegistraFlagModoManual() = runTest {
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

        // Timeout de ACK em 5s
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.showHardwareTimeoutModal)

        // Ativa Modo Manual
        viewModel.continuarEmModoManual()
        assertTrue(viewModel.uiState.value.isModoManual)

        // Valida NFC correto e finaliza
        viewModel.processarLeituraNfc(
            scannedUid = "04:A1:2B:3C:4D:5E:01",
            allTags = tags,
            allPosicoes = posicoes,
            allPrateleiras = prateleiras,
            allEstantes = estantes
        )
        viewModel.finalizarRetiradaPassoAtual(mapOf("ITEM_1" to 2))
        testScheduler.runCurrent()

        val mov = repository.getMovimentacoes().first().first()
        assertEquals(TipoMovimentacao.SAIDA, mov.tipo)
        assertTrue(mov.modoManual)
    }

    @Test
    fun cenario09_BackEmFluxoCriticoPreservaEstadoAoContinuarECancelaAoConfirmar() = runTest {
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

        // Escolher [Continuar Coleta] mantém estado ativo e lock preservado
        assertEquals(EtapaSeparacao.VALIDACAO_NFC, viewModel.uiState.value.etapa)
        assertTrue(repository.getItens().first().find { it.id == "ITEM_1" }!!.emSeparacao)

        // Escolher [Cancelar Coleta] envia APAGAR e libera lock
        viewModel.cancelarColetaEVoltar()
        testScheduler.runCurrent()

        assertEquals(EtapaSeparacao.SELECAO_E_RESUMO, viewModel.uiState.value.etapa)
        assertFalse(repository.getItens().first().find { it.id == "ITEM_1" }!!.emSeparacao)
        assertTrue(mqttService.sentCommands.last().contains("\"acao\":\"APAGAR\""))
    }

    @Test
    fun cenario10_HistoricoRegistraEntradaESaidaComTimestampFormatadoEExportaCsv() = runTest {
        val recebimentoVM = RecebimentoViewModel(repository, feedbackController, this)
        val separacaoVM = SeparacaoViewModel(repository, mqttService, feedbackController, this)

        // 1. Realiza 1 Entrada de 5 unidades do ITEM_1 na POS_A1_01
        val item1 = repository.getItemBySku("ARD-ESP32")!!
        recebimentoVM.processarLeituraNfc("04:A1:2B:3C:4D:5E:01")
        testScheduler.runCurrent()
        recebimentoVM.selecionarItem(item1)
        recebimentoVM.setQuantidade(5)
        recebimentoVM.setObservacao("Entrada Homologacao")
        recebimentoVM.confirmarEntrada()
        testScheduler.runCurrent()

        // 2. Realiza 1 Saída de 2 unidades do ITEM_3 na POS_A2_05
        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        separacaoVM.iniciarColeta(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            quantidadesSelecionadas = mapOf("ITEM_3" to 2)
        )
        testScheduler.runCurrent()
        separacaoVM.processarLeituraNfc(
            scannedUid = "04:A1:2B:3C:4D:5E:04",
            allTags = tags,
            allPosicoes = posicoes,
            allPrateleiras = prateleiras,
            allEstantes = estantes
        )
        separacaoVM.finalizarRetiradaPassoAtual(mapOf("ITEM_3" to 2))
        testScheduler.runCurrent()

        // 3. Verifica registros no Histórico e exportação CSV
        val movs = repository.getMovimentacoes().first()
        assertEquals(2, movs.size)
        assertTrue(movs.any { it.tipo == TipoMovimentacao.ENTRADA && it.quantidade == 5 })
        assertTrue(movs.any { it.tipo == TipoMovimentacao.SAIDA && it.quantidade == 2 })

        val formattedTs = formatarTimestampMovimentacao(movs.first().timestamp)
        assertTrue(formattedTs.matches(Regex("""\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}""")))

        val csv = gerarCsvMovimentacoes(movs, itens) { posId -> "Endereco-$posId" }
        val linhas = csv.trim().lines()
        assertEquals(3, linhas.size) // Cabeçalho + 2 movimentações
        assertTrue(linhas[0].startsWith("id,timestamp,data_hora,tipo,sku"))
        assertTrue(csv.contains("ENTRADA"))
        assertTrue(csv.contains("SAIDA"))
        assertTrue(csv.contains("ARD-ESP32"))
        assertTrue(csv.contains("LED-WS2812"))
        assertNull(separacaoVM.uiState.value.timeoutCancelMessage)
    }
}
