package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader

@Composable
fun NearbyDevicesScreen(
    onDeviceClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        FlowwwSectionHeader(
            title = "Discover nearby devices",
            subtitle = "Share files and links quickly across your trusted devices."
        )

        FlowwwCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Wifi, contentDescription = null, tint = Color(0xFF4A9E4A))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Discovery active", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Make sure Flowww is open on another device and both are on the same network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFA1A1A1)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row {
                    FlowwwButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Scan") }
                    Spacer(modifier = Modifier.width(12.dp))
                    FlowwwButtonOutlined(onClick = {}, modifier = Modifier.weight(1f)) { Text("Pair") }
                }
            }
        }

        FlowwwCard(modifier = Modifier.fillMaxWidth()) {
            EmptyState(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = Color(0xFF707070)
                    )
                },
                title = "No devices nearby",
                subtitle = "When a device is found, it will appear here for quick pairing and transfer."
            )
        }
    }
}