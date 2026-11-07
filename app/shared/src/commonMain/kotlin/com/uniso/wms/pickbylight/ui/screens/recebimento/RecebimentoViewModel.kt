package com.uniso.wms.pickbylight.ui.screens.recebimento

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.domain.model.Movimentacao
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.model.TipoMovimentacao
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.FeedbackScenario
import com.uniso.wms.pickbylight.util.generateUUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ModoRecebimento {
    UNICO,
    MULTIPLO
}

enum class RecebimentoStep {
    SCAN_NFC,        // Modo Único: Leitura NFC da posição de destino
    FORM,            // Modo Único: Formulário pós-NFC
    MONTAGEM_LISTA,  // Modo Múltiplo: Montagem da lista de itens antes de bipar posições
    GUIADO_NFC,      // Modo Múltiplo: Passo a passo bipando a Tag NFC para guardar cada item da lista
    SUCCESS          // Resumo de conclusão (Único ou Múltiplo)
}

/**
 * Representa um item adicionado à lista de Recebimento Múltiplo antes da guarda guiada.
 */
data class ItemLoteRecebimento(
    val id: String = generateUUID(),
    val item: Item,
    val quantidade: Int,
    val observacao: String = ""
)

/**
 * Representa o resumo de um item efetivamente guardado em uma posição física.
 */
data class ItemRecebidoResumo(
    val item: Item,
    val quantidade: Int,
    val posicao: Posicao,
    val enderecoFormatado: String,
    val tagUid: String?
)

/**
 * Estado UDF do módulo de Recebimento de Itens (Seção 3.4 + Modos Único e Múltiplo).
 */
data class RecebimentoUiState(
    val modo: ModoRecebimento = ModoRecebimento.UNICO,
    val step: RecebimentoStep = RecebimentoStep.SCAN_NFC,
    val resolvedPosicao: Posicao? = null,
    val resolvedPrateleira: Prateleira? = null,
    val resolvedEstante: Estante? = null,
    val resolvedTagUid: String? = null,
    val enderecoFormatado: String = "",
    val selectedItem: Item? = null,
    val quantidade: Int = 1,
    val observacao: String = "",
    val itemSearchQuery: String = "",
    val itensLote: List<ItemLoteRecebimento> = emptyList(),
    val passoGuiadoIndex: Int = 0,
    val resumoRecebimentoMultiplo: List<ItemRecebidoResumo> = emptyList(),
    val unmappedTagErrorUid: String? = null,
    val isErrorFlashingRed: Boolean = false,
    val formErrorMessage: String? = null,
    val successMessage: String = "",
    val snackbarMessage: String? = null
) {
    val hasDirtyForm: Boolean
        get() = when (step) {
            RecebimentoStep.FORM -> selectedItem != null || quantidade > 1 || observacao.isNotBlank()
            RecebimentoStep.MONTAGEM_LISTA -> itensLote.isNotEmpty() || selectedItem != null || observacao.isNotBlank()
            RecebimentoStep.GUIADO_NFC -> true
            else -> false
        }

    val isFormValid: Boolean
        get() = resolvedPosicao != null && selectedItem != null && quantidade > 0

    val canAddToList: Boolean
        get() = selectedItem != null && quantidade > 0

    val itemGuiadoAtual: ItemLoteRecebimento?
        get() = itensLote.getOrNull(passoGuiadoIndex)

    val totalPassosGuiados: Int
        get() = itensLote.size

    val progressoGuiadoFracao: Float
        get() = if (totalPassosGuiados > 0) (passoGuiadoIndex + 1).toFloat() / totalPassosGuiados else 0f
}

