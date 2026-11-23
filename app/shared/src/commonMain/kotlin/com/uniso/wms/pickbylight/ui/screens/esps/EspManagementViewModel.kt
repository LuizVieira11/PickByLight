package com.uniso.wms.pickbylight.ui.screens.esps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uniso.wms.pickbylight.data.esp.ConfigLock
import com.uniso.wms.pickbylight.data.esp.ConfigLockProvider
import com.uniso.wms.pickbylight.data.esp.DomainLedIds
import com.uniso.wms.pickbylight.data.esp.EspConfigCoordinator
import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.esp.MappingViolation
import com.uniso.wms.pickbylight.data.esp.MappingViolationType
import com.uniso.wms.pickbylight.data.mqtt.EspDevice
import com.uniso.wms.pickbylight.data.mqtt.EspManagementTimings
import com.uniso.wms.pickbylight.data.mqtt.EspPresence
import com.uniso.wms.pickbylight.data.mqtt.MqttConnectionState
import com.uniso.wms.pickbylight.data.mqtt.MqttDiagnostic
import com.uniso.wms.pickbylight.data.mqtt.MqttOutcome
import com.uniso.wms.pickbylight.data.mqtt.MqttService
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigResult
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet
import com.uniso.wms.pickbylight.data.mqtt.model.ConfigState
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.EspOnlineStatus
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.domain.model.Estante
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.Prateleira
import com.uniso.wms.pickbylight.domain.repository.WmsRepository
import com.uniso.wms.pickbylight.isEspManagementSupported
import com.uniso.wms.pickbylight.util.FeedbackController
import com.uniso.wms.pickbylight.util.FeedbackScenario
import com.uniso.wms.pickbylight.util.generateUUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

sealed interface EspListPhase {
    data object Unsupported : EspListPhase
    data object Loading : EspListPhase
    data object Empty : EspListPhase
    data class Error(val message: String) : EspListPhase
    data object Success : EspListPhase
}

sealed interface EspDetailReadState {
    data object Idle : EspDetailReadState
    data object Loading : EspDetailReadState
    /** Seção 9.2: timeout de leitura nunca é exibido como "configuração vazia". */
    data class NotRead(
        val message: String = "Configuração não lida",
        val diagnostics: List<MqttDiagnostic> = emptyList()
    ) : EspDetailReadState
    data class Loaded(
        val revision: Long,
        val diagnostics: List<MqttDiagnostic> = emptyList()
    ) : EspDetailReadState
}

sealed interface EspConfigOpState {
    data object Idle : EspConfigOpState
    data class WaitingResult(val requestId: String) : EspConfigOpState
    data class AppliedWaitingState(
        val expectedRevision: Long,
        val timedOutWaitingState: Boolean = false,
        val diagnostics: List<MqttDiagnostic> = emptyList()
    ) : EspConfigOpState
    data class Synced(
        val revision: Long,
        val diagnostics: List<MqttDiagnostic> = emptyList()
    ) : EspConfigOpState
    data class Rejected(
        val errorCode: EspErrorCode?,
        val message: String,
        val diagnostics: List<MqttDiagnostic> = emptyList()
    ) : EspConfigOpState
    data class Timeout(
        val pendingSet: ConfigSet,
        val diagnostics: List<MqttDiagnostic> = emptyList()
    ) : EspConfigOpState
    data class Failed(val message: String) : EspConfigOpState
}

data class LedIdOption(
    val ledId: String,
    val label: String,
    val kind: MappingKind,
    val ownerOtherEspId: String? = null,
    val alreadyInDraft: Boolean = false
) {
    val isAvailable: Boolean get() = ownerOtherEspId == null && !alreadyInDraft
}

