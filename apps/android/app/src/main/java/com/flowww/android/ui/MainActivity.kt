package com.flowww.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    val selectedScreenState = mutableStateOf(Screen.NearbyDevices)

    Scaffold(
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
            ) {
                Screen.values().forEach { screen ->
                    TextButton(
                        onClick = { selectedScreenState.value = screen }
                    ) {
                        Text(text = screen.name)
                    }
                }
            }
        }
    ) { innerPadding ->
        when (selectedScreenState.value) {
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