package com.uniso.wms.pickbylight.ui.screens.esps

import app.cash.turbine.test
import com.uniso.wms.pickbylight.data.esp.ConfigLock
import com.uniso.wms.pickbylight.data.esp.EspConfigCoordinator
import com.uniso.wms.pickbylight.data.esp.EspContract
import com.uniso.wms.pickbylight.data.esp.MappingViolationType
import com.uniso.wms.pickbylight.data.esp.TEST_ESP
import com.uniso.wms.pickbylight.data.esp.valid
import com.uniso.wms.pickbylight.data.mqtt.EspManagementTimings
import com.uniso.wms.pickbylight.data.mqtt.EspPresence
import com.uniso.wms.pickbylight.data.mqtt.FakeMqttService
import com.uniso.wms.pickbylight.data.mqtt.VirtualEsp
import com.uniso.wms.pickbylight.data.mqtt.model.EspChannel
import com.uniso.wms.pickbylight.data.mqtt.model.EspErrorCode
import com.uniso.wms.pickbylight.data.mqtt.model.EspMapping
import com.uniso.wms.pickbylight.data.mqtt.model.EspOnlineStatus
import com.uniso.wms.pickbylight.data.mqtt.model.MappingKind
import com.uniso.wms.pickbylight.data.mqtt.model.MqttTopics
import com.uniso.wms.pickbylight.data.mqtt.model.OfflineReason
import com.uniso.wms.pickbylight.data.repository.InMemoryWmsRepository
import com.uniso.wms.pickbylight.util.FeedbackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Etapa 5b (a-k): testes do [EspManagementViewModel] com [FakeMqttService], [VirtualEsp], Turbine e [ConfigLock] isolado. */
@OptIn(ExperimentalCoroutinesApi::class)
class EspManagementViewModelTest {

    private val esp1Id = TEST_ESP
    private val esp2Id = "esp-000001"
    private val esp3Id = "esp-000002"
    private val esp4Id = "esp-000003"
    private val channels = listOf(
        EspChannel("CH01", 4, true),
        EspChannel("CH02", 13, true),
        EspChannel("CH03", 16, true)
    )
    private val mapPosA101 = EspMapping("POS_A1_01", MappingKind.POSICAO, "CH03")
    private val mapPosA102 = EspMapping("POS_A1_02", MappingKind.POSICAO, "CH02")
    private val mapEstA = EspMapping("EST_A_LED1", MappingKind.ESTANTE, "CH01")
    private val mapPratA1 = EspMapping("PRAT_A1_LED1", MappingKind.PRATELEIRA, "CH02")

    private lateinit var repository: InMemoryWmsRepository
    private lateinit var mqtt: FakeMqttService
    private lateinit var lock: ConfigLock
    private lateinit var feedback: FeedbackController

    @BeforeTest
    fun setup() = runTest {
        repository = InMemoryWmsRepository()
        repository.resetarDadosDemo()
        mqtt = FakeMqttService()
        lock = ConfigLock()
        feedback = FeedbackController()
    }

    private fun createVm(
        scope: CoroutineScope,
        isSupported: Boolean = true
    ) = EspManagementViewModel(
        mqttService = mqtt,
        repository = repository,
        feedbackController = feedback,
        coordinator = EspConfigCoordinator(mqtt, lock),
        configLock = lock,
        coroutineScope = scope,
        isSupported = isSupported
    )

    /** Avança o delay(1) de SUBACK simulado em FakeMqttService.ensureV1Subscribed() sem vencer timers de 3s/5s. */
    private fun kotlinx.coroutines.test.TestScope.settle() {
        runCurrent()
        advanceTimeBy(5L)
        runCurrent()
    }

