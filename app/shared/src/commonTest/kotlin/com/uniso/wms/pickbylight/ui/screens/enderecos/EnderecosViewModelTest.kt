package com.uniso.wms.pickbylight.ui.screens.enderecos

import com.uniso.wms.pickbylight.data.mqtt.FakeMqttService
import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.nfc.NfcController
import com.uniso.wms.pickbylight.nfc.NfcManager
import com.uniso.wms.pickbylight.util.FeedbackController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnderecosViewModelTest {

    private lateinit var repository: InMemoryWmsRepository
    private lateinit var mqttService: FakeMqttService
    private lateinit var feedbackController: FeedbackController
    private lateinit var viewModel: EnderecosViewModel

    @BeforeTest
    fun setup() = runTest {
        repository = InMemoryWmsRepository()
        repository.resetarDadosDemo()
        mqttService = FakeMqttService()
        feedbackController = FeedbackController()
        viewModel = EnderecosViewModel(repository, mqttService, feedbackController)
    }

    @Test
    fun testSeedInicialEContadoresDoUiState() = runTest {
        // Ao inicializar o seed: 8 posições, 6 vinculadas e 2 pendentes
        val state = viewModel.uiState.first { it is EnderecosUiState.Success } as EnderecosUiState.Success

        assertEquals(8, state.totalPosicoes)
        assertEquals(6, state.posicoesVinculadasCount)
        assertEquals(2, state.posicoesPendentesCount)
        assertEquals(2, state.estantes.size)
    }

    @Test
    fun testFiltroNfcChips() = runTest {
        // 1. Filtro VINCULADO
        viewModel.setFiltroNfc(FiltroNfc.VINCULADO)
        val stateVinculado = viewModel.uiState.first {
            it is EnderecosUiState.Success && it.filtroNfc == FiltroNfc.VINCULADO
        } as EnderecosUiState.Success

        val todasPosicoesVinculadas = stateVinculado.estantes
            .flatMap { it.prateleiras }
            .flatMap { it.posicoes }

        assertTrue(todasPosicoesVinculadas.isNotEmpty())
        assertTrue(todasPosicoesVinculadas.all { it.isVinculado && it.tagNfc != null })

        // 2. Filtro PENDENTE
        viewModel.setFiltroNfc(FiltroNfc.PENDENTE)
        val statePendente = viewModel.uiState.first {
            it is EnderecosUiState.Success && it.filtroNfc == FiltroNfc.PENDENTE
        } as EnderecosUiState.Success

        val todasPosicoesPendentes = statePendente.estantes
            .flatMap { it.prateleiras }
            .flatMap { it.posicoes }

        assertTrue(todasPosicoesPendentes.isNotEmpty())
        assertTrue(todasPosicoesPendentes.all { !it.isVinculado && it.tagNfc == null })
    }

    @Test
    fun testBuscaPorUidOuCodigo() = runTest {
        // Busca pela tag específica do seed "04:A1:2B:3C:4D:5E:01"
        viewModel.setSearchQuery("04:A1:2B:3C:4D:5E:01")
        val state = viewModel.uiState.first {
            it is EnderecosUiState.Success && it.searchQuery == "04:A1:2B:3C:4D:5E:01"
        } as EnderecosUiState.Success

        val posicoesFiltradas = state.estantes
            .flatMap { it.prateleiras }
            .flatMap { it.posicoes }

        assertEquals(1, posicoesFiltradas.size)
        assertEquals("04:A1:2B:3C:4D:5E:01", posicoesFiltradas.first().tagNfc?.uid)
    }

    @Test
    fun testValidacaoRegexLimitesLap() = runTest {
        // Formatos válidos (Seção 2.2 e 3.2.3)
        val resValidoCm = viewModel.inserirPosicao("PRAT_A1", "POS_TESTE_1", "30x20x40cm", "LED_T1")
        assertTrue(resValidoCm.isSuccess)

        val resValidoMm = viewModel.inserirPosicao("PRAT_A1", "POS_TESTE_2", "150x80x200mm", "LED_T2")
        assertTrue(resValidoMm.isSuccess)

        // Formatos inválidos (sem dimensão ou sem unidade)
        val resSemUnidade = viewModel.inserirPosicao("PRAT_A1", "POS_TESTE_3", "30x20x40", "LED_T3")
        assertFalse(resSemUnidade.isSuccess)

        val resSemProfundidade = viewModel.inserirPosicao("PRAT_A1", "POS_TESTE_4", "30x20cm", "LED_T4")
        assertFalse(resSemProfundidade.isSuccess)
    }

    @Test
    fun testVinculacaoNfcUnicidadeEAtômica() = runTest {
        // Posição POS_B2_01 é pendente de vinculação no seed
        val posPendente = repository.getPosicaoById("POS_B2_01")
        assertNotNull(posPendente)

        // 1. Tentar vincular uma tag já vinculada a outra posição (POS_A1_01 usa "04:A1:2B:3C:4D:5E:01")
        val resDuplicado = viewModel.vincularTag("POS_B2_01", "04:A1:2B:3C:4D:5E:01")
        assertFalse(resDuplicado.isSuccess)
        assertTrue(resDuplicado.exceptionOrNull()?.message?.contains("Tag já cadastrada em") == true)

        // 2. Vincular uma tag nova e livre
        val resNovo = viewModel.vincularTag("POS_B2_01", "04:AA:BB:CC:DD:EE:01")
        assertTrue(resNovo.isSuccess)

        // 3. Confirmar que gravou exclusivamente na tabela TagNFC
        val tagGravada = repository.getTagByUid("04:AA:BB:CC:DD:EE:01")
        assertNotNull(tagGravada)
        assertEquals("POS_B2_01", tagGravada.posicaoId)
    }

    @Test
    fun testNfcControllerNormalizacaoEDebounce() {
        NfcController.resetDebounce()

        // 1. Normalização
        assertEquals("04:A1:2B:3C:4D:5E:01", NfcController.normalizeUid("04-a1-2b-3c-4d-5e-01"))
        assertEquals("04:A1:2B:3C:4D:5E:01", NfcController.normalizeUid("04a12b3c4d5e01"))
        assertEquals("04:A1:2B:3C:4D:5E:01", NfcController.normalizeUid(" 04:A1:2B:3C:4D:5E:01 "))

        // 2. Validação
        assertTrue(NfcController.isValidUid("04:A1:2B:3C:4D:5E:01"))
        assertFalse(NfcController.isValidUid("XYZ"))
        assertFalse(NfcController.isValidUid("04:A1:2B")) // muito curto

        // 3. Debounce: primeira leitura passa, segunda imediata (<2s) é rejeitada
        assertTrue(NfcController.checkDebounce("04:A1:2B:3C:4D:5E:01", 2000L))
        assertFalse(NfcController.checkDebounce("04:A1:2B:3C:4D:5E:01", 2000L))

        // Tag diferente é aceita imediatamente
        assertTrue(NfcController.checkDebounce("04:A1:2B:3C:4D:5E:02", 2000L))
    }
}
