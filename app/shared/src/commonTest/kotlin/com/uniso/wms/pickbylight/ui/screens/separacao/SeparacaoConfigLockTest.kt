package com.uniso.wms.pickbylight.ui.screens.separacao

import androidx.lifecycle.ViewModelStore
import com.uniso.wms.pickbylight.data.esp.ConfigLock
import com.uniso.wms.pickbylight.data.esp.ConfigLockResult
import com.uniso.wms.pickbylight.data.mqtt.FakeMqttService
import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.domain.model.TarefaSeparacao
import com.uniso.wms.pickbylight.util.FeedbackController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Etapa 5a (h, i): testes do [ConfigLock] bidirecional em [SeparacaoViewModel] e liberação em todas as 7 saídas. */
@OptIn(ExperimentalCoroutinesApi::class)
class SeparacaoConfigLockTest {

    private lateinit var repository: InMemoryWmsRepository
    private lateinit var mqttService: FakeMqttService
    private lateinit var feedbackController: FeedbackController
    private lateinit var lock: ConfigLock

    @BeforeTest
    fun setup() = runTest {
        repository = InMemoryWmsRepository()
        repository.resetarDadosDemo()
        mqttService = FakeMqttService()
        feedbackController = FeedbackController()
        lock = ConfigLock()
    }

    @Test
    fun iniciarColetaBlockedWhenEspConfigInProgressShowsErrorWithoutLockingItemOrLightingLed() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this, lock)
        assertIs<ConfigLockResult.Acquired>(lock.tryStartConfig("esp-a1b2c3"))

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
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(EtapaSeparacao.SELECAO_E_RESUMO, state.etapa)
        assertEquals(
            "Configuração de ESP em andamento. Aguarde para iniciar a coleta.",
            state.showConcurrencyLockError
        )
        assertFalse(repository.getItens().first().find { it.id == "ITEM_1" }!!.emSeparacao)
        assertTrue(mqttService.sentCommands.isEmpty())
    }

    @Test
    fun earlyReturnWhenItemAlreadyInSeparacaoReleasesConfigLock() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this, lock)
        repository.setEmSeparacao("ITEM_1", true)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        viewModel.iniciarColetaComTarefas(
            itensDisponiveis = itens,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tags,
            tarefas = listOf(TarefaSeparacao("ITEM_1", "POS_A1_01", 1))
        )
        runCurrent()

        assertFalse(lock.state.value.isColetaActive, "early return por item emSeparacao deve liberar ConfigLock")
        assertIs<ConfigLockResult.Acquired>(lock.tryStartConfig("esp-a1b2c3"))
        lock.endConfig("esp-a1b2c3")
    }

    @Test
    fun earlyReturnWhenTasksHaveNoValidLocationReleasesConfigLock() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this, lock)
        val itens = repository.getItens().first()

        viewModel.iniciarColetaComTarefas(
            itensDisponiveis = itens,
            posicoes = emptyList(),
            prateleiras = emptyList(),
            estantes = emptyList(),
            tagsNfc = emptyList(),
            tarefas = listOf(TarefaSeparacao("ITEM_1", "POS_INEXISTENTE", 1))
        )
        runCurrent()

        assertFalse(lock.state.value.isColetaActive, "early return sem localização válida deve liberar ConfigLock")
    }

    @Test
    fun concludingLastStepReleasesConfigLock() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this, lock)
        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        viewModel.iniciarColeta(itens, posicoes, prateleiras, estantes, tags, mapOf("ITEM_1" to 1))
        runCurrent()
        assertTrue(lock.state.value.isColetaActive)
        assertIs<ConfigLockResult.Blocked>(lock.tryStartConfig("esp-a1b2c3"))

        viewModel.validarManualmenteSemNfc()
        viewModel.finalizarRetiradaPassoAtual(mapOf("ITEM_1" to 1))
        runCurrent()

        assertEquals(EtapaSeparacao.CONCLUIDO, viewModel.uiState.value.etapa)
        assertFalse(lock.state.value.isColetaActive, "conclusão do último passo deve liberar ConfigLock")
    }

    @Test
    fun cancelarColetaEVoltarReleasesConfigLock() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this, lock)
        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        viewModel.iniciarColeta(itens, posicoes, prateleiras, estantes, tags, mapOf("ITEM_1" to 1))
        runCurrent()
        assertTrue(lock.state.value.isColetaActive)

        viewModel.cancelarColetaEVoltar()
        runCurrent()

        assertFalse(lock.state.value.isColetaActive, "cancelarColetaEVoltar deve liberar ConfigLock")
    }

    @Test
    fun cancelarColetaPorTimeoutReleasesConfigLock() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this, lock)
        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        viewModel.iniciarColeta(itens, posicoes, prateleiras, estantes, tags, mapOf("ITEM_1" to 1))
        runCurrent()
        assertTrue(lock.state.value.isColetaActive)

        advanceTimeBy(61_000L)
        runCurrent()

        assertEquals("Tempo esgotado. Operação cancelada.", viewModel.uiState.value.timeoutCancelMessage)
        assertFalse(lock.state.value.isColetaActive, "timeout de 60s do operador deve liberar ConfigLock")
    }

    @Test
    fun resetarParaSelecaoReleasesConfigLock() = runTest {
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, this, lock)
        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        viewModel.iniciarColeta(itens, posicoes, prateleiras, estantes, tags, mapOf("ITEM_1" to 1))
        runCurrent()
        assertTrue(lock.state.value.isColetaActive)

        viewModel.resetarParaSelecao()

        assertFalse(lock.state.value.isColetaActive, "resetarParaSelecao deve liberar ConfigLock")
    }

    @Test
    fun onClearedReleasesConfigLock() = runTest {
        val store = ViewModelStore()
        val viewModel = SeparacaoViewModel(repository, mqttService, feedbackController, backgroundScope, lock)
        store.put("separacao", viewModel)

        val itens = repository.getItens().first()
        val posicoes = repository.getPosicoes().first()
        val prateleiras = repository.getPrateleiras().first()
        val estantes = repository.getEstantes().first()
        val tags = repository.getTagsNFC().first()

        viewModel.iniciarColeta(itens, posicoes, prateleiras, estantes, tags, mapOf("ITEM_1" to 1))
        runCurrent()
        assertTrue(lock.state.value.isColetaActive)

        store.clear()

        assertFalse(lock.state.value.isColetaActive, "ViewModelStore.clear() -> onCleared() deve liberar ConfigLock")
    }
}
