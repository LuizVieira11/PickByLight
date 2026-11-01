package com.uniso.wms.pickbylight.ui.state

/**
 * Estado padrão UDF exigido para todas as telas do WMS Pick-by-Light.
 * Padroniza os 4 estados visuais da Seção 1.3 da especificação:
 * - Loading
 * - Empty
 * - Error
 * - Success
 */
sealed interface UiState<out T> {
    data class Loading(
        val message: String = "Carregando..."
    ) : UiState<Nothing>

    data class Empty(
        val title: String,
        val message: String,
        val actionLabel: String? = null
    ) : UiState<Nothing>

    data class Error(
        val message: String,
        val canRetry: Boolean = true
    ) : UiState<Nothing>

    data class Success<out T>(
        val data: T
    ) : UiState<T>
}
