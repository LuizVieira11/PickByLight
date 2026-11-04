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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RecebimentoStep {
    SCAN_NFC,
    FORM,
    SUCCESS
}

/**
 * Estado UDF do módulo de Recebimento de Itens (Seção 3.4).
 */
data class RecebimentoUiState(
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
    val unmappedTagErrorUid: String? = null,
    val isErrorFlashingRed: Boolean = false,
    val formErrorMessage: String? = null,
    val successMessage: String = "",
    val snackbarMessage: String? = null
) {
    val hasDirtyForm: Boolean
        get() = selectedItem != null || quantidade > 1 || observacao.isNotBlank()

    val isFormValid: Boolean
        get() = resolvedPosicao != null && selectedItem != null && quantidade > 0
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
     * Resolução de Endereço via Leitura NFC (Seção 3.4.2 e Seção 5.4):
     * - Se encontrada na tabela TagNFC: vibração curta (150ms) + avança automaticamente para o formulário.
     * - Se não cadastrada: vibração longa (600ms) + tela vermelha + modal com atalho [Cadastrar Agora].
     */
    fun processarLeituraNfc(rawUid: String) {
        val uid = rawUid.trim().uppercase()
        if (uid.isBlank()) return

        scope.launch {
            val tag = repository.getTagByUid(uid)
            if (tag == null) {
                feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
                _uiState.update {
                    it.copy(
                        unmappedTagErrorUid = uid,
                        isErrorFlashingRed = true
                    )
                }
            } else {
                val pos = repository.getPosicaoById(tag.posicaoId)
                if (pos == null) {
                    feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
                    _uiState.update {
                        it.copy(
                            unmappedTagErrorUid = uid,
                            isErrorFlashingRed = true
                        )
                    }
                    return@launch
                }

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
     * Fallback de Seleção Manual de Endereço (Seção 3.4.1 - Web / Contingência).
     */
    fun selecionarEnderecoManualmente(posicao: Posicao) {
        scope.launch {
            val prat = repository.getPrateleiraById(posicao.prateleiraId)
            val est = prat?.let { repository.getEstanteById(it.estanteId) }
            val tag = repository.getTagByPosicaoId(posicao.id)
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
     * Confirmação de Entrada (Seção 3.4.3 e 3.4.4):
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

            _uiState.update {
                it.copy(
                    step = RecebimentoStep.SUCCESS,
                    successMessage = msg,
                    snackbarMessage = msg,
                    formErrorMessage = null
                )
            }
        }
    }

    /**
     * Limpa os campos do formulário e retorna à leitura NFC para o próximo lote (Seção 3.4.4 - [Nova Entrada]).
     */
    fun iniciarNovaEntrada() {
        _uiState.update {
            RecebimentoUiState(step = RecebimentoStep.SCAN_NFC)
        }
    }

    /**
     * Descarta preenchimento em andamento e retorna à leitura NFC (Seção 3.7).
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

