package com.uniso.wms.pickbylight.ui.screens.separacao

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.EstoquePosicao
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.domain.model.Movimentacao
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.model.TagNFC
import com.uniso.wms.pickbylight.domain.model.TarefaSeparacao
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.FeedbackScenario
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Representa a intenção de retirada de um item em uma posição física específica.
 */
data class ItemRetirada(
    val itemId: String,
    val posicaoId: String,
    val quantidade: Int
)

/**
 * Item individual a ser retirado dentro de uma posição de coleta.
 */
data class ItemParaColeta(
    val item: Item,
    val quantidadeDesejada: Int,
    val quantidadeConfirmada: Int = quantidadeDesejada,
    val posicaoOrigemId: String = ""
)

/**
 * Passo de Separação otimizado por agrupamento físico (Seção 3.5 da especificação).
 * Aglutina todos os itens que residem no mesmo endereço físico (posicao.id),
 * permitindo acender o LED apenas uma vez para múltiplos itens.
 */
data class PassoColeta(
    val posicao: Posicao,
    val prateleira: Prateleira,
    val estante: Estante,
    val tagNfc: TagNFC?,
    val itens: List<ItemParaColeta>
)

/**
 * Etapas da tela de Separação Pick-by-Light.
 */
enum class EtapaSeparacao {
    SELECAO_E_RESUMO,     // Etapa 1: Busca e seleção de itens + resumo do pedido (nenhum LED aceso)
    VALIDACAO_NFC,        // Etapa 2: Sequenciamento guiado, LED verde aceso e aguardando validação NFC
    CONFIRMAR_QUANTIDADE, // Etapa 3: Tag validada com sucesso, confirmação das quantidades do passo
    CONCLUIDO             // Etapa 4: Sucesso e resumo final da coleta
}

/**
 * Estado UDF unificado da Separação.
 */
data class SeparacaoUiState(
    val etapa: EtapaSeparacao = EtapaSeparacao.SELECAO_E_RESUMO,
    val passos: List<PassoColeta> = emptyList(),
    val passoAtualIndex: Int = 0,
    val isModoManual: Boolean = false,
    val timeoutOperadorSegundos: Int = 60,
    val isAguardandoAck: Boolean = false,
    val showHardwareTimeoutModal: Boolean = false,
    val showConcurrencyLockError: String? = null,
    val showWrongTagModal: Pair<String, String>? = null,
    val unmappedTagUid: String? = null,
    val timeoutCancelMessage: String? = null,
    val isVisualFlashingGreen: Boolean = false,
    val isVisualFlashingRed: Boolean = false,
    val resumoFinal: List<ItemParaColeta> = emptyList()
) {
    val passoAtual: PassoColeta?
        get() = passos.getOrNull(passoAtualIndex)

    val totalPassos: Int
        get() = passos.size

    val totalItensParaColeta: Int
        get() = passos.sumOf { passo -> passo.itens.sumOf { it.quantidadeDesejada } }

    val progressoFracao: Float
        get() = if (totalPassos > 0) (passoAtualIndex + 1).toFloat() / totalPassos else 0f
}

