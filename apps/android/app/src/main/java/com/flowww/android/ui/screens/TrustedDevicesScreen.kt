package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader

@Composable
fun TrustedDevicesScreen(
    onDeviceClick: (String) -> Unit = {},
    onForgetDevice: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        FlowwwSectionHeader(
            title = "Trusted devices",
            subtitle = "Paired devices appear here for fast, secure transfers."
        )

        FlowwwCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Pair a new device", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Approve pairing once, then send content without repeating setup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFA1A1A1)
                )
                Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.foundation.layout.Row {
                    FlowwwButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Pair") }
                    Spacer(modifier = Modifier.width(12.dp))
                    FlowwwButtonOutlined(onClick = {}, modifier = Modifier.weight(1f)) { Text("Manage") }
                }
            }
        }

        FlowwwCard(modifier = Modifier.fillMaxWidth()) {
            EmptyState(
                icon = {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = Color(0xFF707070)
                    )
                },
                title = "No trusted devices yet",
                subtitle = "Devices you approve will be listed here with quick actions."
            )
        }
    }
}