    @Test
    fun listaTransitionsFromLoadingToEmptyAfterDiscoveryWindowWhenNoEspResponds() = runTest {
        val vm = createVm(backgroundScope)

        vm.uiState.map { it.listPhase }.distinctUntilChanged().test {
            assertEquals(EspListPhase.Loading, awaitItem())
            vm.iniciarLista()
            advanceTimeBy(EspManagementTimings.DISCOVERY_WINDOW_MS - 100L)
            expectNoEvents()
            advanceTimeBy(200L)
            assertEquals(EspListPhase.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun listaShowsOnlineStaleOfflineAndConfigInvalidWithErrorCode() = runTest {
        val vOnline = VirtualEsp(esp1Id, channels, listOf(mapPosA101), "1111aaaa", 4L)
        val vStale = VirtualEsp(esp2Id, channels, emptyList(), "2222bbbb", 1L)
        val vOffline = VirtualEsp(esp3Id, channels, emptyList(), "3333cccc", 2L)
        val vInvalid = VirtualEsp(esp4Id, channels, emptyList(), "4444dddd", 3L)

        mqtt.addVirtualEsp(vOnline, publishRetained = false)
        mqtt.seedRetainedStatus(vStale.status())
        mqtt.seedRetainedOffline(vOffline.offlineStatus(OfflineReason.LWT))
        mqtt.seedRetainedStatus(
            vInvalid.status().copy(
                status = EspOnlineStatus.CONFIG_INVALID,
                errorCode = EspErrorCode.INVALID_CHANNEL_TABLE
            )
        )

        val vm = createVm(backgroundScope)
        vm.iniciarLista()
        settle()

        val state = vm.uiState.value
        assertEquals(EspListPhase.Success, state.listPhase)
        val byId = state.devices.associateBy { it.espId }
        assertEquals(EspPresence.ONLINE, byId[esp1Id]?.presence)
        assertEquals(EspPresence.STALE, byId[esp2Id]?.presence)
        assertEquals(EspPresence.OFFLINE, byId[esp3Id]?.presence)
        assertEquals(EspOnlineStatus.CONFIG_INVALID, byId[esp4Id]?.reportedStatus)
        assertEquals(EspErrorCode.INVALID_CHANNEL_TABLE, byId[esp4Id]?.errorCode)
    }

    @Test
    fun detalheUnlocksEditingOnConfigOkLocksOnTimeoutAndHandlesConfigInvalidCodes() = runTest {
        val v1 = VirtualEsp(esp1Id, channels, listOf(mapPosA101), "1111aaaa", 4L)
        mqtt.addVirtualEsp(v1, publishRetained = true)
        val vm = createVm(backgroundScope)

        // 1. obterConfig Ok -> libera edição (e abrirDetalhe NÃO publica discovery)
        vm.abrirDetalhe(esp1Id)
        settle()
        assertTrue(mqtt.sentManagement.none { it.first == MqttTopics.V1_DISCOVERY_REQUEST })
        assertTrue(vm.uiState.value.configReadInSession)
        assertTrue(vm.uiState.value.canEditMappings)
        assertIs<EspDetailReadState.Loaded>(vm.uiState.value.detailReadState)

        // 2. Correção 2: STORAGE_FAILURE permite editar e salvar para recuperar NVS
        mqtt.simulateIncomingStatus(
            EspContract.encodeStatusOnline(
                v1.status().copy(status = EspOnlineStatus.CONFIG_INVALID, errorCode = EspErrorCode.STORAGE_FAILURE)
            ),
            esp1Id
        )
        settle()
        assertTrue(vm.uiState.value.canEditMappings, "STORAGE_FAILURE deve permitir salvar config/set de recuperação")
        vm.adicionarMapping(MappingKind.POSICAO, "POS_A1_02", "CH02")
        assertTrue(vm.uiState.value.canSave)
        vm.salvarConfig()
        settle()
        assertIs<EspConfigOpState.Synced>(vm.uiState.value.opState)

        // 3. Correção 2: INVALID_CHANNEL_TABLE bloqueia edição e salvamento
        mqtt.simulateIncomingStatus(
            EspContract.encodeStatusOnline(
                v1.status().copy(status = EspOnlineStatus.CONFIG_INVALID, errorCode = EspErrorCode.INVALID_CHANNEL_TABLE)
            ),
            esp1Id
        )
        settle()
        assertFalse(vm.uiState.value.canEditMappings, "INVALID_CHANNEL_TABLE deve bloquear edição")
        assertFalse(vm.uiState.value.canSave)

        // 4. Timeout em obterConfig -> "Configuração não lida" e edição bloqueada
        mqtt.silentEspIds.add(esp1Id)
        vm.abrirDetalhe(esp1Id)
        advanceTimeBy(EspManagementTimings.CONFIG_TIMEOUT_MS + 20L)
        settle()
        val notRead = assertIs<EspDetailReadState.NotRead>(vm.uiState.value.detailReadState)
        assertEquals("Configuração não lida", notRead.message)
        assertFalse(vm.uiState.value.configReadInSession)
        assertFalse(vm.uiState.value.canEditMappings)
    }

    @Test
    fun salvarConfigTransitionsWaitingResultAppliedWaitingStateAndSyncedWhenStateArrives() = runTest {
        val v1 = VirtualEsp(esp1Id, channels, listOf(mapPosA101), "1111aaaa", 4L)
        mqtt.addVirtualEsp(v1, publishRetained = true)
        // Entrega config/result OK, mas atrasa o config/state para observar WAITING_RESULT -> APPLIED_WAITING_STATE -> SYNCED
        mqtt.onPublishConfigSet = { set, payload ->
            val res = v1.handleConfigSet(set.requestId, payload)
            simulateIncomingConfigResult(EspContract.encodeConfigResult(res), set.espId)
        }

        val vm = createVm(backgroundScope)
        vm.abrirDetalhe(esp1Id)
        settle()
        vm.adicionarMapping(MappingKind.POSICAO, "POS_A1_02", "CH02")
        settle()

        vm.uiState.map { it.opState }.distinctUntilChanged().test {
            assertEquals(EspConfigOpState.Idle, awaitItem())
            vm.salvarConfig()
            assertIs<EspConfigOpState.WaitingResult>(awaitItem())
            val applied = assertIs<EspConfigOpState.AppliedWaitingState>(awaitItem())
            assertEquals(5L, applied.expectedRevision)
            assertFalse(applied.timedOutWaitingState)

            mqtt.simulateIncomingConfigState(EspContract.encodeConfigState(v1.state()), esp1Id, retained = false)
            val synced = assertIs<EspConfigOpState.Synced>(awaitItem())
            assertEquals(5L, synced.revision)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun salvarConfigStaysAppliedWaitingStateAfter5sAndManualReadCallsObterConfig() = runTest {
        val v1 = VirtualEsp(esp1Id, channels, listOf(mapPosA101), "1111aaaa", 4L)
        mqtt.addVirtualEsp(v1, publishRetained = true)
        mqtt.onPublishConfigSet = { set, payload ->
            val res = v1.handleConfigSet(set.requestId, payload)
            simulateIncomingConfigResult(EspContract.encodeConfigResult(res), set.espId)
        }

        val vm = createVm(backgroundScope)
        vm.abrirDetalhe(esp1Id)
        settle()
        vm.adicionarMapping(MappingKind.POSICAO, "POS_A1_02", "CH02")
        vm.salvarConfig()
        settle()

        val initialApplied = assertIs<EspConfigOpState.AppliedWaitingState>(vm.uiState.value.opState)
        assertFalse(initialApplied.timedOutWaitingState)

        advanceTimeBy(EspManagementTimings.CONFIG_TIMEOUT_MS + 20L)
        settle()
        val after5s = assertIs<EspConfigOpState.AppliedWaitingState>(vm.uiState.value.opState)
        assertTrue(after5s.timedOutWaitingState, "após 5 s sem state continua aguardando estado e habilita leitura manual")

        val getsBefore = mqtt.sentManagement.count { it.first == MqttTopics.espConfigGet(esp1Id) }
        vm.lerConfiguracao(esp1Id)
        settle()
        val getsAfter = mqtt.sentManagement.count { it.first == MqttTopics.espConfigGet(esp1Id) }
        assertEquals(getsBefore + 1, getsAfter)
        val synced = assertIs<EspConfigOpState.Synced>(vm.uiState.value.opState)
        assertEquals(5L, synced.revision)
    }

    @Test
    fun salvarConfigOnRevisionMismatchShowsRejectedAndReloadRecovers() = runTest {
        val v1 = VirtualEsp(esp1Id, channels, listOf(mapPosA101), "1111aaaa", 4L)
        mqtt.addVirtualEsp(v1, publishRetained = true)
        val vm = createVm(backgroundScope)

        vm.abrirDetalhe(esp1Id)
        settle()
        assertEquals(4L, vm.uiState.value.loadedConfigRevision)

        // Outro cliente altera o ESP diretamente para a revisão 5
        v1.handleConfigSet(
            "923e4567-e89b-12d3-a456-426614174000",
            EspContract.encodeConfigSet(
                com.uniso.wms.pickbylight.data.mqtt.model.ConfigSet(
                    schemaVersion = EspContract.SCHEMA_VERSION,
                    espId = esp1Id,
                    requestId = "923e4567-e89b-12d3-a456-426614174000",
                    expectedRevision = 4L,
                    mappings = listOf(mapEstA)
                )
            ).valid()
        )

        // Usuário tenta salvar ainda com expected_revision = 4
        vm.adicionarMapping(MappingKind.POSICAO, "POS_A1_02", "CH02")
        vm.salvarConfig()
        settle()

        val rejected = assertIs<EspConfigOpState.Rejected>(vm.uiState.value.opState)
        assertEquals(EspErrorCode.REVISION_MISMATCH, rejected.errorCode)

        // Esclarecimento 4: "Recarregar configuração" substitui rascunho e exibe a mensagem exigida
        vm.recarregarAposConflitoRevisao()
        settle()
        assertEquals(5L, vm.uiState.value.loadedConfigRevision)
        assertEquals(listOf(mapEstA), vm.uiState.value.draftMappings)
        assertEquals(
            "A configuração do ESP mudou. Suas alterações foram descartadas e a versão atual foi carregada.",
            vm.uiState.value.infoMessage
        )
    }

    @Test
    fun salvarConfigTimeoutRetryReusesSameRequestIdAndNewEditGeneratesNewRequestId() = runTest {
        val v1 = VirtualEsp(esp1Id, channels, listOf(mapPosA101), "1111aaaa", 4L)
        mqtt.addVirtualEsp(v1, publishRetained = true)
        val vm = createVm(backgroundScope)

        vm.abrirDetalhe(esp1Id)
        settle()
        vm.adicionarMapping(MappingKind.POSICAO, "POS_A1_02", "CH02")

        // 1ª tentativa: ESP silencioso -> Timeout em 5 s
        mqtt.silentEspIds.add(esp1Id)
        vm.salvarConfig()
        advanceTimeBy(EspManagementTimings.CONFIG_TIMEOUT_MS + 20L)
        settle()
        assertIs<EspConfigOpState.Timeout>(vm.uiState.value.opState)

        // "Tentar de novo" reenvia com o MESMO request_id
        mqtt.silentEspIds.remove(esp1Id)
        vm.tentarNovamenteSalvar()
        settle()
        assertIs<EspConfigOpState.Synced>(vm.uiState.value.opState)

        val setPayloads = mqtt.sentManagement
            .filter { it.first == MqttTopics.espConfigSet(esp1Id) }
            .map { EspContract.parseConfigSet(it.second, esp1Id).valid() }
        assertEquals(2, setPayloads.size)
        assertEquals(setPayloads[0].requestId, setPayloads[1].requestId, "retry após Timeout deve reutilizar o mesmo request_id")

        // Nova edição gera um request_id novo
        vm.adicionarMapping(MappingKind.ESTANTE, "EST_A_LED1", "CH01")
        vm.salvarConfig()
        settle()
        val thirdSet = mqtt.sentManagement
            .filter { it.first == MqttTopics.espConfigSet(esp1Id) }
            .map { EspContract.parseConfigSet(it.second, esp1Id).valid() }
            .last()
        assertNotEquals(setPayloads[0].requestId, thirdSet.requestId, "nova edição deve gerar request_id novo")
    }

    @Test
    fun validationBlocksSaveWithoutPublishingForOtherEspKindMismatchUnknownLedDuplicateAndUnknownChannel() = runTest {
        val v1 = VirtualEsp(esp1Id, channels, listOf(mapPosA101), "1111aaaa", 4L)
        val v2 = VirtualEsp(esp2Id, channels, listOf(mapEstA), "2222bbbb", 1L)
        mqtt.addVirtualEsp(v1, publishRetained = true)
        mqtt.addVirtualEsp(v2, publishRetained = true)
        val vm = createVm(backgroundScope)

        vm.abrirDetalhe(esp1Id)
        settle()
        val setsBefore = mqtt.sentManagement.count { it.first == MqttTopics.espConfigSet(esp1Id) }

        vm.definirRascunho(
            listOf(
                EspMapping("EST_A_LED1", MappingKind.ESTANTE, "CH01"),
                EspMapping("PRAT_A1_LED1", MappingKind.POSICAO, "CH01"),
                EspMapping("LED_NAO_EXISTE", MappingKind.POSICAO, "CH99")
            )
        )
        settle()

        val violationTypes = vm.uiState.value.violations.map { it.type }.toSet()
        assertTrue(MappingViolationType.LED_ID_OWNED_BY_OTHER_ESP in violationTypes)
        assertTrue(MappingViolationType.KIND_MISMATCH in violationTypes)
        assertTrue(MappingViolationType.UNKNOWN_LED_ID in violationTypes)
        assertTrue(MappingViolationType.CHANNEL_DUPLICATED in violationTypes)
        assertTrue(MappingViolationType.UNKNOWN_CHANNEL in violationTypes)

        assertFalse(vm.uiState.value.canSave)
        vm.salvarConfig()
        settle()
        assertEquals(setsBefore, mqtt.sentManagement.count { it.first == MqttTopics.espConfigSet(esp1Id) })
    }

    @Test
    fun activeColetaInConfigLockBlocksSaveAndLedTestWithoutPublishing() = runTest {
        val v1 = VirtualEsp(esp1Id, channels, listOf(mapPosA101), "1111aaaa", 4L)
        mqtt.addVirtualEsp(v1, publishRetained = true)
        val vm = createVm(backgroundScope)

        vm.abrirDetalhe(esp1Id)
        settle()
        vm.adicionarMapping(MappingKind.POSICAO, "POS_A1_02", "CH02")
        assertTrue(vm.uiState.value.canSave)

        // Inicia coleta no ConfigLock
        assertTrue(lock.tryStartColeta())
        settle()

        assertTrue(vm.uiState.value.isColetaActive)
        assertFalse(vm.uiState.value.canSave)
        assertFalse(vm.uiState.value.canTestLed)

        val setsBefore = mqtt.sentManagement.count { it.first == MqttTopics.espConfigSet(esp1Id) }
        vm.salvarConfig()
        vm.testarMapping(mapPosA101)
        settle()

        assertEquals(setsBefore, mqtt.sentManagement.count { it.first == MqttTopics.espConfigSet(esp1Id) })
        assertTrue(mqtt.sentCommands.isEmpty())
        assertEquals("Coleta em andamento: teste bloqueado", vm.uiState.value.ledTestFeedbackByLedId["POS_A1_01"])
        lock.endColeta()
    }

    @Test
    fun testarLedSendsHierarchyForEstantePrateleiraPosicaoAndReportsOkErroTimeout() = runTest {
        val v1 = VirtualEsp(
            esp1Id,
            channels,
            listOf(mapEstA, mapPratA1, mapPosA101),
            "1111aaaa",
            4L
        )
        mqtt.addVirtualEsp(v1, publishRetained = true)
        val vm = createVm(backgroundScope)
        vm.abrirDetalhe(esp1Id)
        runCurrent()

        // 1. ESTANTE -> só estante_led_id
        vm.testarMapping(mapEstA)
        runCurrent()
        val cmdEst = mqtt.sentCommands.last()
        assertTrue(cmdEst.contains("\"estante_led_id\":\"EST_A_LED1\""))
        assertTrue(cmdEst.contains("\"prateleira_led_id\":null"))
        assertTrue(cmdEst.contains("\"posicao_led_id\":null"))
        assertEquals("LED confirmado", vm.uiState.value.ledTestFeedbackByLedId["EST_A_LED1"])

        // 2. PRATELEIRA -> estante_led_id + prateleira_led_id
        vm.testarMapping(mapPratA1)
        runCurrent()
        val cmdPrat = mqtt.sentCommands.last()
        assertTrue(cmdPrat.contains("\"estante_led_id\":\"EST_A_LED1\""))
        assertTrue(cmdPrat.contains("\"prateleira_led_id\":\"PRAT_A1_LED1\""))
        assertTrue(cmdPrat.contains("\"posicao_led_id\":null"))
        assertEquals("LED confirmado", vm.uiState.value.ledTestFeedbackByLedId["PRAT_A1_LED1"])

        // 3. POSICAO -> estante_led_id + prateleira_led_id + posicao_led_id
        vm.testarMapping(mapPosA101)
        runCurrent()
        val cmdPos = mqtt.sentCommands.last()
        assertTrue(cmdPos.contains("\"estante_led_id\":\"EST_A_LED1\""))
        assertTrue(cmdPos.contains("\"prateleira_led_id\":\"PRAT_A1_LED1\""))
        assertTrue(cmdPos.contains("\"posicao_led_id\":\"POS_A1_01\""))
        assertEquals("LED confirmado", vm.uiState.value.ledTestFeedbackByLedId["POS_A1_01"])

        // 4. Timeout -> "Sem confirmação do ESP"
        mqtt.silentEspIds.add(esp1Id)
        vm.testarMapping(mapPosA101)
        advanceTimeBy(EspManagementTimings.ACK_TIMEOUT_MS + 10L)
        runCurrent()
        assertEquals("Sem confirmação do ESP", vm.uiState.value.ledTestFeedbackByLedId["POS_A1_01"])

        // 5. Erro -> código do erro exibido
        val errService = object : com.uniso.wms.pickbylight.data.mqtt.MqttService by mqtt {
            override suspend fun testarLedComConfirmacao(
                estanteLedId: String,
                prateleiraLedId: String?,
                posicaoLedId: String?,
                cor: String,
                duracaoMs: Int
            ) = Result.success(com.uniso.wms.pickbylight.data.mqtt.MqttOutcome.Erro(EspErrorCode.UNKNOWN_CHANNEL))
        }
        val errVm = EspManagementViewModel(
            mqttService = errService,
            repository = repository,
            feedbackController = feedback,
            coordinator = EspConfigCoordinator(errService, lock),
            configLock = lock,
            coroutineScope = backgroundScope
        )
        runCurrent()
        errVm.testarMapping(mapPosA101)
        runCurrent()
        assertEquals("Erro no ESP (UNKNOWN_CHANNEL)", errVm.uiState.value.ledTestFeedbackByLedId["POS_A1_01"])
    }

    @Test
    fun webUnsupportedFlagShowsUnavailableWithoutCallingMqttService() = runTest {
        val vm = createVm(backgroundScope, isSupported = false)

        assertEquals(EspListPhase.Unsupported, vm.uiState.value.listPhase)
        assertFalse(vm.uiState.value.isSupported)

        vm.iniciarLista()
        vm.abrirDetalhe(esp1Id)
        vm.testarMapping(mapPosA101)
        runCurrent()

        assertTrue(mqtt.sentManagement.isEmpty())
        assertTrue(mqtt.sentCommands.isEmpty())
        assertTrue(mqtt.protocolEvents.isEmpty())
    }
}