class RecebimentoViewModel(
    private val repository: WmsRepository,
    private val feedbackController: FeedbackController,
    coroutineScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow(RecebimentoUiState())
    val uiState: StateFlow<RecebimentoUiState> = _uiState.asStateFlow()

    private fun buildEnderecoFormatado(est: Estante?, prat: Prateleira?, pos: Posicao?): String {
        if (pos == null) return "Endereço não identificado"
        val estanteStr = est?.nome?.takeIf { it.isNotBlank() } ?: est?.codigo ?: "Estante"
        val pratStr = prat?.codigo?.takeIf { it.isNotBlank() } ?: "Prat"
        return "$estanteStr > $pratStr > ${pos.codigo}"
    }

    /**
     * Alterna entre Recebimento Único e Recebimento Múltiplo.
     */
    fun selecionarModo(novoModo: ModoRecebimento) {
        if (_uiState.value.modo == novoModo) return
        _uiState.update {
            RecebimentoUiState(
                modo = novoModo,
                step = if (novoModo == ModoRecebimento.UNICO) {
                    RecebimentoStep.SCAN_NFC
                } else {
                    RecebimentoStep.MONTAGEM_LISTA
                }
            )
        }
    }

    /**
     * Resolução de Endereço via Leitura NFC:
     * - No Modo Único (SCAN_NFC): resolve o endereço e avança para o formulário.
     * - No Modo Múltiplo Guiado (GUIADO_NFC): confirma a entrada do item atual na posição bipada
     *   e avança automaticamente para o próximo passo (ou tela de sucesso final).
     * - Se tag não cadastrada: vibração longa (600ms) + tela vermelha + modal com atalho [Cadastrar Agora].
     */
    fun processarLeituraNfc(rawUid: String) {
        val uid = rawUid.trim().uppercase()
        if (uid.isBlank()) return

        scope.launch {
            val tag = repository.getTagByUid(uid)
            val pos = tag?.let { repository.getPosicaoById(it.posicaoId) }

            if (tag == null || pos == null) {
                feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
                _uiState.update {
                    it.copy(
                        unmappedTagErrorUid = uid,
                        isErrorFlashingRed = true
                    )
                }
                return@launch
            }

            if (_uiState.value.step == RecebimentoStep.GUIADO_NFC) {
                confirmarPassoGuiadoNaPosicao(pos, tag.uid)
            } else {
                val prat = repository.getPrateleiraById(pos.prateleiraId)
                val est = prat?.let { repository.getEstanteById(it.estanteId) }
                val endereco = buildEnderecoFormatado(est, prat, pos)

                feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                _uiState.update {
                    it.copy(
                        step = RecebimentoStep.FORM,
                        resolvedPosicao = pos,
                        resolvedPrateleira = prat,
                        resolvedEstante = est,
                        resolvedTagUid = tag.uid,
                        enderecoFormatado = endereco,
                        unmappedTagErrorUid = null,
                        isErrorFlashingRed = false,
                        formErrorMessage = null
                    )
                }
            }
        }
    }

    /**
     * Fallback de Seleção Manual de Endereço (Web / Contingência) para Modo Único e Modo Múltiplo Guiado.
     */
    fun selecionarEnderecoManualmente(posicao: Posicao) {
        scope.launch {
            val tag = repository.getTagByPosicaoId(posicao.id)
            if (_uiState.value.step == RecebimentoStep.GUIADO_NFC) {
                confirmarPassoGuiadoNaPosicao(posicao, tag?.uid)
            } else {
                val prat = repository.getPrateleiraById(posicao.prateleiraId)
                val est = prat?.let { repository.getEstanteById(it.estanteId) }
                val endereco = buildEnderecoFormatado(est, prat, posicao)

                _uiState.update {
                    it.copy(
                        step = RecebimentoStep.FORM,
                        resolvedPosicao = posicao,
                        resolvedPrateleira = prat,
                        resolvedEstante = est,
                        resolvedTagUid = tag?.uid,
                        enderecoFormatado = endereco,
                        unmappedTagErrorUid = null,
                        isErrorFlashingRed = false,
                        formErrorMessage = null
                    )
                }
            }
        }
    }

    /**
     * Modo Múltiplo: Adiciona o item e quantidade atuais à lista de recebimento.
     */
    fun adicionarItemAoLote() {
        val state = _uiState.value
        val item = state.selectedItem
        val qtd = state.quantidade

        if (item == null || qtd <= 0) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            _uiState.update {
                it.copy(formErrorMessage = "Selecione um item e informe uma quantidade maior que zero para adicionar à lista.")
            }
            return
        }

        val novoItemLote = ItemLoteRecebimento(
            item = item,
            quantidade = qtd,
            observacao = state.observacao.trim()
        )

        feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
        _uiState.update {
            it.copy(
                itensLote = it.itensLote + novoItemLote,
                selectedItem = null,
                itemSearchQuery = "",
                quantidade = 1,
                observacao = "",
                formErrorMessage = null
            )
        }
    }

    /**
     * Modo Múltiplo: Remove um item da lista antes de iniciar o recebimento guiado.
     */
    fun removerItemDoLote(loteId: String) {
        _uiState.update { state ->
            state.copy(itensLote = state.itensLote.filterNot { it.id == loteId })
        }
    }

    /**
     * Modo Múltiplo: Inicia o fluxo passo a passo de Recebimento Guiado.
     */
    fun iniciarRecebimentoGuiado() {
        if (_uiState.value.itensLote.isEmpty()) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return
        }

        _uiState.update {
            it.copy(
                step = RecebimentoStep.GUIADO_NFC,
                passoGuiadoIndex = 0,
                resumoRecebimentoMultiplo = emptyList(),
                unmappedTagErrorUid = null,
                isErrorFlashingRed = false,
                formErrorMessage = null
            )
        }
    }

    /**
     * Modo Múltiplo: Registra a entrada do item do passo atual na posição bipada e avança para o próximo passo.
     */
    private suspend fun confirmarPassoGuiadoNaPosicao(pos: Posicao, tagUid: String?) {
        val state = _uiState.value
        val itemLote = state.itemGuiadoAtual ?: return

        val prat = repository.getPrateleiraById(pos.prateleiraId)
        val est = prat?.let { repository.getEstanteById(it.estanteId) }
        val endereco = buildEnderecoFormatado(est, prat, pos)

        val estoqueAtual = repository.getEstoque(itemLote.item.id, pos.id)?.quantidade ?: 0
        val novaQtd = estoqueAtual + itemLote.quantidade
        val resEstoque = repository.atualizarEstoquePosicao(itemLote.item.id, pos.id, novaQtd)
        if (resEstoque.isFailure) {
            feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
            return
        }

        val mov = Movimentacao(
            tipo = TipoMovimentacao.ENTRADA,
            itemId = itemLote.item.id,
            posicaoId = pos.id,
            quantidade = itemLote.quantidade,
            observacao = itemLote.observacao.takeIf { it.isNotBlank() } ?: "Recebimento Múltiplo Guiado",
            modoManual = false
        )
        repository.registrarMovimentacao(mov)

        feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)

        val itemResumo = ItemRecebidoResumo(
            item = itemLote.item,
            quantidade = itemLote.quantidade,
            posicao = pos,
            enderecoFormatado = endereco,
            tagUid = tagUid
        )
        val novoResumo = state.resumoRecebimentoMultiplo + itemResumo
        val msgPasso = "Entrada registrada: ${itemLote.item.nome} × ${itemLote.quantidade} em $endereco"
        val proximoIndex = state.passoGuiadoIndex + 1

        if (proximoIndex < state.itensLote.size) {
            _uiState.update {
                it.copy(
                    passoGuiadoIndex = proximoIndex,
                    resumoRecebimentoMultiplo = novoResumo,
                    snackbarMessage = msgPasso,
                    unmappedTagErrorUid = null,
                    isErrorFlashingRed = false
                )
            }
        } else {
            val totalUnidades = novoResumo.sumOf { it.quantidade }
            val msgFinal = "Recebimento múltiplo concluído! ${novoResumo.size} ${if (novoResumo.size == 1) "item guardado" else "itens guardados"} ($totalUnidades un)."
            _uiState.update {
                it.copy(
                    step = RecebimentoStep.SUCCESS,
                    resumoRecebimentoMultiplo = novoResumo,
                    successMessage = msgFinal,
                    snackbarMessage = msgPasso,
                    unmappedTagErrorUid = null,
                    isErrorFlashingRed = false
                )
            }
        }
    }

    fun selecionarItem(item: Item?) {
        _uiState.update {
            it.copy(
                selectedItem = item,
                itemSearchQuery = item?.let { itm -> "${itm.sku} - ${itm.nome}" } ?: "",
                formErrorMessage = null
            )
        }
    }

    fun setItemSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                itemSearchQuery = query,
                selectedItem = null,
                formErrorMessage = null
            )
        }
    }

    fun setQuantidade(novaQuantidade: Int) {
        _uiState.update {
            it.copy(
                quantidade = novaQuantidade.coerceIn(1, 99999),
                formErrorMessage = null
            )
        }
    }

    fun setObservacao(novaObservacao: String) {
        _uiState.update {
            it.copy(observacao = novaObservacao)
        }
    }

    /**
     * Cadastro rápido de novo item diretamente pela tela de Recebimento (Seção 3.4.3 - [+ Novo Item]).
     */
    suspend fun cadastrarNovoItemRapido(
        sku: String,
        nome: String,
        categoria: String,
        unidade: String,
        estoqueMinimo: Int = 5
    ): Result<Item> {
        val skuTrimmed = sku.trim().uppercase()
        val nomeTrimmed = nome.trim()
        val catTrimmed = categoria.trim().ifBlank { "Geral" }
        val unidTrimmed = unidade.trim().uppercase().ifBlank { "UN" }

        if (skuTrimmed.isBlank() || nomeTrimmed.isBlank()) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return Result.failure(IllegalArgumentException("SKU e Nome são obrigatórios."))
        }

        val newItem = Item(
            sku = skuTrimmed,
            nome = nomeTrimmed,
            categoria = catTrimmed,
            unidade = unidTrimmed,
            estoqueMinimo = estoqueMinimo.coerceAtLeast(0)
        )

        val res = repository.salvarItem(newItem)
        return if (res.isSuccess) {
            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
            selecionarItem(newItem)
            Result.success(newItem)
        } else {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            Result.failure(res.exceptionOrNull() ?: IllegalArgumentException("Falha ao cadastrar item."))
        }
    }

    /**
     * Confirmação de Entrada no Modo Único (Seção 3.4.3 e 3.4.4):
     * - Valida Posição, Item selecionado e Quantidade > 0.
     * - Incrementa o saldo do item na tabela EstoquePosicao.
     * - Registra Movimentacao ENTRADA.
     * - Emite feedback de sucesso e exibe mensagem/Snackbar.
     */
    fun confirmarEntrada() {
        val state = _uiState.value
        val pos = state.resolvedPosicao
        val item = state.selectedItem
        val qtd = state.quantidade

        if (pos == null || item == null || qtd <= 0) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            _uiState.update {
                it.copy(formErrorMessage = "Selecione uma posição válida, um item e informe quantidade maior que zero.")
            }
            return
        }

        scope.launch {
            val estoqueAtual = repository.getEstoque(item.id, pos.id)?.quantidade ?: 0
            val novaQtd = estoqueAtual + qtd
            val resEstoque = repository.atualizarEstoquePosicao(item.id, pos.id, novaQtd)
            if (resEstoque.isFailure) {
                feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
                _uiState.update {
                    it.copy(formErrorMessage = resEstoque.exceptionOrNull()?.message ?: "Erro ao atualizar estoque.")
                }
                return@launch
            }

            val mov = Movimentacao(
                tipo = TipoMovimentacao.ENTRADA,
                itemId = item.id,
                posicaoId = pos.id,
                quantidade = qtd,
                observacao = state.observacao.trim().takeIf { it.isNotBlank() },
                modoManual = false
            )
            repository.registrarMovimentacao(mov)

            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
            val msg = "Entrada registrada: ${item.nome} × $qtd em ${state.enderecoFormatado}"

            val resumoUnico = ItemRecebidoResumo(
                item = item,
                quantidade = qtd,
                posicao = pos,
                enderecoFormatado = state.enderecoFormatado,
                tagUid = state.resolvedTagUid
            )

            _uiState.update {
                it.copy(
                    step = RecebimentoStep.SUCCESS,
                    resumoRecebimentoMultiplo = listOf(resumoUnico),
                    successMessage = msg,
                    snackbarMessage = msg,
                    formErrorMessage = null
                )
            }
        }
    }

    /**
     * Limpa os campos do formulário e retorna ao início do modo selecionado ([Nova Entrada]).
     */
    fun iniciarNovaEntrada() {
        val modoAtual = _uiState.value.modo
        _uiState.update {
            RecebimentoUiState(
                modo = modoAtual,
                step = if (modoAtual == ModoRecebimento.UNICO) {
                    RecebimentoStep.SCAN_NFC
                } else {
                    RecebimentoStep.MONTAGEM_LISTA
                }
            )
        }
    }

    /**
     * Descarta preenchimento em andamento e retorna ao passo inicial do modo ativo (Seção 3.7).
     */
    fun descartarFormularioEVoltarParaScan() {
        iniciarNovaEntrada()
    }

    fun dismissUnmappedTagError() {
        _uiState.update {
            it.copy(
                unmappedTagErrorUid = null,
                isErrorFlashingRed = false
            )
        }
    }

    fun consumeSnackbarMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