data class EspManagementUiState(
    val isSupported: Boolean = true,
    val listPhase: EspListPhase = EspListPhase.Loading,
    val isDiscovering: Boolean = false,
    val listErrorMessage: String? = null,
    val devices: List<EspDevice> = emptyList(),
    val connectionState: MqttConnectionState = MqttConnectionState.DISCONNECTED,
    val isColetaActive: Boolean = false,
    val isConfigLockActive: Boolean = false,
    val selectedEspId: String? = null,
    val detailReadState: EspDetailReadState = EspDetailReadState.Idle,
    val configReadInSession: Boolean = false,
    val loadedConfigRevision: Long? = null,
    val originalMappings: List<EspMapping> = emptyList(),
    val draftMappings: List<EspMapping> = emptyList(),
    val violations: List<MappingViolation> = emptyList(),
    val opState: EspConfigOpState = EspConfigOpState.Idle,
    val infoMessage: String? = null,
    val testingLedId: String? = null,
    val ledTestFeedbackByLedId: Map<String, String> = emptyMap(),
    val isForgetting: Boolean = false,
    val espForgotten: Boolean = false,
    val forgetErrorMessage: String? = null,
    val estantes: List<Estante> = emptyList(),
    val prateleiras: List<Prateleira> = emptyList(),
    val posicoes: List<Posicao> = emptyList()
) {
    val selectedDevice: EspDevice?
        get() = devices.find { it.espId == selectedEspId }

    val selectedChannels: List<EspChannel>
        get() = selectedDevice?.status?.capabilities?.channels ?: emptyList()

    val freeChannels: List<EspChannel>
        get() {
            val used = draftMappings.map { it.channelId }.toSet()
            return selectedChannels.filter { it.channelId !in used }
        }

    /** Correção 2: bloqueia edição só com INVALID_CHANNEL_TABLE; STORAGE_FAILURE permite salvar para recuperar NVS. */
    val isChannelTableInvalid: Boolean
        get() = selectedDevice?.reportedStatus == EspOnlineStatus.CONFIG_INVALID &&
            selectedDevice?.errorCode == EspErrorCode.INVALID_CHANNEL_TABLE

    val isSaving: Boolean
        get() = opState is EspConfigOpState.WaitingResult

    val hasUnsavedChanges: Boolean
        get() = draftMappings != originalMappings

    val canEditMappings: Boolean
        get() = isSupported && configReadInSession && !isChannelTableInvalid && !isColetaActive && !isSaving

    val canAddMoreMappings: Boolean
        get() = canEditMappings &&
            selectedChannels.isNotEmpty() &&
            draftMappings.size < selectedChannels.size &&
            draftMappings.size < EspContract.MAX_MAPPINGS

    val canSave: Boolean
        get() = canEditMappings && hasUnsavedChanges && violations.isEmpty() && loadedConfigRevision != null

    val canTestLed: Boolean
        get() = isSupported && !isColetaActive && testingLedId == null && !isForgetting

    val isAnyOperationInProgress: Boolean
        get() = isSaving || isForgetting || detailReadState is EspDetailReadState.Loading || testingLedId != null

    val forgetDisabledReason: String?
        get() {
            if (!isSupported) return "Indisponível na versão Web"
            if (connectionState != MqttConnectionState.CONNECTED) return "Conecte ao broker"
            if (isColetaActive) return "Coleta em andamento"
            if (isAnyOperationInProgress || isConfigLockActive) return "Operação em andamento"
            val dev = selectedDevice ?: return "ESP não encontrado"
            if (dev.presence == EspPresence.ONLINE) return "Desligue o ESP para esquecê-lo: ele está ONLINE"
            return null
        }

    val canForgetEsp: Boolean
        get() = forgetDisabledReason == null

    fun ledIdOptionsFor(kind: MappingKind): List<LedIdOption> {
        val espId = selectedEspId
        val usedInDraft = draftMappings.map { it.ledId }.toSet()
        val otherEspOwners = mutableMapOf<String, String>()
        devices.forEach { dev ->
            if (dev.espId != espId) {
                dev.config?.mappings?.forEach { m -> otherEspOwners[m.ledId] = dev.espId }
            }
        }
        val estMap = estantes.associateBy { it.id }
        val pratMap = prateleiras.associateBy { it.id }
        return when (kind) {
            MappingKind.ESTANTE -> estantes.map { est ->
                LedIdOption(
                    ledId = est.ledId,
                    label = "${est.ledId} (${est.nome})",
                    kind = kind,
                    ownerOtherEspId = otherEspOwners[est.ledId],
                    alreadyInDraft = est.ledId in usedInDraft
                )
            }
            MappingKind.PRATELEIRA -> prateleiras.map { prat ->
                val estCodigo = estMap[prat.estanteId]?.codigo ?: "Estante"
                LedIdOption(
                    ledId = prat.ledIdPonta,
                    label = "${prat.ledIdPonta} ($estCodigo · ${prat.codigo})",
                    kind = kind,
                    ownerOtherEspId = otherEspOwners[prat.ledIdPonta],
                    alreadyInDraft = prat.ledIdPonta in usedInDraft
                )
            }
            MappingKind.POSICAO -> posicoes.map { pos ->
                val prat = pratMap[pos.prateleiraId]
                val estCodigo = estMap[prat?.estanteId]?.codigo ?: "Estante"
                val pratCodigo = prat?.codigo ?: "Prat"
                LedIdOption(
                    ledId = pos.ledId,
                    label = "${pos.ledId} ($estCodigo · $pratCodigo · ${pos.codigo})",
                    kind = kind,
                    ownerOtherEspId = otherEspOwners[pos.ledId],
                    alreadyInDraft = pos.ledId in usedInDraft
                )
            }
        }
    }
}

