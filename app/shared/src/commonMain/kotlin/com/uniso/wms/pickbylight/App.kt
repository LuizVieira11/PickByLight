package com.uniso.wms.pickbylight

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.uniso.wms.pickbylight.data.mqtt.MqttServiceProvider
import com.uniso.wms.pickbylight.data.repository.WmsRepositoryProvider
import com.uniso.wms.pickbylight.ui.components.MqttDisconnectedBanner
import com.uniso.wms.pickbylight.ui.navigation.Screen
import com.uniso.wms.pickbylight.ui.responsive.ResponsiveContainer
import com.uniso.wms.pickbylight.ui.screens.ajustes.AjustesScreen
import com.uniso.wms.pickbylight.ui.screens.catalogo.CatalogoScreen
import com.uniso.wms.pickbylight.ui.screens.dashboard.DashboardScreen
import com.uniso.wms.pickbylight.ui.screens.enderecos.EnderecosScreen
import com.uniso.wms.pickbylight.ui.screens.historico.HistoricoScreen
import com.uniso.wms.pickbylight.ui.screens.recebimento.RecebimentoScreen
import com.uniso.wms.pickbylight.ui.screens.separacao.SeparacaoScreen
import com.uniso.wms.pickbylight.ui.theme.WmsTheme
import com.uniso.wms.pickbylight.util.FeedbackController

@Composable
fun App(
    onCloseApp: (() -> Unit)? = null
) {
    WmsTheme {
        ResponsiveContainer { windowSize ->
            val navController = rememberNavController()
            val repository = remember { WmsRepositoryProvider.getRepository() }
            val mqttService = remember { MqttServiceProvider.getMqttService() }
            val feedbackController = remember { FeedbackController() }

            val mqttState by mqttService.connectionState.collectAsState()
            var passedTagUidForEnderecos by remember { mutableStateOf<String?>(null) }

            // Safe popBackStack que nunca esvazia a pilha (evita tela branca ao voltar rápido)
            val safePopBackStack: () -> Unit = {
                if (navController.previousBackStackEntry != null &&
                    navController.currentDestination?.route != Screen.Dashboard.route) {
                    navController.popBackStack()
                }
            }

            // Conexão automática ao iniciar o aplicativo em background
            LaunchedEffect(Unit) {
                withContext(Dispatchers.Default) {
                    mqttService.connect()
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeContentPadding()
                ) {
                    // Banner persistente de Broker MQTT desconectado (Seção 5.1)
                    MqttDisconnectedBanner(connectionState = mqttState)

                    Box(modifier = Modifier.fillMaxSize()) {
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Dashboard.route,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable(Screen.Dashboard.route) {
                                DashboardScreen(
                                    windowSize = windowSize,
                                    repository = repository,
                                    mqttService = mqttService,
                                    feedbackController = feedbackController,
                                    onNavigateRecebimento = { navController.navigate(Screen.Recebimento.route) },
                                    onNavigateSeparacao = { navController.navigate(Screen.Separacao.route) },
                                    onNavigateEnderecos = {
                                        passedTagUidForEnderecos = null
                                        navController.navigate(Screen.Enderecos.route)
                                    },
                                    onNavigateCatalogo = { navController.navigate(Screen.Catalogo.route) },
                                    onNavigateHistorico = { navController.navigate(Screen.Historico.route) },
                                    onNavigateAjustes = { navController.navigate(Screen.Ajustes.route) },
                                    onCloseApp = onCloseApp
                                )
                            }

                            composable(Screen.Recebimento.route) {
                                RecebimentoScreen(
                                    repository = repository,
                                    feedbackController = feedbackController,
                                    onNavigateBack = safePopBackStack,
                                    onNavigateEnderecosComUid = { uid ->
                                        passedTagUidForEnderecos = uid
                                        navController.navigate(Screen.Enderecos.route)
                                    }
                                )
                            }

                            composable(Screen.Separacao.route) {
                                SeparacaoScreen(
                                    repository = repository,
                                    mqttService = mqttService,
                                    feedbackController = feedbackController,
                                    onNavigateBack = safePopBackStack
                                )
                            }

                            composable(Screen.Enderecos.route) {
                                EnderecosScreen(
                                    repository = repository,
                                    mqttService = mqttService,
                                    feedbackController = feedbackController,
                                    initialTagUid = passedTagUidForEnderecos,
                                    onNavigateBack = safePopBackStack
                                )
                            }

                            composable(Screen.Catalogo.route) {
                                CatalogoScreen(
                                    repository = repository,
                                    feedbackController = feedbackController,
                                    onNavigateBack = safePopBackStack,
                                    onNavigateEnderecosComUid = { uid ->
                                        passedTagUidForEnderecos = uid
                                        navController.navigate(Screen.Enderecos.route)
                                    }
                                )
                            }

                            composable(Screen.Historico.route) {
                                HistoricoScreen(
                                    repository = repository,
                                    onNavigateBack = safePopBackStack,
                                    onNavigateRecebimento = { navController.navigate(Screen.Recebimento.route) }
                                )
                            }

                            composable(Screen.Ajustes.route) {
                                AjustesScreen(
                                    mqttService = mqttService,
                                    repository = repository,
                                    feedbackController = feedbackController,
                                    onNavigateBack = safePopBackStack
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}