package com.flowww.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NavigationBar(
    selectedScreen: MainScreen,
    onItemClick: (MainScreen) -> Unit
) {
    androidx.compose.material3.navigation.rememberNavigationBar(
        onTabSelected = { onItemClick(it) }
    ) {
        MainScreen.values().forEach { screen ->
            NavigationBarItem(
                selected = selectedScreen == screen,
                onClick = { onItemClick(screen) },
                icon = { screen.icon },
                label = { Text(screen.label) }
            )
        }
    }
}

sealed class MainScreen(val label: String, val icon: @Composable () -> Unit) {
    object NearbyDevices : MainScreen("Nearby", { androidx.compose.material3.Icons.Default.DeviceHub })
    object TrustedDevices : MainScreen("Trusted", { androidx.compose.material3.Icons.Default.People })
    object Pairing : MainScreen("Pair", { androidx.compose.material3.Icons.Default.AddLink })
    object StagedContent : MainScreen("Staged", { androidx.compose.material3.Icons.Default.ContentPaste })
    object ActiveTransfers : MainScreen("Transfers", { androidx.compose.material3.Icons.Default.Upload })
    object TransferHistory : MainScreen("History", { androidx.compose.material3.Icons.Default.History })
    object Settings : MainScreen("Settings", { androidx.compose.material3.Icons.Default.Settings })

    @Composable
    fun values(): List<MainScreen> = listOf(
        NearbyDevices, TrustedDevices, Pairing, StagedContent, ActiveTransfers, TransferHistory, Settings
    )
}

@Composable
fun DeviceRow(
    deviceName: String,
    devicePlatform: String,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Icon(
            imageVector = androidx.compose.material3.Icons.Default.Devices,
            contentDescription = "Device",
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 16.dp)
        ) {
            Text(text = deviceName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "$devicePlatform • ${if (isConnected) "Connected" else "Disconnected"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.Icon(
            imageVector = if (isConnected) androidx.compose.material3.Icons.Default.CheckCircle
            else androidx.compose.material3.Icons.Default.CircleOutlined,
            contentDescription = "Status",
            modifier = Modifier.tint(if (isConnected) MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.outline)
        )
    }
}
