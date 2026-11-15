package com.flowww.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.flowww.android.ui.navigation.Screen
import com.flowww.android.ui.navigation.bottomNavItems
import com.flowww.android.ui.components.FlowwwBottomBar
import com.flowww.android.ui.screens.AboutScreen
import com.flowww.android.ui.screens.ActiveTransfersScreen
import com.flowww.android.ui.screens.GestureDiagnosticsRoute
import com.flowww.android.ui.screens.GestureSessionScreen
import com.flowww.android.ui.screens.IncomingTransferApprovalScreen
import com.flowww.android.ui.screens.NearbyDevicesScreen
import com.flowww.android.sensor.GestureController
import com.flowww.android.ui.screens.PairingScreen
import com.flowww.android.ui.screens.SettingsScreen
import com.flowww.android.ui.screens.StagedContentScreen
import com.flowww.android.ui.screens.TransferDetailsScreen
import com.flowww.android.ui.screens.TransferHistoryScreen
import com.flowww.android.ui.screens.TrustedDevicesScreen
import com.flowww.android.ui.theme.FlowwwTheme
import com.flowww.android.ui.theme.flowwwColors
import com.flowww.android.ui.theme.resolveDarkTheme
import com.flowww.android.ui.viewmodel.AppViewModelFactory
import com.flowww.android.ui.viewmodel.NearbyDevicesViewModel
import com.flowww.android.ui.viewmodel.PairingViewModel
import com.flowww.android.ui.viewmodel.SettingsViewModel
import com.flowww.android.ui.viewmodel.StagedContentViewModel
import com.flowww.android.ui.viewmodel.TransferViewModel
import com.flowww.android.ui.viewmodel.TrustedDevicesViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FlowwwApp(intent?.getStringExtra(EXTRA_OPEN_ROUTE)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // recomposition will read updated intent in a fuller impl; route handled on launch
    }

    companion object {
        const val EXTRA_OPEN_ROUTE = "open_route"
    }
}

