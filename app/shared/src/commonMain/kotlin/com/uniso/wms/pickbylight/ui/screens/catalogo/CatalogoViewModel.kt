package com.uniso.wms.pickbylight.ui.screens.catalogo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uniso.wms.pickbylight.data.settings.AppSettings
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.EstoquePosicaoDetalhe
import com.uniso.wms.pickbylight.domain.model.Item
import com.uniso.wms.pickbylight.domain.model.ItemComEstoque
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.FeedbackScenario
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Item individual preparado com dados agregados de estoque por posição para exibição no card do catálogo.
 */
data class ItemCardUi(
    val item: Item,
    val saldoTotal: Int,
    val estoques: List<EstoquePosicaoDetalhe>,
    val isAbaixoMinimo: Boolean,
    val enderecoCompleto: String
)

/**
 * Estado UDF do Catálogo de Itens (Seção 3.3).
 * Segue estritamente a convenção Loading | Empty | Error | Success.
 */
sealed interface CatalogoUiState {
    object Loading : CatalogoUiState
    object Empty : CatalogoUiState
    data class Error(val message: String) : CatalogoUiState
    data class Success(
        val itens: List<ItemCardUi>,
        val totalItensCount: Int,
        val itensAbaixoMinimoCount: Int,
        val categoriasDisponiveis: List<String>,
        val categoriaSelecionada: String? = null,
        val apenasAbaixoMinimo: Boolean = false,
        val searchQuery: String = "",
        val modoDebugNfc: Boolean = false
    ) : CatalogoUiState
}