class SeparacaoViewModel(
    private val repository: WmsRepository,
    private val mqttService: MqttService,
    private val feedbackController: FeedbackController,
    coroutineScope: kotlinx.coroutines.CoroutineScope? = null
) : ViewModel() {

    private val scope: kotlinx.coroutines.CoroutineScope = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow(SeparacaoUiState())
    val uiState: StateFlow<SeparacaoUiState> = _uiState.asStateFlow()

    private var operatorTimerJob: Job? = null
    private var ackTimeoutJob: Job? = null

    /**
     * Algoritmo de Alocação de Estoque Inteligente (Best Fit + Fracionamento + Wave Picking).
     *
     * - Cenário A (Atendimento em uma única prateleira - Best Fit):
     *   Se houver múltiplas posições com saldo suficiente para atender a quantidade pedida N,
     *   escolhe a posição com o MENOR saldo (que ainda seja >= N) para não fragmentar o estoque.
     * - Cenário B (Fracionamento / Split):
     *   Se nenhuma posição única atender, preenche a cota dividindo o pedido a partir da posição
     *   com maior saldo disponível (ex: pedi 12, tenho 3 na PRAT-1 e 10 na PRAT-2 -> aloca 10 na PRAT-2 e 2 na PRAT-1).
     * - Cenário C (Otimização de Rota / Wave Picking):
     *   Se o carrinho de separação já for passar em uma prateleira X por causa de outro item,
     *   e essa prateleira X tiver saldo para atender o item, aloca lá, sobrepondo a regra do "menor saldo".
     * - Ordenação por Endereço Físico: Retorna as tarefas ordenadas por endereço físico
     *   (Estante -> Prateleira -> Posição) para garantir a melhor rota de coleta.
     */
    fun alocarCarrinhoWavePicking(
        quantidadesSelecionadas: Map<String, Int>,
        estoquesPorItem: Map<String, List<EstoquePosicao>>,
        posicoes: List<Posicao>,
        prateleiras: List<Prateleira>,
        estantes: List<Estante>
    ): List<TarefaSeparacao> {
        val itensCarrinho = quantidadesSelecionadas.filter { it.value > 0 }
        if (itensCarrinho.isEmpty()) return emptyList()

        val posicaoMap = posicoes.associateBy { it.id }
        val prateleiraMap = prateleiras.associateBy { it.id }
        val estanteMap = estantes.associateBy { it.id }

        // Comparador de endereço físico: Estante -> Prateleira -> Posição
        val addressComparator = Comparator<String> { posId1, posId2 ->
            val p1 = posicaoMap[posId1]
            val p2 = posicaoMap[posId2]
            val prat1 = prateleiraMap[p1?.prateleiraId]
            val prat2 = prateleiraMap[p2?.prateleiraId]
            val est1 = estanteMap[prat1?.estanteId]
            val est2 = estanteMap[prat2?.estanteId]

            val cEst = (est1?.codigo ?: "").compareTo(est2?.codigo ?: "")
            if (cEst != 0) return@Comparator cEst
            val cPrat = (prat1?.codigo ?: "").compareTo(prat2?.codigo ?: "")
            if (cPrat != 0) return@Comparator cPrat
            (p1?.codigo ?: "").compareTo(p2?.codigo ?: "")
        }

        // Comparador Best Fit: menor saldo suficiente primeiro; desempate por endereço físico
        val bestFitComparator = Comparator<EstoquePosicao> { e1, e2 ->
            val cmpSaldo = e1.quantidade.compareTo(e2.quantidade)
            if (cmpSaldo != 0) cmpSaldo else addressComparator.compare(e1.posicaoId, e2.posicaoId)
        }

        // Ordena itens do carrinho para alocação: itens mais restritos primeiro.
        // 1º Itens com apenas 1 posição suficiente (fixam obrigatoriamente uma prateleira na rota)
        // 2º Itens que exigem fracionamento (suficientes == 0, visitam múltiplas posições)
        // 3º Itens com múltiplas posições suficientes (podem aproveitar a rota ou usar Best Fit)
        data class ItemDemanda(val itemId: String, val quantidadeDesejada: Int)
        val listaDemandas = itensCarrinho.map { ItemDemanda(it.key, it.value) }

        val itensOrdenados = listaDemandas.sortedWith(
            compareBy(
                { demanda ->
                    val estoquesItem = estoquesPorItem[demanda.itemId]?.filter { it.quantidade > 0 } ?: emptyList()
                    val suficientes = estoquesItem.count { it.quantidade >= demanda.quantidadeDesejada }
                    when {
                        suficientes == 1 -> 0
                        suficientes == 0 -> 1
                        else -> suficientes
                    }
                },
                { demanda ->
                    estoquesPorItem[demanda.itemId]?.count { it.quantidade > 0 } ?: 0
                }
            )
        )

        val posicoesVisitadas = mutableSetOf<String>()
        val prateleirasVisitadas = mutableSetOf<String>()
        val tarefasGeradas = mutableListOf<TarefaSeparacao>()

        for (demanda in itensOrdenados) {
            val estoquesItem = (estoquesPorItem[demanda.itemId]?.filter { it.quantidade > 0 } ?: emptyList())
                .sortedWith { e1, e2 -> addressComparator.compare(e1.posicaoId, e2.posicaoId) }

            if (estoquesItem.isEmpty()) continue

            // 1. Verifica se cabe inteiramente em uma única posição (Cenário A ou C)
            val posicoesSuficientes = estoquesItem.filter { it.quantidade >= demanda.quantidadeDesejada }

            if (posicoesSuficientes.isNotEmpty()) {
                // Cenário C (Otimização de Rota / Wave Picking):
                // Prioridade 1: Mesma posição física já visitada por outro item (mesmo LED)
                val jaVisitadaPos = posicoesSuficientes
                    .filter { it.posicaoId in posicoesVisitadas }
                    .minWithOrNull(bestFitComparator)

                // Prioridade 2: Mesma prateleira física já visitada por outro item (sobrepõe menor saldo)
                val jaVisitadaPrat = posicoesSuficientes
                    .filter {
                        val pratId = posicaoMap[it.posicaoId]?.prateleiraId
                        pratId != null && pratId in prateleirasVisitadas
                    }
                    .minWithOrNull(bestFitComparator)

                // Cenário A (Best Fit): Posição com o MENOR saldo que ainda seja >= quantidadeDesejada
                val bestFit = posicoesSuficientes.minWithOrNull(bestFitComparator)!!

                val escolhida = jaVisitadaPos ?: jaVisitadaPrat ?: bestFit

                tarefasGeradas.add(
                    TarefaSeparacao(
                        itemId = demanda.itemId,
                        posicaoId = escolhida.posicaoId,
                        quantidadeAlocada = demanda.quantidadeDesejada
                    )
                )
                posicoesVisitadas.add(escolhida.posicaoId)
                posicaoMap[escolhida.posicaoId]?.prateleiraId?.let { prateleirasVisitadas.add(it) }
            } else {
                // 2. Cenário B (Fracionamento / Split):
                // Nenhuma posição única atende; divide o pedido preenchendo a partir do maior saldo
                val ordenadasParaSplit = estoquesItem.sortedWith(
                    compareByDescending<EstoquePosicao> { it.quantidade }
                        .thenByDescending { it.posicaoId in posicoesVisitadas }
                        .thenByDescending {
                            val pratId = posicaoMap[it.posicaoId]?.prateleiraId
                            pratId != null && pratId in prateleirasVisitadas
                        }
                        .thenComparator { e1, e2 -> addressComparator.compare(e1.posicaoId, e2.posicaoId) }
                )

                var restante = demanda.quantidadeDesejada
                for (est in ordenadasParaSplit) {
                    if (restante <= 0) break
                    val tirar = restante.coerceAtMost(est.quantidade)
                    if (tirar > 0) {
                        tarefasGeradas.add(
                            TarefaSeparacao(
                                itemId = demanda.itemId,
                                posicaoId = est.posicaoId,
                                quantidadeAlocada = tirar
                            )
                        )
                        posicoesVisitadas.add(est.posicaoId)
                        posicaoMap[est.posicaoId]?.prateleiraId?.let { prateleirasVisitadas.add(it) }
                        restante -= tirar
                    }
                }
            }
        }

        // 3. Passo de consolidação de rota (Cenário C entre múltiplos itens flexíveis):
        // Se um item foi alocado sozinho em uma prateleira por Best Fit antes de outro item definir
        // uma prateleira diferente na rota, verifica se ele pode ser consolidado na prateleira do outro item.
        if (itensOrdenados.size > 1) {
            for (demanda in itensOrdenados) {
                val tarefasDoItem = tarefasGeradas.filter { it.itemId == demanda.itemId }
                if (tarefasDoItem.size != 1) continue
                val tarefaAtual = tarefasDoItem.first()
                val pratAtual = posicaoMap[tarefaAtual.posicaoId]?.prateleiraId

                val outrasTarefas = tarefasGeradas.filter { it.itemId != demanda.itemId }
                val jaCompartilhaPrateleira = outrasTarefas.any {
                    posicaoMap[it.posicaoId]?.prateleiraId == pratAtual
                }
                if (jaCompartilhaPrateleira) continue

                val outrasPosicoes = outrasTarefas.map { it.posicaoId }.toSet()
                val outrasPrateleiras = outrasPosicoes.mapNotNull { posicaoMap[it]?.prateleiraId }.toSet()

                val posicoesSuficientes = (estoquesPorItem[demanda.itemId] ?: emptyList())
                    .filter { it.quantidade >= demanda.quantidadeDesejada }

                val candidataPos = posicoesSuficientes
                    .filter { it.posicaoId in outrasPosicoes }
                    .minWithOrNull(bestFitComparator)
                val candidataPrat = posicoesSuficientes
                    .filter {
                        val pId = posicaoMap[it.posicaoId]?.prateleiraId
                        pId != null && pId in outrasPrateleiras
                    }
                    .minWithOrNull(bestFitComparator)

                val substituta = candidataPos ?: candidataPrat
                if (substituta != null && substituta.posicaoId != tarefaAtual.posicaoId) {
                    val idx = tarefasGeradas.indexOf(tarefaAtual)
                    if (idx >= 0) {
                        tarefasGeradas[idx] = tarefaAtual.copy(posicaoId = substituta.posicaoId)
                    }
                }
            }
        }

        // 4. Resultado ordenado por código de endereço (Estante -> Prateleira -> Posição)
        return tarefasGeradas.sortedWith { t1, t2 ->
            addressComparator.compare(t1.posicaoId, t2.posicaoId)
        }
    }

    /**
     * Algoritmo de Alocação de Estoque por Posição para Item Individual (Best Fit e Fracionamento).
     * - Cenário A (Best Fit): Escolhe a posição com o MENOR saldo que ainda seja >= quantidadeDesejada.
     * - Cenário B (Fracionamento): Se nenhuma posição única atender, divide o pedido a partir do maior saldo.
     */
    fun alocarTarefasSeparacao(
        itemId: String,
        quantidadeDesejada: Int,
        estoquesDisponiveis: List<EstoquePosicao>
    ): List<TarefaSeparacao> {
        if (quantidadeDesejada <= 0) return emptyList()
        val estoquesComSaldo = estoquesDisponiveis.filter { it.quantidade > 0 }

        // Cenário A: Posição única com saldo suficiente (Best Fit: menor saldo >= quantidadeDesejada)
        val posicaoSuficiente = estoquesComSaldo
            .filter { it.quantidade >= quantidadeDesejada }
            .minByOrNull { it.quantidade }

        if (posicaoSuficiente != null) {
            return listOf(
                TarefaSeparacao(
                    itemId = itemId,
                    posicaoId = posicaoSuficiente.posicaoId,
                    quantidadeAlocada = quantidadeDesejada
                )
            )
        }

        // Cenário B: Fatiar entre múltiplas posições por ordem decrescente de saldo
        val ordenadasPorSaldo = estoquesComSaldo.sortedByDescending { it.quantidade }
        val tarefas = mutableListOf<TarefaSeparacao>()
        var restante = quantidadeDesejada

        for (est in ordenadasPorSaldo) {
            if (restante <= 0) break
            val tirar = restante.coerceAtMost(est.quantidade)
            if (tirar > 0) {
                tarefas.add(
                    TarefaSeparacao(
                        itemId = itemId,
                        posicaoId = est.posicaoId,
                        quantidadeAlocada = tirar
                    )
                )
                restante -= tirar
            }
        }
        return tarefas
    }

    /**
     * Inicia a coleta a partir das tarefas de separação geradas pelo algoritmo de alocação automática.
     */
    fun iniciarColetaComTarefas(
        itensDisponiveis: List<Item>,
        posicoes: List<Posicao>,
        prateleiras: List<Prateleira>,
        estantes: List<Estante>,
        tagsNfc: List<TagNFC>,
        tarefas: List<TarefaSeparacao>
    ) {
        val tarefasValidas = tarefas.filter { it.quantidadeAlocada > 0 }
        if (tarefasValidas.isEmpty()) return

        // 1. Verificação de Concorrência / Lock Otimista (Seção 5.5)
        val itemIds = tarefasValidas.map { it.itemId }.distinct()
        val itensBloqueados = itemIds.mapNotNull { id ->
            itensDisponiveis.find { it.id == id && it.emSeparacao }
        }
        if (itensBloqueados.isNotEmpty()) {
            val primeiro = itensBloqueados.first()
            _uiState.update {
                it.copy(showConcurrencyLockError = "Item [${primeiro.sku}] está em separação por outro operador.")
            }
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return
        }

        // Mapeia os dados completos de localização de cada tarefa
        val tarefasComLocalizacao = tarefasValidas.mapNotNull { tarefa ->
            val item = itensDisponiveis.find { it.id == tarefa.itemId } ?: return@mapNotNull null
            val pos = posicoes.find { it.id == tarefa.posicaoId } ?: return@mapNotNull null
            val prat = prateleiras.find { it.id == pos.prateleiraId } ?: return@mapNotNull null
            val est = estantes.find { it.id == prat.estanteId } ?: return@mapNotNull null
            val tag = tagsNfc.find { it.posicaoId == pos.id }

            Triple(item, tarefa.quantidadeAlocada, tag) to Triple(pos, prat, est)
        }

        if (tarefasComLocalizacao.isEmpty()) return

        // 2. OTIMIZAÇÃO DE ROTA: Aglutinação (grouping) por posição física e ordenação por endereço
        val passosAgrupados = tarefasComLocalizacao
            .groupBy { it.second.first.id }
            .map { (_, itensDaMesmaPosicao) ->
                val (_, localizacao) = itensDaMesmaPosicao.first()
                val pos = localizacao.first
                val prat = localizacao.second
                val est = localizacao.third
                val tag = itensDaMesmaPosicao.first().first.third

                val itensDoPasso = itensDaMesmaPosicao.map { (dadosItem, loc) ->
                    ItemParaColeta(
                        item = dadosItem.first,
                        quantidadeDesejada = dadosItem.second,
                        quantidadeConfirmada = dadosItem.second,
                        posicaoOrigemId = loc.first.id
                    )
                }

                PassoColeta(
                    posicao = pos,
                    prateleira = prat,
                    estante = est,
                    tagNfc = tag,
                    itens = itensDoPasso
                )
            }
            .sortedWith(
                compareBy(
                    { it.estante.codigo },
                    { it.prateleira.codigo },
                    { it.posicao.codigo }
                )
            )

        // 3. Transita para Etapa 2 imediatamente
        _uiState.update {
            it.copy(
                etapa = EtapaSeparacao.VALIDACAO_NFC,
                passos = passosAgrupados,
                passoAtualIndex = 0,
                isModoManual = false,
                timeoutOperadorSegundos = 60,
                showHardwareTimeoutModal = false,
                showConcurrencyLockError = null,
                showWrongTagModal = null,
                unmappedTagUid = null,
                timeoutCancelMessage = null,
                isVisualFlashingGreen = false,
                isVisualFlashingRed = false,
                resumoFinal = emptyList()
            )
        }

        // 4. Aplica lock otimista no repositório, aciona hardware e inicia timer
        scope.launch {
            itemIds.forEach { itemId ->
                repository.setEmSeparacao(itemId, true)
            }
            acionarHardwarePassoAtual()
            iniciarTimerOperador()
        }
    }

    /**
     * Inicia a coleta aplicando o algoritmo inteligente de alocação de estoque (Wave Picking)
     * a partir dos itens e quantidades totais desejadas pelo operador.
     */
    fun iniciarColeta(
        itensDisponiveis: List<Item>,
        posicoes: List<Posicao>,
        prateleiras: List<Prateleira>,
        estantes: List<Estante>,
        tagsNfc: List<TagNFC>,
        quantidadesSelecionadas: Map<String, Int>
    ) {
        scope.launch {
            val itensValidos = quantidadesSelecionadas.filter { it.value > 0 }.keys
            val estoquesPorItem = itensValidos.associateWith { itemId ->
                repository.getEstoquesByItem(itemId).first()
            }

            val tarefasOtimizadas = alocarCarrinhoWavePicking(
                quantidadesSelecionadas = quantidadesSelecionadas,
                estoquesPorItem = estoquesPorItem,
                posicoes = posicoes,
                prateleiras = prateleiras,
                estantes = estantes
            )

            iniciarColetaComTarefas(
                itensDisponiveis = itensDisponiveis,
                posicoes = posicoes,
                prateleiras = prateleiras,
                estantes = estantes,
                tagsNfc = tagsNfc,
                tarefas = tarefasOtimizadas
            )
        }
    }

    /**
     * Sobrecarga compatível para iniciar coleta a partir de retiradas explícitas.
     */
    fun iniciarColetaComRetiradas(
        itensDisponiveis: List<Item>,
        posicoes: List<Posicao>,
        prateleiras: List<Prateleira>,
        estantes: List<Estante>,
        tagsNfc: List<TagNFC>,
        retiradas: List<ItemRetirada>
    ) {
        iniciarColetaComTarefas(
            itensDisponiveis = itensDisponiveis,
            posicoes = posicoes,
            prateleiras = prateleiras,
            estantes = estantes,
            tagsNfc = tagsNfc,
            tarefas = retiradas.map { TarefaSeparacao(it.itemId, it.posicaoId, it.quantidade) }
        )
    }

    /**
     * Dispara comando MQTT ACENDER com QoS 1 para a posição atual da rota.
     * Inicia o timeout de ACK de 5 segundos (Seção 5.2).
     * Se o ACK for recebido com sucesso, libera automaticamente o Modo Manual caso estivesse ativo.
     */
    fun acionarHardwarePassoAtual(forceRetry: Boolean = false) {
        val passo = _uiState.value.passoAtual ?: return

        if (_uiState.value.isModoManual && !forceRetry) {
            return
        }

        ackTimeoutJob?.cancel()
        ackTimeoutJob = scope.launch {
            _uiState.update { it.copy(isAguardandoAck = true, showHardwareTimeoutModal = false) }

            val result = withTimeoutOrNull(5000L) {
                mqttService.acenderLed(
                    estanteLedId = passo.estante.ledId,
                    prateleiraLedId = passo.prateleira.ledIdPonta,
                    posicaoLedId = passo.posicao.ledId,
                    cor = "VERDE"
                )
            }

            if (result != null && result.isSuccess) {
                // Seção 5.2: Libera o modo manual automaticamente ao receber ACK de comando MQTT bem-sucedido
                _uiState.update {
                    it.copy(
                        isAguardandoAck = false,
                        isModoManual = false,
                        showHardwareTimeoutModal = false
                    )
                }
            } else {
                feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
                _uiState.update {
                    it.copy(
                        isAguardandoAck = false,
                        showHardwareTimeoutModal = true
                    )
                }
            }
        }
    }

    /**
     * Temporizador regressivo visível de 60 segundos do operador (Seção 5.3).
     * Se expirar sem leitura NFC válida, envia APAGAR, libera os locks e retorna à busca
     * exibindo a mensagem "Tempo esgotado. Operação cancelada.".
     */
    private fun iniciarTimerOperador() {
        operatorTimerJob?.cancel()
        operatorTimerJob = scope.launch {
            var segundos = 60
            _uiState.update { it.copy(timeoutOperadorSegundos = segundos) }

            while (segundos > 0) {
                delay(1000)
                segundos--
                _uiState.update { it.copy(timeoutOperadorSegundos = segundos) }
            }

            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            cancelarColetaPorTimeout()
        }
    }

    private fun cancelarColetaPorTimeout() {
        operatorTimerJob?.cancel()
        ackTimeoutJob?.cancel()
        val passo = _uiState.value.passoAtual
        val passosParaLiberar = _uiState.value.passos

        scope.launch {
            if (passo != null) {
                mqttService.apagarLed(
                    estanteLedId = passo.estante.ledId,
                    prateleiraLedId = passo.prateleira.ledIdPonta,
                    posicaoLedId = passo.posicao.ledId
                )
            }

            passosParaLiberar.forEach { p ->
                p.itens.forEach { item ->
                    repository.setEmSeparacao(item.item.id, false)
                }
            }

            _uiState.update {
                SeparacaoUiState(
                    timeoutCancelMessage = "Tempo esgotado. Operação cancelada."
                )
            }
        }
    }

    /**
     * Validação NFC com Separação Flexível / Picking Dinâmico (Seção 3.5.4):
     * - Se a tag bipada corresponder à posição sugerida OU a qualquer outra posição física
     *   que possua o(s) item(ns) do passo atual com saldo suficiente na tabela EstoquePosicao:
     *   aceita a leitura (vibra sucesso), atualiza a posição do passo atual para dar baixa
     *   dessa posição bipada e avança para a confirmação de quantidades.
     * - O erro "Local incorreto" (vermelho) só ocorre se a posição bipada não tiver o item
     *   ou não tiver saldo suficiente (ou se a tag não estiver cadastrada).
     */
    fun processarLeituraNfc(
        scannedUid: String,
        allTags: List<TagNFC>,
        allPosicoes: List<Posicao>,
        allPrateleiras: List<Prateleira>,
        allEstantes: List<Estante>
    ) {
        val currentState = _uiState.value
        val passo = currentState.passoAtual ?: return
        val passoIndex = currentState.passoAtualIndex
        val expectedTag = passo.tagNfc

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Caso 1: Operador bipou exatamente a posição sugerida
            if (expectedTag != null && expectedTag.uid.equals(scannedUid, ignoreCase = true)) {
                operatorTimerJob?.cancel()
                _uiState.update {
                    it.copy(
                        etapa = EtapaSeparacao.CONFIRMAR_QUANTIDADE,
                        isVisualFlashingGreen = true,
                        isVisualFlashingRed = false,
                        showWrongTagModal = null
                    )
                }
                feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                return@launch
            }

            // Caso 2: Verifica se a tag bipada pertence a outra posição que possui o item com saldo suficiente
            val tagLida = allTags.find { it.uid.equals(scannedUid, ignoreCase = true) }
                ?: repository.getTagByUid(scannedUid)
            val posLida = tagLida?.let { t ->
                allPosicoes.find { it.id == t.posicaoId } ?: repository.getPosicaoById(t.posicaoId)
            }
            val pratLida = posLida?.let { p ->
                allPrateleiras.find { it.id == p.prateleiraId } ?: repository.getPrateleiraById(p.prateleiraId)
            }
            val estLida = pratLida?.let { pr ->
                allEstantes.find { it.id == pr.estanteId } ?: repository.getEstanteById(pr.estanteId)
            }

            if (tagLida != null && posLida != null && pratLida != null && estLida != null) {
                val temSaldoSuficiente = passo.itens.all { itemParaColeta ->
                    val saldoNaPosicaoBipada = repository.getEstoque(itemParaColeta.item.id, posLida.id)?.quantidade ?: 0
                    saldoNaPosicaoBipada >= itemParaColeta.quantidadeDesejada
                }

                if (temSaldoSuficiente) {
                    operatorTimerJob?.cancel()

                    // Apaga o LED da posição originalmente sugerida caso tenha mudado de endereço
                    if (posLida.id != passo.posicao.id) {
                        mqttService.apagarLed(
                            estanteLedId = passo.estante.ledId,
                            prateleiraLedId = passo.prateleira.ledIdPonta,
                            posicaoLedId = passo.posicao.ledId
                        )
                    }

                    // Atualiza o passo atual para apontar para a posição efetivamente bipada pelo operador
                    val passoAtualizado = passo.copy(
                        posicao = posLida,
                        prateleira = pratLida,
                        estante = estLida,
                        tagNfc = tagLida,
                        itens = passo.itens.map { it.copy(posicaoOrigemId = posLida.id) }
                    )

                    _uiState.update { state ->
                        val novosPassos = state.passos.toMutableList().apply {
                            if (passoIndex in indices) {
                                set(passoIndex, passoAtualizado)
                            }
                        }
                        state.copy(
                            passos = novosPassos,
                            etapa = EtapaSeparacao.CONFIRMAR_QUANTIDADE,
                            isVisualFlashingGreen = true,
                            isVisualFlashingRed = false,
                            showWrongTagModal = null
                        )
                    }
                    feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                    return@launch
                }
            }

            // Caso 3: Posição bipada não possui o item ou não possui saldo suficiente (ou tag desconhecida)
            _uiState.update {
                it.copy(
                    isVisualFlashingRed = true,
                    isVisualFlashingGreen = false
                )
            }
            feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)

            val enderecoLido = if (tagLida != null && posLida != null) {
                "${estLida?.nome ?: estLida?.codigo ?: "Estante"} > ${pratLida?.codigo ?: "Prateleira"} > ${posLida.codigo} (Sem saldo suficiente)"
            } else {
                "UID: $scannedUid (Tag não cadastrada)"
            }

            val enderecoEsperado = "${passo.estante.nome} > ${passo.prateleira.codigo} > ${passo.posicao.codigo}"
            _uiState.update {
                it.copy(
                    showWrongTagModal = enderecoLido to enderecoEsperado,
                    unmappedTagUid = if (tagLida == null) scannedUid.trim().uppercase() else null
                )
            }
        }
    }

    /**
     * Confirmação de quantidade e baixa de todos os itens agrupados nesta posição física (Etapa 3).
     * 1. Debita o saldo de estoque específico desta posição física na tabela EstoquePosicao.
     * 2. Registra Movimentacao SAIDA (com flag modoManual).
     * 3. Libera o lock otimista (emSeparacao = false) caso o item não tenha passos pendentes na rota.
     * 4. Envia MQTT APAGAR para o LED da posição física.
     * 5. Avança para a próxima posição da rota ou conclui a coleta.
     */
    fun finalizarRetiradaPassoAtual(quantidadesConfirmadas: Map<String, Int>) {
        val passo = _uiState.value.passoAtual ?: return

        scope.launch {
            val itensFinalizados = mutableListOf<ItemParaColeta>()

            passo.itens.forEach { itemParaColeta ->
                val qtdConfirmada = quantidadesConfirmadas[itemParaColeta.item.id] ?: itemParaColeta.quantidadeDesejada
                val estoqueAtual = repository.getEstoque(itemParaColeta.item.id, passo.posicao.id)?.quantidade ?: 0
                val novoEstoque = (estoqueAtual - qtdConfirmada).coerceAtLeast(0)

                repository.atualizarEstoquePosicao(itemParaColeta.item.id, passo.posicao.id, novoEstoque)

                repository.registrarMovimentacao(
                    Movimentacao(
                        tipo = TipoMovimentacao.SAIDA,
                        itemId = itemParaColeta.item.id,
                        posicaoId = passo.posicao.id,
                        quantidade = qtdConfirmada,
                        modoManual = _uiState.value.isModoManual,
                        observacao = "Separação Pick-by-Light (Posição ${passo.posicao.codigo})"
                    )
                )

                // Verifica se este item ainda tem paradas futuras na rota
                val passosRestantes = _uiState.value.passos.drop(_uiState.value.passoAtualIndex + 1)
                val temMaisPassosDesteItem = passosRestantes.any { p -> p.itens.any { it.item.id == itemParaColeta.item.id } }
                if (!temMaisPassosDesteItem) {
                    repository.setEmSeparacao(itemParaColeta.item.id, false)
                }

                itensFinalizados.add(itemParaColeta.copy(quantidadeConfirmada = qtdConfirmada))
            }

            // Apaga o LED da posição física
            mqttService.apagarLed(
                estanteLedId = passo.estante.ledId,
                prateleiraLedId = passo.prateleira.ledIdPonta,
                posicaoLedId = passo.posicao.ledId
            )

            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)

            val novoResumo = _uiState.value.resumoFinal + itensFinalizados
            val proximoIndex = _uiState.value.passoAtualIndex + 1

            if (proximoIndex < _uiState.value.passos.size) {
                // Próxima parada na rota
                _uiState.update {
                    it.copy(
                        etapa = EtapaSeparacao.VALIDACAO_NFC,
                        passoAtualIndex = proximoIndex,
                        resumoFinal = novoResumo,
                        isVisualFlashingGreen = false,
                        isVisualFlashingRed = false,
                        timeoutOperadorSegundos = 60
                    )
                }
                acionarHardwarePassoAtual()
                iniciarTimerOperador()
            } else {
                // Fim da rota: todas as paradas concluídas!
                _uiState.update {
                    it.copy(
                        etapa = EtapaSeparacao.CONCLUIDO,
                        resumoFinal = novoResumo,
                        isVisualFlashingGreen = false,
                        isVisualFlashingRed = false
                    )
                }
            }
        }
    }

    /**
     * Cancelamento da coleta ativa (Seção 3.7):
     * Apaga LED físico, libera lock otimista de todos os itens e reseta o estado.
     */
    fun cancelarColetaEVoltar(onComplete: () -> Unit = {}) {
        operatorTimerJob?.cancel()
        val passo = _uiState.value.passoAtual

        scope.launch {
            if (passo != null) {
                mqttService.apagarLed(
                    estanteLedId = passo.estante.ledId,
                    prateleiraLedId = passo.prateleira.ledIdPonta,
                    posicaoLedId = passo.posicao.ledId
                )
            }

            _uiState.value.passos.forEach { p ->
                p.itens.forEach { item ->
                    repository.setEmSeparacao(item.item.id, false)
                }
            }

            _uiState.update { SeparacaoUiState() }
            onComplete()
        }
    }

    fun dismissWrongTagModal() {
        _uiState.update {
            it.copy(
                showWrongTagModal = null,
                unmappedTagUid = null,
                isVisualFlashingRed = false
            )
        }
    }

    fun dismissTimeoutCancelMessage() {
        _uiState.update { it.copy(timeoutCancelMessage = null) }
    }

    fun dismissConcurrencyLockError() {
        _uiState.update { it.copy(showConcurrencyLockError = null) }
    }

    fun dismissHardwareTimeoutModal() {
        _uiState.update { it.copy(showHardwareTimeoutModal = false) }
    }

    fun continuarEmModoManual() {
        _uiState.update {
            it.copy(
                isModoManual = true,
                showHardwareTimeoutModal = false
            )
        }
    }

    fun validarManualmenteSemNfc() {
        operatorTimerJob?.cancel()
        _uiState.update {
            it.copy(
                etapa = EtapaSeparacao.CONFIRMAR_QUANTIDADE,
                isVisualFlashingGreen = true,
                isVisualFlashingRed = false,
                showWrongTagModal = null
            )
        }
        feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
    }

    fun resetarParaSelecao() {
        operatorTimerJob?.cancel()
        _uiState.update { SeparacaoUiState() }
    }
}