@Composable
fun FlowwwApp(openRoute: String? = null) {
    val container = rememberAppContainer()
    val gestureController = remember(container) {
        GestureController(container.connectionService, container.stagedStore, container.appScope)
    }
    LaunchedEffect(container) {
        val windows = runCatching { container.connectionService.getTrustedDevices() }
            .getOrDefault(emptyList())
            .filter { it.info.platform == com.flowww.android.connection.Platform.Windows }
        if (windows.size == 1) gestureController.selectTarget(windows.single().info.deviceId)
    }
    val pendingOffers by container.transfersBridge.pendingOffers.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val stagedVm: StagedContentViewModel = viewModel(
        factory = AppViewModelFactory {
            StagedContentViewModel(
                container.stagedContentRepository,
                container.trustedDeviceRepository,
                container.localContentGateway,
                container.contentHandoff,
                container.operationRepository
            )
        }
    )
    val settingsVm: SettingsViewModel = viewModel(
        factory = AppViewModelFactory {
            SettingsViewModel(
                container.settingsRepository,
                container.stagedContentRepository,
                container.operationRepository
            )
        }
    )
    val nearbyVm: NearbyDevicesViewModel = viewModel(factory = AppViewModelFactory { NearbyDevicesViewModel(container.discoveryRepository) })
    val trustedVm: TrustedDevicesViewModel = viewModel(factory = AppViewModelFactory { TrustedDevicesViewModel(container.trustedDeviceRepository) })
    val pairingVm: PairingViewModel = viewModel(factory = AppViewModelFactory { PairingViewModel(container.pairingRepository) })
    val transferVm: TransferViewModel = viewModel(factory = AppViewModelFactory { TransferViewModel(container.operationRepository) })

    val settingsState by settingsVm.uiState.collectAsStateWithLifecycle()
    val systemInDarkTheme = isSystemInDarkTheme()
    val darkTheme = settingsState.settings.themeMode.resolveDarkTheme(systemInDarkTheme)

    LaunchedEffect(openRoute) {
        if (openRoute == Screen.Staged.route) {
            navController.navigate(Screen.Staged.route) { launchSingleTop = true }
        }
    }

    FlowwwTheme(darkTheme = darkTheme) {
        val colors = flowwwColors
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        val showBottomBar = currentDestination?.route in bottomNavItems.map { it.screen.route }
        Scaffold(
            containerColor = colors.background,
            bottomBar = {
                if (showBottomBar) {
                    FlowwwBottomBar(
                        currentRoute = currentDestination?.route,
                        onNavigate = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Nearby.route,
                modifier = Modifier.padding(innerPadding).statusBarsPadding()
            ) {
                composable(Screen.Nearby.route) {
                    NearbyDevicesScreen(
                        viewModel = nearbyVm,
                        onOpenPairing = { navController.navigate(Screen.Pairing.route) },
                        onOpenDevice = { _ -> navController.navigate(Screen.Pairing.route) }
                    )
                }
                composable(Screen.Trusted.route) {
                    TrustedDevicesScreen(
                        viewModel = trustedVm,
                        onOpenPairing = { navController.navigate(Screen.Pairing.route) }
                    )
                }
                composable(Screen.Staged.route) {
                    StagedContentScreen(viewModel = stagedVm)
                }
                composable(Screen.Transfers.route) {
                    ActiveTransfersScreen(
                        viewModel = transferVm,
                        onOpenHistory = { navController.navigate(Screen.History.route) },
                        onOpenDetails = { id -> navController.navigate(Screen.TransferDetails.create(id)) }
                    )
                }
                composable(Screen.History.route) {
                    TransferHistoryScreen(
                        viewModel = transferVm,
                        onBack = { navController.popBackStack() },
                        onOpenDetails = { id -> navController.navigate(Screen.TransferDetails.create(id)) }
                    )
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        viewModel = settingsVm,
                        onOpenGestureDiagnostics = { navController.navigate(Screen.GestureDiagnostics.route) },
                        onOpenAbout = { navController.navigate(Screen.About.route) },
                        onOpenHistory = { navController.navigate(Screen.History.route) }
                    )
                }
                composable(Screen.Pairing.route) {
                    PairingScreen(viewModel = pairingVm, onBack = { navController.popBackStack() })
                }
                composable(Screen.About.route) {
                    AboutScreen(onBack = { navController.popBackStack() })
                }
                composable(Screen.GestureDiagnostics.route) {
                    GestureDiagnosticsRoute(onBack = { navController.popBackStack() }, onOpenSession = { navController.navigate(Screen.GestureSession.route) })
                }
                composable(Screen.GestureSession.route) {
                    GestureSessionScreen(
                        onBack = { navController.popBackStack() },
                        onGestureSend = {},
                        onGestureReceive = {},
                        onGestureCancel = {},
                        onGestureEvent = gestureController::handleEvent,
                        onSessionActiveChanged = { active ->
                            if (active) gestureController.arm() else gestureController.disarm()
                        }
                    )
                }
                composable(
                    route = Screen.TransferDetails.route,
                    arguments = listOf(navArgument("transferId") { type = NavType.StringType })
                ) { backStackEntry ->
                    TransferDetailsScreen(
                        operationId = backStackEntry.arguments?.getString("transferId").orEmpty(),
                        viewModel = transferVm,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = Screen.IncomingApproval.route,
                    arguments = listOf(navArgument("transferId") { type = NavType.StringType })
                ) { backStackEntry ->
                    IncomingTransferApprovalScreen(
                        transferId = backStackEntry.arguments?.getString("transferId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        offer = pendingOffers.firstOrNull {
                            it.transferId == backStackEntry.arguments?.getString("transferId")
                        },
                        onRespond = { accepted ->
                            val id = backStackEntry.arguments?.getString("transferId").orEmpty()
                            container.appScope.launch {
                                runCatching { container.transfersBridge.respondToOffer(id, accepted) }
                                navController.popBackStack()
                            }
                        }
                    )
                }
            }
        }
    }
}