class CatalogoViewModel(
    private val repository: WmsRepository,
    private val feedbackController: FeedbackController
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _categoriaSelecionada = MutableStateFlow<String?>(null)
    val categoriaSelecionada: StateFlow<String?> = _categoriaSelecionada.asStateFlow()

    private val _apenasAbaixoMinimo = MutableStateFlow(false)
    val apenasAbaixoMinimo: StateFlow<Boolean> = _apenasAbaixoMinimo.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setCategoria(categoria: String?) {
        _categoriaSelecionada.value = categoria
    }

    fun toggleApenasAbaixoMinimo() {
        _apenasAbaixoMinimo.value = !_apenasAbaixoMinimo.value
    }

    fun setApenasAbaixoMinimo(enabled: Boolean) {
        _apenasAbaixoMinimo.value = enabled
    }

    val uiState: StateFlow<CatalogoUiState> = combine(
        repository.getItensComEstoque(),
        searchQuery,
        categoriaSelecionada,
        apenasAbaixoMinimo,
        AppSettings.modoDebugNfc
    ) { rawItensComEstoque, query, categoria, apenasMinimo, debugNfc ->
        if (rawItensComEstoque.isEmpty()) {
            return@combine CatalogoUiState.Empty
        }

        val categorias = rawItensComEstoque.map { it.item.categoria }.distinct().sorted()
        val totalCount = rawItensComEstoque.size
        val abaixoMinimoCount = rawItensComEstoque.count { it.isAbaixoMinimo }

        val itensUi = rawItensComEstoque.map { itemComEstoque ->
            val enderecoPrincipal = when {
                itemComEstoque.estoques.isEmpty() -> "Sem Estoque Alocado"
                itemComEstoque.estoques.size == 1 -> itemComEstoque.estoques.first().enderecoFormatado
                else -> "${itemComEstoque.estoques.size} localizações no armazém"
            }

            ItemCardUi(
                item = itemComEstoque.item,
                saldoTotal = itemComEstoque.saldoTotal,
                estoques = itemComEstoque.estoques,
                isAbaixoMinimo = itemComEstoque.isAbaixoMinimo,
                enderecoCompleto = enderecoPrincipal
            )
        }

        // Aplicação dos filtros reativos (Seção 3.3)
        val itensFiltrados = itensUi.filter { itemUi ->
            val matchesQuery = query.isBlank() ||
                itemUi.item.sku.contains(query, ignoreCase = true) ||
                itemUi.item.nome.contains(query, ignoreCase = true) ||
                itemUi.item.categoria.contains(query, ignoreCase = true)

            val matchesCategoria = categoria == null || itemUi.item.categoria.equals(categoria, ignoreCase = true)
            val matchesMinimo = !apenasMinimo || itemUi.isAbaixoMinimo

            matchesQuery && matchesCategoria && matchesMinimo
        }

        CatalogoUiState.Success(
            itens = itensFiltrados,
            totalItensCount = totalCount,
            itensAbaixoMinimoCount = abaixoMinimoCount,
            categoriasDisponiveis = categorias,
            categoriaSelecionada = categoria,
            apenasAbaixoMinimo = apenasMinimo,
            searchQuery = query,
            modoDebugNfc = debugNfc
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CatalogoUiState.Loading
    )

    /**
     * Cadastro e Edição de Item com validações de unicidade e regras da Seção 3.3.
     */
    suspend fun salvarItem(item: Item): Result<Unit> {
        val skuTrimmed = item.sku.trim().uppercase()
        val nomeTrimmed = item.nome.trim()
        val categoriaTrimmed = item.categoria.trim()
        val unidadeTrimmed = item.unidade.trim().uppercase()

        if (skuTrimmed.isBlank()) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return Result.failure(IllegalArgumentException("O SKU do produto é obrigatório."))
        }
        if (nomeTrimmed.isBlank()) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return Result.failure(IllegalArgumentException("O Nome do produto é obrigatório."))
        }
        if (categoriaTrimmed.isBlank()) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return Result.failure(IllegalArgumentException("A Categoria é obrigatória."))
        }
        if (unidadeTrimmed.isBlank()) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return Result.failure(IllegalArgumentException("A Unidade de medida é obrigatória."))
        }
        if (item.estoqueMinimo < 0) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return Result.failure(IllegalArgumentException("O estoque mínimo não pode ser negativo."))
        }

        val itemValidado = item.copy(
            sku = skuTrimmed,
            nome = nomeTrimmed,
            categoria = categoriaTrimmed,
            unidade = unidadeTrimmed
        )

        val result = repository.salvarItem(itemValidado)
        if (result.isSuccess) {
            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
        } else {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
        }
        return result
    }

    /**
     * Ação Rápida — Mover Item (Transferência Interna - Seção 3.3):
     * Suporta especificação de posição de origem e destino com quantidade particionada.
     */
    suspend fun moverItem(
        itemId: String,
        posicaoOrigemId: String,
        novaPosicaoId: String,
        quantidade: Int
    ): Result<Unit> {
        if (posicaoOrigemId.isBlank()) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return Result.failure(IllegalArgumentException("Selecione a posição de origem."))
        }
        if (novaPosicaoId.isBlank()) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return Result.failure(IllegalArgumentException("Selecione a posição de destino."))
        }
        if (posicaoOrigemId == novaPosicaoId) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return Result.failure(IllegalArgumentException("A posição de destino deve ser diferente da posição atual."))
        }
        if (quantidade <= 0) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return Result.failure(IllegalArgumentException("A quantidade a mover deve ser maior que zero."))
        }

        val estoqueOrigem = repository.getEstoque(itemId, posicaoOrigemId)
        val saldoOrigem = estoqueOrigem?.quantidade ?: 0
        if (quantidade > saldoOrigem) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return Result.failure(IllegalArgumentException("A quantidade não pode exceder o saldo disponível na posição de origem ($saldoOrigem)."))
        }

        val result = repository.moverItem(itemId, posicaoOrigemId, novaPosicaoId, quantidade)
        if (result.isSuccess) {
            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
        } else {
            feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
        }
        return result
    }

    /**
     * Sobrecarga para compatibilidade com transferências quando a origem é omitida
     * (assume a primeira posição onde o item possui saldo).
     */
    suspend fun moverItem(itemId: String, novaPosicaoId: String, quantidade: Int): Result<Unit> {
        val itemComEstoque = repository.getItemComEstoqueById(itemId)
            ?: return Result.failure(IllegalArgumentException("Item não encontrado."))
        val origem = itemComEstoque.estoques.firstOrNull { it.quantidade > 0 }
            ?: return Result.failure(IllegalArgumentException("Item não possui posição com estoque para transferência."))
        return moverItem(itemId, origem.posicaoId, novaPosicaoId, quantidade)
    }

    /**
     * Ação de Debug — [Simular Item em Separação] (Seção 3.3):
     * Alterna a flag emSeparacao para permitir que a banca demonstre o bloqueio por concorrência.
     */
    suspend fun alternarEmSeparacao(itemId: String): Result<Boolean> {
        val item = repository.getItemById(itemId)
            ?: return Result.failure(IllegalArgumentException("Item não encontrado."))

        val novoEstado = !item.emSeparacao
        val res = repository.setEmSeparacao(itemId, novoEstado)
        return if (res.isSuccess) {
            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
            Result.success(novoEstado)
        } else {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            Result.failure(res.exceptionOrNull() ?: IllegalStateException("Erro ao alternar lock."))
        }
    }

    /**
     * Resolução de posição a partir de uma Tag NFC lida ou simulada (Seção 3.3).
     */
    suspend fun buscarPosicaoPorTagUid(uid: String): Posicao? {
        val tag = repository.getTagByUid(uid) ?: return null
        return repository.getPosicaoById(tag.posicaoId)
    }
}
