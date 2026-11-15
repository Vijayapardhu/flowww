package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.domain.models.DiscoveryStatus
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.ErrorState
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader
import com.flowww.android.ui.components.LoadingState
import com.flowww.android.ui.components.StatusIndicator
import com.flowww.android.ui.viewmodel.NearbyDevicesViewModel

@Composable
fun NearbyDevicesScreen(
    viewModel: NearbyDevicesViewModel,
    onOpenPairing: () -> Unit,
    onOpenDevice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FlowwwSectionHeader(title = "Nearby devices", subtitle = "Discover Flowww peers on your local network.") }
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Wifi, contentDescription = null, tint = if (state.status == DiscoveryStatus.DEVICES_FOUND) Color(0xFF4A9E4A) else Color(0xFFA1A1A1))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (state.status) {
                                DiscoveryStatus.INITIALIZING -> "Initializing"
                                DiscoveryStatus.DISCOVERING -> "Discovering"
                                DiscoveryStatus.DEVICES_FOUND -> "Devices found"
                                DiscoveryStatus.NO_DEVICES -> "No devices found"
                                DiscoveryStatus.PERMISSION_UNAVAILABLE -> "Permission unavailable"
                                DiscoveryStatus.NETWORK_UNAVAILABLE -> "Network unavailable"
                                DiscoveryStatus.DISCOVERY_ERROR -> "Discovery failed"
                                DiscoveryStatus.SERVICE_UNAVAILABLE -> "Discovery unavailable"
                            }, style = MaterialTheme.typography.titleSmall, color = Color(0xFFF5F5F5)
                        )
                        Text(state.infoMessage ?: state.errorMessage ?: "Ready to scan when service is connected.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1))
                    }
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.isRefreshing) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh discovery", tint = Color(0xFFFF7A1D))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowwwButtonOutlined(onClick = onOpenPairing, modifier = Modifier.weight(1f)) { Text("Pair device", maxLines = 1, softWrap = false) }
                FlowwwButtonOutlined(onClick = { viewModel.refresh() }, modifier = Modifier.weight(1f), enabled = !state.isRefreshing) { Text(if (state.isRefreshing) "Scanning…" else "Refresh", maxLines = 1) }
            }
        }
        when {
            state.isRefreshing && state.devices.isEmpty() -> item { LoadingState("Checking for nearby devices…") }
            state.errorMessage != null && state.devices.isEmpty() -> item { ErrorState(state.errorMessage!!, onRetry = { viewModel.refresh() }) }
            state.devices.isEmpty() -> item {
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = { Icon(Icons.Outlined.Devices, contentDescription = null, modifier = Modifier.size(52.dp), tint = Color(0xFF707070)) },
                        title = "No devices to show",
                        subtitle = state.infoMessage ?: "Discovery is not connected to the Rust core yet, so no real peers can be listed."
                    )
                }
            }
            else -> items(state.devices, key = { it.device.deviceId }) { peer ->
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(peer.device.deviceName, style = MaterialTheme.typography.titleSmall, color = Color(0xFFF5F5F5))
                        Text("${peer.device.platform} • ${peer.device.protocolVersion}", color = Color(0xFFA1A1A1), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusIndicator(if (peer.paired) "paired" else "disconnected")
                            Text(peer.endpoint ?: "Endpoint unknown", color = Color(0xFF707070), style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowwwButtonOutlined(onClick = onOpenPairing, modifier = Modifier.weight(1f)) { Text(if (peer.paired) "Open" else "Pair", maxLines = 1, softWrap = false) }
                            FlowwwButtonOutlined(onClick = { viewModel.markConnectUnavailable() }, modifier = Modifier.weight(1f)) { Text("Connect", maxLines = 1, softWrap = false) }
                        }
                        Text("Connect unavailable until Rust/FFI integration.", color = Color(0xFF707070), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
