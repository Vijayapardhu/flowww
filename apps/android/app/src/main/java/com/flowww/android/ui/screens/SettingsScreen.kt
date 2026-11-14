package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        FlowwwSectionHeader(title = "Settings", subtitle = "Tune how Flowww connects and transfers.")

        FlowwwCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingRow(label = "Auto-connect to last device", hint = "Reconnect quickly when available", checked = false)
                SettingRow(label = "Transfer notifications", hint = "Show progress and completion alerts", checked = true)
                SettingRow(label = "Motion gestures", hint = "Enable experimental gesture controls", checked = false)
            }
        }

        FlowwwCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Flowww Android companion • UI preview build",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFA1A1A1)
                )
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, hint: String, checked: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFF5F5F5))
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = hint, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9A9A))
        }
        Switch(checked = checked, onCheckedChange = {})
    }
}