class EspManagementViewModel(
    private val mqttService: MqttService,
    private val repository: WmsRepository,
    private val feedbackController: FeedbackController = FeedbackController(),
    private val coordinator: EspConfigCoordinator = EspConfigCoordinator(mqttService),
    private val configLock: ConfigLock = ConfigLockProvider.getLock(),
    coroutineScope: CoroutineScope? = null,
    private val isSupported: Boolean = isEspManagementSupported
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow(
        EspManagementUiState(
            isSupported = isSupported,
            listPhase = if (isSupported) EspListPhase.Loading else EspListPhase.Unsupported
        )
    )
    val uiState: StateFlow<EspManagementUiState> = _uiState.asStateFlow()

    private var discoveryJob: Job? = null
    private var readConfigJob: Job? = null
    private var stateWaitJob: Job? = null

    init {
        if (isSupported) {
            scope.launch {
                mqttService.connectionState.collect { connState ->
                    _uiState.update { it.copy(connectionState = connState) }
                }
            }
            scope.launch {
                mqttService.espDevices.collect { devices ->
                    onEspDevicesUpdated(devices)
                }
            }
            scope.launch {
                configLock.state.collect { lockState ->
                    _uiState.update {
                        it.copy(
                            isColetaActive = lockState.isColetaActive,
                            isConfigLockActive = lockState.isConfigActive
                        )
                    }
                }
            }
            scope.launch {
                combine(
                    repository.getEstantes(),
                    repository.getPrateleiras(),
                    repository.getPosicoes()
                ) { estantes, prateleiras, posicoes ->
                    Triple(estantes, prateleiras, posicoes)
                }.collect { (estantes, prateleiras, posicoes) ->
                    _uiState.update { current ->
                        val next = current.copy(
                            estantes = estantes,
                            prateleiras = prateleiras,
                            posicoes = posicoes
                        )
                        next.copy(violations = computeViolations(next))
                    }
                }
            }
        }
    }

    /** Esclarecimento 3: chamado apenas pela tela de lista (EspListScreen). */
    fun iniciarLista() {
        if (!isSupported) return
        descobrirEsps()
    }

    fun descobrirEsps() {
        if (!isSupported) return
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            _uiState.update { state ->
                state.copy(
                    isDiscovering = true,
                    listErrorMessage = null,
                    listPhase = if (state.devices.isNotEmpty()) EspListPhase.Success else EspListPhase.Loading
                )
            }
            val result = mqttService.descobrirEsps()
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "Erro ao descobrir ESPs"
                _uiState.update { state ->
                    state.copy(
                        isDiscovering = false,
                        listErrorMessage = msg,
                        listPhase = if (state.devices.isNotEmpty()) EspListPhase.Success else EspListPhase.Error(msg)
                    )
                }
                return@launch
            }
            if (mqttService.espDevices.value.isEmpty()) {
                delay(EspManagementTimings.DISCOVERY_WINDOW_MS)
            }
            _uiState.update { state ->
                val list = mqttService.espDevices.value
                state.copy(
                    isDiscovering = false,
                    devices = list,
                    listPhase = if (list.isEmpty()) EspListPhase.Empty else EspListPhase.Success
                )
            }
        }
    }

    /** Esclarecimento 3: chamado apenas pela tela de detalhe (EspDetailScreen); não dispara discovery. */
    fun abrirDetalhe(espId: String) {
        if (!isSupported) return
        stateWaitJob?.cancel()
        _uiState.update { state ->
            state.copy(
                selectedEspId = espId,
                detailReadState = EspDetailReadState.Loading,
                configReadInSession = false,
                loadedConfigRevision = null,
                originalMappings = emptyList(),
                draftMappings = emptyList(),
                violations = emptyList(),
                opState = EspConfigOpState.Idle,
                infoMessage = null,
                ledTestFeedbackByLedId = emptyMap(),
                isForgetting = false,
                espForgotten = false,
                forgetErrorMessage = null
            )
        }
        lerConfiguracao(espId, afterRevisionMismatch = false)
    }

    fun esquecerEspAtual() {
        val current = _uiState.value
        val espId = current.selectedEspId ?: return
        if (!current.canForgetEsp) return
        scope.launch {
            _uiState.update { it.copy(isForgetting = true, forgetErrorMessage = null) }
            val result = coordinator.esquecerEsp(espId)
            result.fold(
                onSuccess = {
                    feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                    _uiState.update { it.copy(isForgetting = false, espForgotten = true) }
                },
                onFailure = { err ->
                    feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
                    _uiState.update {
                        it.copy(
                            isForgetting = false,
                            forgetErrorMessage = err.message ?: "Erro ao esquecer ESP"
                        )
                    }
                }
            )
        }
    }

    /** Esclarecimento 4: usado pelo botão "Recarregar configuração" após REVISION_MISMATCH. */
    fun recarregarAposConflitoRevisao() {
        val espId = _uiState.value.selectedEspId ?: return
        lerConfiguracao(espId, afterRevisionMismatch = true)
    }

    fun lerConfiguracao(espId: String = _uiState.value.selectedEspId ?: "", afterRevisionMismatch: Boolean = false) {
        if (!isSupported || espId.isBlank()) return
        readConfigJob?.cancel()
        stateWaitJob?.cancel()
        readConfigJob = scope.launch {
            _uiState.update { state ->
                state.copy(
                    selectedEspId = espId,
                    detailReadState = EspDetailReadState.Loading,
                    infoMessage = if (afterRevisionMismatch) state.infoMessage else null
                )
            }
            val res = mqttService.obterConfig(espId)
            res.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is MqttOutcome.Ok -> applyLoadedConfig(outcome.value, outcome.diagnostics, afterRevisionMismatch)
                        is MqttOutcome.Timeout -> {
                            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
                            _uiState.update { state ->
                                state.copy(
                                    detailReadState = EspDetailReadState.NotRead(
                                        message = "Configuração não lida",
                                        diagnostics = outcome.diagnostics
                                    ),
                                    configReadInSession = false
                                )
                            }
                        }
                        is MqttOutcome.Erro -> {
                            feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
                            _uiState.update { state ->
                                state.copy(
                                    detailReadState = EspDetailReadState.NotRead(
                                        message = "Configuração não lida (${readableErrorCode(outcome.errorCode)})",
                                        diagnostics = outcome.diagnostics
                                    ),
                                    configReadInSession = false
                                )
                            }
                        }
                    }
                },
                onFailure = { err ->
                    feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
                    _uiState.update { state ->
                        state.copy(
                            detailReadState = EspDetailReadState.NotRead(
                                message = "Configuração não lida (${err.message ?: "falha de comunicação"})"
                            ),
                            configReadInSession = false
                        )
                    }
                }
            )
        }
    }

    private fun applyLoadedConfig(
        state: ConfigState,
        diagnostics: List<MqttDiagnostic>,
        afterRevisionMismatch: Boolean
    ) {
        _uiState.update { current ->
            val currentOp = current.opState
            val nextOp = if (currentOp is EspConfigOpState.AppliedWaitingState &&
                currentOp.expectedRevision == state.configRevision
            ) {
                feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                EspConfigOpState.Synced(state.configRevision, diagnostics.ifEmpty { currentOp.diagnostics })
            } else {
                EspConfigOpState.Idle
            }
            val nextInfo = if (afterRevisionMismatch) {
                "A configuração do ESP mudou. Suas alterações foram descartadas e a versão atual foi carregada."
            } else {
                null
            }
            val next = current.copy(
                detailReadState = EspDetailReadState.Loaded(state.configRevision, diagnostics),
                configReadInSession = true,
                loadedConfigRevision = state.configRevision,
                originalMappings = state.mappings,
                draftMappings = state.mappings,
                opState = nextOp,
                infoMessage = nextInfo
            )
            next.copy(violations = computeViolations(next))
        }
    }

    fun adicionarMapping(kind: MappingKind, ledId: String, channelId: String) {
        val trimmedLed = ledId.trim()
        val trimmedCh = channelId.trim()
        if (trimmedLed.isEmpty() || trimmedCh.isEmpty()) return
        stateWaitJob?.cancel()
        _uiState.update { current ->
            if (!current.canEditMappings) return@update current
            val updated = current.draftMappings + EspMapping(trimmedLed, kind, trimmedCh)
            val next = current.copy(
                draftMappings = updated,
                opState = EspConfigOpState.Idle,
                infoMessage = null
            )
            next.copy(violations = computeViolations(next))
        }
    }

    fun removerMapping(index: Int) {
        stateWaitJob?.cancel()
        _uiState.update { current ->
            if (!current.canEditMappings || index !in current.draftMappings.indices) return@update current
            val updated = current.draftMappings.toMutableList().also { it.removeAt(index) }
            val next = current.copy(
                draftMappings = updated,
                opState = EspConfigOpState.Idle,
                infoMessage = null
            )
            next.copy(violations = computeViolations(next))
        }
    }

    fun definirRascunho(mappings: List<EspMapping>) {
        stateWaitJob?.cancel()
        _uiState.update { current ->
            if (!current.canEditMappings) return@update current
            val next = current.copy(
                draftMappings = mappings,
                opState = EspConfigOpState.Idle,
                infoMessage = null
            )
            next.copy(violations = computeViolations(next))
        }
    }

    fun salvarConfig() {
        val current = _uiState.value
        val espId = current.selectedEspId ?: return
        val expectedRevision = current.loadedConfigRevision ?: return
        if (!current.canSave) return

        val set = ConfigSet(
            schemaVersion = EspContract.SCHEMA_VERSION,
            espId = espId,
            requestId = generateUUID(),
            expectedRevision = expectedRevision,
            mappings = current.draftMappings
        )
        executarGravacao(set)
    }

    /** Seções 8.5 e 9.2: retry de timeout reutiliza exatamente o mesmo ConfigSet (mesmo request_id). */
    fun tentarNovamenteSalvar() {
        val current = _uiState.value
        val timeoutOp = current.opState as? EspConfigOpState.Timeout ?: return
        if (!current.canEditMappings) return
        executarGravacao(timeoutOp.pendingSet)
    }

    private fun executarGravacao(set: ConfigSet) {
        stateWaitJob?.cancel()
        scope.launch {
            _uiState.update {
                it.copy(
                    opState = EspConfigOpState.WaitingResult(set.requestId),
                    infoMessage = null
                )
            }
            val res = coordinator.gravarConfig(set)
            res.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is MqttOutcome.Ok -> handleSaveOk(set, outcome.value, outcome.diagnostics)
                        is MqttOutcome.Erro -> {
                            feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
                            _uiState.update { state ->
                                state.copy(
                                    opState = EspConfigOpState.Rejected(
                                        errorCode = outcome.errorCode,
                                        message = readableErrorCode(outcome.errorCode),
                                        diagnostics = outcome.diagnostics
                                    )
                                )
                            }
                        }
                        is MqttOutcome.Timeout -> {
                            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
                            _uiState.update { state ->
                                state.copy(
                                    opState = EspConfigOpState.Timeout(
                                        pendingSet = set,
                                        diagnostics = outcome.diagnostics
                                    )
                                )
                            }
                        }
                    }
                },
                onFailure = { err ->
                    feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
                    _uiState.update { state ->
                        state.copy(
                            opState = EspConfigOpState.Failed(err.message ?: "Erro ao gravar configuração")
                        )
                    }
                }
            )
        }
    }

    private suspend fun handleSaveOk(
        set: ConfigSet,
        result: ConfigResult,
        diagnostics: List<MqttDiagnostic>
    ) {
        _uiState.update { state ->
            state.copy(
                loadedConfigRevision = result.configRevision,
                originalMappings = set.mappings,
                opState = EspConfigOpState.AppliedWaitingState(
                    expectedRevision = result.configRevision,
                    timedOutWaitingState = false,
                    diagnostics = diagnostics
                )
            )
        }

        val currentStateRev = mqttService.espDevices.value
            .find { it.espId == set.espId }
            ?.config
            ?.configRevision

        if (currentStateRev == result.configRevision) {
            yield()
            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
            _uiState.update { state ->
                val op = state.opState
                if (op is EspConfigOpState.AppliedWaitingState && op.expectedRevision == result.configRevision) {
                    state.copy(
                        detailReadState = EspDetailReadState.Loaded(result.configRevision, diagnostics),
                        opState = EspConfigOpState.Synced(result.configRevision, diagnostics)
                    )
                } else state
            }
            return
        }

        // Correção 1: espera de 5 s pelo config/state usando EspManagementTimings.CONFIG_TIMEOUT_MS
        stateWaitJob?.cancel()
        stateWaitJob = scope.launch {
            delay(EspManagementTimings.CONFIG_TIMEOUT_MS)
            _uiState.update { state ->
                val op = state.opState
                if (op is EspConfigOpState.AppliedWaitingState && op.expectedRevision == result.configRevision) {
                    state.copy(opState = op.copy(timedOutWaitingState = true))
                } else state
            }
        }
    }

    fun testarMapping(mapping: EspMapping) {
        if (!isSupported) return
        val current = _uiState.value
        if (current.isColetaActive) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            _uiState.update {
                it.copy(
                    ledTestFeedbackByLedId = it.ledTestFeedbackByLedId +
                        (mapping.ledId to "Coleta em andamento: teste bloqueado")
                )
            }
            return
        }

        val hierarchy = resolveLedHierarchy(mapping, current.estantes, current.prateleiras, current.posicoes)
        if (hierarchy == null) {
            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
            _uiState.update {
                it.copy(
                    ledTestFeedbackByLedId = it.ledTestFeedbackByLedId +
                        (mapping.ledId to "Endereço não encontrado no domínio")
                )
            }
            return
        }

        scope.launch {
            _uiState.update {
                it.copy(
                    testingLedId = mapping.ledId,
                    ledTestFeedbackByLedId = it.ledTestFeedbackByLedId + (mapping.ledId to "Testando...")
                )
            }
            val (estLed, pratLed, posLed) = hierarchy
            val res = mqttService.testarLedComConfirmacao(
                estanteLedId = estLed,
                prateleiraLedId = pratLed,
                posicaoLedId = posLed
            )
            val feedbackText = res.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is MqttOutcome.Ok -> {
                            feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                            "LED confirmado"
                        }
                        is MqttOutcome.Erro -> {
                            feedbackController.triggerFeedback(FeedbackScenario.ERROR_CRITICAL_LONG_VIBRATE)
                            "Erro no ESP (${outcome.errorCode?.name ?: "ERRO"})"
                        }
                        is MqttOutcome.Timeout -> {
                            feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
                            "Sem confirmação do ESP"
                        }
                    }
                },
                onFailure = { err ->
                    feedbackController.triggerFeedback(FeedbackScenario.WARNING_DOUBLE_BEEP_VIBRATE)
                    err.message ?: "Falha ao enviar TESTE_LED"
                }
            )
            _uiState.update {
                it.copy(
                    testingLedId = null,
                    ledTestFeedbackByLedId = it.ledTestFeedbackByLedId + (mapping.ledId to feedbackText)
                )
            }
        }
    }

    private fun resolveLedHierarchy(
        mapping: EspMapping,
        estantes: List<Estante>,
        prateleiras: List<Prateleira>,
        posicoes: List<Posicao>
    ): Triple<String, String?, String?>? = when (mapping.kind) {
        MappingKind.ESTANTE -> {
            val est = estantes.find { it.ledId == mapping.ledId } ?: return null
            Triple(est.ledId, null, null)
        }
        MappingKind.PRATELEIRA -> {
            val prat = prateleiras.find { it.ledIdPonta == mapping.ledId } ?: return null
            val est = estantes.find { it.id == prat.estanteId } ?: return null
            Triple(est.ledId, prat.ledIdPonta, null)
        }
        MappingKind.POSICAO -> {
            val pos = posicoes.find { it.ledId == mapping.ledId } ?: return null
            val prat = prateleiras.find { it.id == pos.prateleiraId } ?: return null
            val est = estantes.find { it.id == prat.estanteId } ?: return null
            Triple(est.ledId, prat.ledIdPonta, pos.ledId)
        }
    }

    private fun onEspDevicesUpdated(devices: List<EspDevice>) {
        _uiState.update { current ->
            val nextListPhase = when {
                !current.isSupported -> EspListPhase.Unsupported
                devices.isNotEmpty() -> EspListPhase.Success
                current.isDiscovering -> EspListPhase.Loading
                current.listPhase is EspListPhase.Error -> current.listPhase
                else -> current.listPhase
            }
            val selected = devices.find { it.espId == current.selectedEspId }
            val currentOp = current.opState
            val isNowSynced = currentOp is EspConfigOpState.AppliedWaitingState &&
                selected?.config?.configRevision == currentOp.expectedRevision
            val syncedFromState = if (isNowSynced && currentOp is EspConfigOpState.AppliedWaitingState) {
                stateWaitJob?.cancel()
                feedbackController.triggerFeedback(FeedbackScenario.SUCCESS_BEEP_VIBRATE)
                EspConfigOpState.Synced(currentOp.expectedRevision, currentOp.diagnostics)
            } else {
                currentOp
            }
            val syncedConfig = selected?.config
            val next = current.copy(
                devices = devices,
                listPhase = nextListPhase,
                opState = syncedFromState,
                loadedConfigRevision = if (isNowSynced && syncedConfig != null) syncedConfig.configRevision else current.loadedConfigRevision,
                originalMappings = if (isNowSynced && syncedConfig != null) syncedConfig.mappings else current.originalMappings,
                draftMappings = if (isNowSynced && syncedConfig != null) syncedConfig.mappings else current.draftMappings
            )
            next.copy(violations = computeViolations(next))
        }
    }

    private fun computeViolations(state: EspManagementUiState): List<MappingViolation> {
        val espId = state.selectedEspId ?: return emptyList()
        if (!state.configReadInSession) return emptyList()
        val domain = DomainLedIds.from(state.estantes, state.prateleiras, state.posicoes)
        val knownEsps = state.devices
            .mapNotNull { dev -> dev.config?.mappings?.let { dev.espId to it } }
            .toMap()
        return EspContract.validateMappingsForPublish(
            espId = espId,
            mappings = state.draftMappings,
            channels = state.selectedChannels,
            domain = domain,
            knownEsps = knownEsps
        )
    }

    companion object {
        fun readableErrorCode(code: EspErrorCode?): String = when (code) {
            EspErrorCode.REVISION_MISMATCH -> "Revisão divergente (REVISION_MISMATCH): outro cliente alterou o ESP."
            EspErrorCode.INVALID_SCHEMA -> "Formato inválido (INVALID_SCHEMA)."
            EspErrorCode.LED_ID_CONFLICT -> "ID de LED duplicado no mesmo ESP (LED_ID_CONFLICT)."
            EspErrorCode.UNKNOWN_CHANNEL -> "Canal GPIO inexistente nas capacidades do ESP (UNKNOWN_CHANNEL)."
            EspErrorCode.CHANNEL_CONFLICT -> "Canal atribuído a mais de um LED (CHANNEL_CONFLICT)."
            EspErrorCode.PAYLOAD_TOO_LARGE -> "Configuração excede o limite de 2048 bytes (PAYLOAD_TOO_LARGE)."
            EspErrorCode.STORAGE_FAILURE -> "Falha ao gravar na memória NVS do ESP (STORAGE_FAILURE)."
            EspErrorCode.EXPIRED -> "Mensagem expirada antes da entrega (EXPIRED)."
            EspErrorCode.ESP_ID_MISMATCH -> "ID do ESP divergente do tópico (ESP_ID_MISMATCH)."
            EspErrorCode.INTERNAL_ERROR -> "Erro interno do ESP (INTERNAL_ERROR)."
            EspErrorCode.INVALID_CHANNEL_TABLE -> "Tabela de canais compilada no firmware é inválida (INVALID_CHANNEL_TABLE)."
            null -> "Erro reportado pelo ESP."
        }

        fun readableViolation(v: MappingViolation): String = when (v.type) {
            MappingViolationType.TOO_MANY_MAPPINGS -> "Máximo de ${EspContract.MAX_MAPPINGS} mapeamentos excedido."
            MappingViolationType.UNKNOWN_CHANNEL -> "Canal ${v.channelId ?: ""} não existe nas capacidades do ESP."
            MappingViolationType.LED_ID_DUPLICATED -> "LED ${v.ledId ?: ""} duplicado neste ESP."
            MappingViolationType.CHANNEL_DUPLICATED -> "Canal ${v.channelId ?: ""} atribuído a mais de um LED."
            MappingViolationType.UNKNOWN_LED_ID -> "LED ${v.ledId ?: ""} não existe no cadastro de endereços."
            MappingViolationType.KIND_MISMATCH -> "LED ${v.ledId ?: ""} pertence a outro tipo de endereço (Estante/Prateleira/Posição)."
            MappingViolationType.LED_ID_OWNED_BY_OTHER_ESP -> "LED ${v.ledId ?: ""} já está mapeado no ESP ${v.otherEspId ?: ""}."
        }
    }
}

