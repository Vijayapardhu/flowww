package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    var autoConnect by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(true) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(text = "Settings", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)

        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = "Auto-connect to last device",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = autoConnect,
                onCheckedChange = { autoConnect = it }
            )
        }

        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = "Transfer notifications",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = notifications,
                onCheckedChange = { notifications = it }
            )
        }
    }
}
