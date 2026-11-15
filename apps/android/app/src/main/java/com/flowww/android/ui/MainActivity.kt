package com.flowww.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import com.flowww.android.ui.screens.PairingScreen
import com.flowww.android.ui.screens.SettingsScreen
import com.flowww.android.ui.screens.StagedContentScreen
import com.flowww.android.ui.screens.TransferDetailsScreen
import com.flowww.android.ui.screens.TransferHistoryScreen
import com.flowww.android.ui.screens.TrustedDevicesScreen
import com.flowww.android.ui.theme.FlowwwTheme
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
    val navController = rememberNavController()
    val stagedVm: StagedContentViewModel = viewModel(factory = AppViewModelFactory { StagedContentViewModel(container.stagedContentRepository, container.trustedDeviceRepository) })
    val settingsVm: SettingsViewModel = viewModel(factory = AppViewModelFactory { SettingsViewModel(container.settingsRepository) })
    val nearbyVm: NearbyDevicesViewModel = viewModel(factory = AppViewModelFactory { NearbyDevicesViewModel(container.discoveryRepository) })
    val trustedVm: TrustedDevicesViewModel = viewModel(factory = AppViewModelFactory { TrustedDevicesViewModel(container.trustedDeviceRepository) })
    val pairingVm: PairingViewModel = viewModel(factory = AppViewModelFactory { PairingViewModel(container.pairingRepository) })
    val transferVm: TransferViewModel = viewModel(factory = AppViewModelFactory { TransferViewModel(container.transferRepository) })

    LaunchedEffect(openRoute) {
        if (openRoute == Screen.Staged.route) {
            navController.navigate(Screen.Staged.route) { launchSingleTop = true }
        }
    }

    FlowwwTheme {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        val showBottomBar = currentDestination?.route in bottomNavItems.map { it.screen.route }
        Scaffold(
            containerColor = Color(0xFF101010),
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
                    GestureSessionScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = Screen.TransferDetails.route,
                    arguments = listOf(navArgument("transferId") { type = NavType.StringType })
                ) { backStackEntry ->
                    TransferDetailsScreen(
                        transferId = backStackEntry.arguments?.getString("transferId").orEmpty(),
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
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}