package com.uniso.wms.pickbylight.ui.screens.enderecos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.model.TagNFC
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.nfc.NfcController
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.FeedbackScenario
import com.uniso.wms.pickbylight.util.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class FiltroNfc {
    TODOS,
    VINCULADO,
    PENDENTE
}

data class PosicaoItemUi(
    val posicao: Posicao,
    val tagNfc: TagNFC?,
    val isVinculado: Boolean
)

data class PrateleiraItemUi(
    val prateleira: Prateleira,
    val isExpanded: Boolean,
    val posicoes: List<PosicaoItemUi>
)

data class EstanteItemUi(
    val estante: Estante,
    val isExpanded: Boolean,
    val prateleiras: List<PrateleiraItemUi>
)

sealed interface EnderecosUiState {
    object Loading : EnderecosUiState
    object Empty : EnderecosUiState
    data class Error(val message: String) : EnderecosUiState
    data class Success(
        val estantes: List<EstanteItemUi>,
        val totalPosicoes: Int,
        val posicoesVinculadasCount: Int,
        val posicoesPendentesCount: Int,
        val searchQuery: String = "",
        val filtroNfc: FiltroNfc = FiltroNfc.TODOS
    ) : EnderecosUiState
}

class EnderecosViewModel(
    private val repository: WmsRepository,
    private val mqttService: MqttService,
    private val feedbackController: FeedbackController
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _filtroNfc = MutableStateFlow(FiltroNfc.TODOS)
    val filtroNfc: StateFlow<FiltroNfc> = _filtroNfc

    private val _expandedEstantes = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val _expandedPrateleiras = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    val uiState: StateFlow<EnderecosUiState> = combine(
        repository.getEstantes(),
        repository.getPrateleiras(),
        repository.getPosicoes(),
        repository.getTagsNFC(),
        _searchQuery,
        _filtroNfc,
        _expandedEstantes,
        _expandedPrateleiras
    ) { args ->
        val estantes = args[0] as List<Estante>
        val prateleiras = args[1] as List<Prateleira>
        val posicoes = args[2] as List<Posicao>
        val tagsNfc = args[3] as List<TagNFC>
        val query = args[4] as String
        val filtro = args[5] as FiltroNfc
        val expEstantes = args[6] as Map<String, Boolean>
        val expPrateleiras = args[7] as Map<String, Boolean>

        if (estantes.isEmpty()) {
            return@combine EnderecosUiState.Empty
        }

        val totalPosicoes = posicoes.size
        val vinculadasCount = tagsNfc.size
        val pendentesCount = (totalPosicoes - vinculadasCount).coerceAtLeast(0)

        // Filtro em cascata respeitando a hierarquia
        val estantesUi = estantes.filter { est ->
            query.isBlank() ||
                est.codigo.contains(query, ignoreCase = true) ||
                est.nome.contains(query, ignoreCase = true) ||
                prateleiras.any { it.estanteId == est.id && it.codigo.contains(query, ignoreCase = true) } ||
                posicoes.any { pos ->
                    prateleiras.any { it.estanteId == est.id && it.id == pos.prateleiraId } &&
                        (pos.codigo.contains(query, ignoreCase = true) || tagsNfc.any { it.posicaoId == pos.id && it.uid.contains(query, ignoreCase = true) })
                }
        }.map { estante ->
            val isEstanteExpanded = expEstantes[estante.id] ?: true
            val prateleirasDaEstante = prateleiras.filter { it.estanteId == estante.id }.map { prat ->
                val isPratExpanded = expPrateleiras[prat.id] ?: true
                val posicoesDaPrateleira = posicoes.filter { it.prateleiraId == prat.id }
                    .map { pos ->
                        val tag = tagsNfc.find { it.posicaoId == pos.id }
                        PosicaoItemUi(
                            posicao = pos,
                            tagNfc = tag,
                            isVinculado = tag != null
                        )
                    }
                    .filter { posUi ->
                        val matchesQuery = query.isBlank() ||
                            posUi.posicao.codigo.contains(query, ignoreCase = true) ||
                            (posUi.tagNfc?.uid?.contains(query, ignoreCase = true) == true) ||
                            prat.codigo.contains(query, ignoreCase = true) ||
                            estante.codigo.contains(query, ignoreCase = true) ||
                            estante.nome.contains(query, ignoreCase = true)

                        val matchesFiltro = when (filtro) {
                            FiltroNfc.TODOS -> true
                            FiltroNfc.VINCULADO -> posUi.isVinculado
                            FiltroNfc.PENDENTE -> !posUi.isVinculado
                        }

                        matchesQuery && matchesFiltro
                    }

                PrateleiraItemUi(
                    prateleira = prat,
                    isExpanded = isPratExpanded,
                    posicoes = posicoesDaPrateleira
                )
            }

            EstanteItemUi(
                estante = estante,
                isExpanded = isEstanteExpanded,
                prateleiras = prateleirasDaEstante
            )
        }

        EnderecosUiState.Success(
            estantes = estantesUi,
            totalPosicoes = totalPosicoes,
            posicoesVinculadasCount = vinculadasCount,
            posicoesPendentesCount = pendentesCount,
            searchQuery = query,
            filtroNfc = filtro
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EnderecosUiState.Loading
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFiltroNfc(filtro: FiltroNfc) {
        _filtroNfc.value = filtro
    }

    fun toggleEstanteExpanded(estanteId: String) {
        val current = _expandedEstantes.value.toMutableMap()
        current[estanteId] = !(current[estanteId] ?: true)
        _expandedEstantes.value = current
    }

    fun togglePrateleiraExpanded(prateleiraId: String) {
        val current = _expandedPrateleiras.value.toMutableMap()
        current[prateleiraId] = !(current[prateleiraId] ?: true)
        _expandedPrateleiras.value = current
    }

    suspend fun inserirEstante(codigo: String, nome: String, setor: String, ledId: String): Result<Unit> {
        val estante = Estante(
            codigo = codigo.trim().uppercase(),
            nome = nome.trim(),
            setor = setor.trim(),
            ledId = ledId.trim().uppercase()
        )
        val res = repository.inserirEstante(estante)
        if (res.isSuccess) {
            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
        } else {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
        }
        return res
    }

    suspend fun inserirPrateleira(estanteId: String, codigo: String, ledIdPonta: String): Result<Unit> {
        val prateleira = Prateleira(
            estanteId = estanteId,
            codigo = codigo.trim().uppercase(),
            ledIdPonta = ledIdPonta.trim().uppercase()
        )
        val res = repository.inserirPrateleira(prateleira)
        if (res.isSuccess) {
            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
        } else {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
        }
        return res
    }

    suspend fun inserirPosicao(prateleiraId: String, codigo: String, limitesLap: String, ledId: String): Result<Unit> {
        val cleanLap = limitesLap.trim()
        if (!Posicao.validarLimitesLap(cleanLap)) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            return Result.failure(IllegalArgumentException("Formato esperado: 30x20x40cm ou 150x80x200mm"))
        }

        val posicao = Posicao(
            prateleiraId = prateleiraId,
            codigo = codigo.trim().uppercase(),
            limitesLap = cleanLap,
            ledId = ledId.trim().uppercase()
        )
        val res = repository.inserirPosicao(posicao)
        if (res.isSuccess) {
            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
        } else {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
        }
        return res
    }

    /**
     * Vinculação NFC (Regra de Ouro da Seção 3.2):
     * - O NFC opera apenas como leitura de UID (nunca grava na tag física NTAG213).
     * - O vínculo é gravado exclusivamente na tabela TagNFC (fonte única de verdade).
     * - Valida formato de 7 bytes hex e unicidade no armazém.
     * - Se a tag já estiver vinculada a outra posição, bloqueia e avisa:
     *   "Tag já cadastrada em [Estante X > Prat Y > Pos Z]".
     */
    suspend fun vincularTag(posicaoId: String, rawUid: String): Result<Unit> {
        val normalized = NfcController.normalizeUid(rawUid)
        if (!NfcController.isValidUid(normalized)) {
            feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
            return Result.failure(IllegalArgumentException("Formato de UID inválido. Esperado hexadecimal de 7 bytes (ex: 04:A1:2B:3C:4D:5E:01)"))
        }

        // Verifica se a tag já está cadastrada em outra posição
        val tagExistente = repository.getTagByUid(normalized)
        if (tagExistente != null && tagExistente.posicaoId != posicaoId) {
            val posExistente = repository.getPosicaoById(tagExistente.posicaoId)
            val pratExistente = posExistente?.let { repository.getPrateleiraById(it.prateleiraId) }
            val estExistente = pratExistente?.let { repository.getEstanteById(it.estanteId) }

            val enderecoExistente = "${estExistente?.nome ?: estExistente?.codigo ?: "Estante"} > ${pratExistente?.codigo ?: "Prateleira"} > ${posExistente?.codigo ?: tagExistente.posicaoId}"
            feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
            return Result.failure(IllegalStateException("Tag já cadastrada em $enderecoExistente"))
        }

        // Desvincula tag anterior desta posição caso existisse
        val tagAtualDestaPos = repository.getTagByPosicaoId(posicaoId)
        if (tagAtualDestaPos != null && tagAtualDestaPos.uid != normalized) {
            repository.desvincularTag(tagAtualDestaPos.uid)
        }

        val novaTag = TagNFC(
            uid = normalized,
            posicaoId = posicaoId,
            dataVinculo = currentTimeMillis()
        )
        val res = repository.vincularTag(novaTag)
        if (res.isSuccess) {
            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
        } else {
            feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
        }
        return res
    }

    suspend fun desvincularTag(uid: String): Result<Unit> {
        val res = repository.desvincularTag(uid)
        if (res.isSuccess) {
            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
        }
        return res
    }

    suspend fun testarLed(estanteLedId: String, prateleiraLedId: String?, posicaoLedId: String?): Result<Unit> {
        val res = mqttService.testarLed(
            estanteLedId = estanteLedId,
            prateleiraLedId = prateleiraLedId,
            posicaoLedId = posicaoLedId,
            cor = "AZUL",
            duracaoMs = 3000
        )
        if (res.isSuccess) {
            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
        } else {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
        }
        return res
    }
}
