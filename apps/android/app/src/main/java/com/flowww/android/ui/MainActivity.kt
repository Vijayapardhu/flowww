package com.flowww.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.material3.navigation.NavigationBarItem
import androidx.compose.material3.navigation.rememberNavigationBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import com.flowww.android.ui.components.NavigationBar
import com.flowww.android.ui.screens.ActiveTransfersScreen
import com.flowww.android.ui.screens.NearbyDevicesScreen
import com.flowww.android.ui.screens.PairingScreen
import com.flowww.android.ui.screens.SettingsScreen
import com.flowww.android.ui.screens.StagedContentScreen
import com.flowww.android.ui.screens.TransferHistoryScreen
import com.flowww.android.ui.screens.TrustedDevicesScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlowwwApp()
        }
    }
}

@Composable
fun FlowwwApp() {
    var selectedScreen by rememberSaveable { mutableStateOf(Screen.NearbyDevices) }

    Scaffold(
        bottomNavigation = {
            NavigationBar(selectedScreen = selectedScreen) { screen ->
                selectedScreen = screen
            }
        }
    ) { innerPadding ->
        when (selectedScreen) {
            Screen.NearbyDevices -> NearbyDevicesScreen(Modifier.padding(innerPadding))
            Screen.TrustedDevices -> TrustedDevicesScreen(Modifier.padding(innerPadding))
            Screen.Pairing -> PairingScreen(Modifier.padding(innerPadding))
            Screen.StagedContent -> StagedContentScreen(Modifier.padding(innerPadding))
            Screen.ActiveTransfers -> ActiveTransfersScreen(Modifier.padding(innerPadding))
            Screen.TransferHistory -> TransferHistoryScreen(Modifier.padding(innerPadding))
            Screen.Settings -> SettingsScreen(Modifier.padding(innerPadding))
        }
    }
}

enum class Screen {
    NearbyDevices, TrustedDevices, Pairing, StagedContent, ActiveTransfers, TransferHistory, Settings
}
