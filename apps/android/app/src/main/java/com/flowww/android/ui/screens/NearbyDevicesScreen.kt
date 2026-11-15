package com.flowww.android.ui.screens

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.flowww.android.domain.models.DiscoveryStatus
import com.flowww.android.domain.models.WifiPermissionState
import com.flowww.android.ui.components.BannerTone
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwDetailRow
import com.flowww.android.ui.components.FlowwwInfoBanner
import com.flowww.android.ui.components.FlowwwSectionHeader
import com.flowww.android.ui.theme.flowwwColors
import com.flowww.android.ui.viewmodel.NearbyDevicesViewModel

/**
 * Nearby devices.
 *
 * Discovery is backed by the Rust mDNS node. Discovered peers can be
 * connected to if they are already paired (trusted) via QR code.
 */
@Composable
fun NearbyDevicesScreen(
    viewModel: NearbyDevicesViewModel,
    onOpenPairing: () -> Unit,
    onOpenDevice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = flowwwColors

    val environment = state.environment
    val peers = state.devices
    val errorMessage = state.errorMessage
    val infoMessage = state.infoMessage
    val requiredPermission = environment.requiredPermission

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onPermissionResult(granted) }

    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FlowwwSectionHeader(
                title = "Nearby",
                subtitle = "Network state of this phone and the Wi-Fi networks it can see."
            )
        }

        if (errorMessage != null) {
            item { FlowwwInfoBanner(message = errorMessage, tone = BannerTone.ERROR) }
        }
        if (infoMessage != null) {
            item { FlowwwInfoBanner(message = infoMessage, tone = BannerTone.INFO) }
        }

        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Network",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "The real connection state of this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted
                    )
                    FlowwwDetailRow(
                        label = "Connection",
                        value = if (environment.isConnected) "Connected" else "Offline"
                    )
                    FlowwwDetailRow(label = "Transport", value = environment.transport)
                    FlowwwDetailRow(
                        label = "Wi-Fi radio",
                        value = if (environment.isWifiEnabled) "On" else "Off"
                    )
                    FlowwwDetailRow(
                        label = "Nearby-devices permission",
                        value = when (environment.permissionState) {
                            WifiPermissionState.GRANTED -> "Granted"
                            WifiPermissionState.NOT_GRANTED -> "Not granted"
                            WifiPermissionState.NOT_REQUIRED -> "Not required"
                        }
                    )
                    if (environment.permissionState == WifiPermissionState.NOT_GRANTED &&
                        requiredPermission != null
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowwwButtonOutlined(
                            onClick = { permissionLauncher.launch(requiredPermission) },
                            modifier = Modifier.fillMaxWidth()
                        ) { FlowwwButtonLabel("Allow nearby devices") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowwwButtonOutlined(
                            onClick = { viewModel.refresh() },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isRefreshing
                        ) { FlowwwButtonLabel("Refresh") }
                        FlowwwButtonOutlined(
                            onClick = { viewModel.scanWifiNetworks() },
                            modifier = Modifier.weight(1f),
                            enabled = environment.canScan && !state.isRefreshing
                        ) { FlowwwButtonLabel("Scan Wi-Fi") }
                    }
                }
            }
        }

        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Flowww peers",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Discovered peers on the local network. Connect only works for peers " +
                            "already paired via QR code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    FlowwwDetailRow(label = "Discovery status", value = discoveryStatusLabel(state.status))
                    if (peers.isEmpty()) {
                        EmptyState(
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Devices,
                                    contentDescription = null,
                                    modifier = Modifier.size(52.dp),
                                    tint = colors.textMuted
                                )
                            },
                            title = "No Flowww peers",
                            subtitle = "Discover peers on the local network, or pair a device via QR code."
                        )
                    } else {
                        peers.forEach { peer ->
                            PeerRow(
                                name = peer.device.deviceName,
                                platform = peer.device.platform.name,
                                endpoint = peer.endpoint ?: "Endpoint unknown",
                                paired = peer.paired,
                                onOpen = { onOpenDevice(peer.device.deviceId) },
                                onConnect = {
                                    scope.launch {
                                        val connected = viewModel.connectToDevice(peer.device.deviceId)
                                        if (connected) {
                                            // Connection succeeded; peer is trusted
                                        } else {
                                            // Already handled via errorMessage in UI state
                                        }
                                    }
                                }
                            )
                        }
                    }
                    FlowwwButtonOutlined(
                        onClick = onOpenPairing,
                        modifier = Modifier.fillMaxWidth()
                    ) { FlowwwButtonLabel("Pair a device instead") }
                }
            }
        }

        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Wi-Fi networks",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Ordinary Wi-Fi networks this phone can see. They are not Flowww peers " +
                            "and cannot be connected to from here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    if (environment.networks.isEmpty()) {
                        Text(
                            text = when {
                                environment.permissionState == WifiPermissionState.NOT_GRANTED ->
                                    "Scan results need the nearby-devices permission. Grant it above " +
                                        "to see the networks this phone can see."
                                !environment.isWifiEnabled ->
                                    "The Wi-Fi radio is switched off. Turn it on to scan for networks."
                                else -> "No scan results yet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted
                        )
                    } else {
                        environment.networks.forEach { network ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = network.ssid,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textPrimary,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${network.signalLabel} · ${network.frequencyMhz} MHz · ${network.bssid}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    environment.lastScanAt?.let { scannedAt ->
                        Text(
                            text = "Last scan ${DateUtils.getRelativeTimeSpanString(scannedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

@Composable
private fun PeerRow(
    name: String,
    platform: String,
    endpoint: String,
    paired: Boolean,
    onOpen: () -> Unit,
    onConnect: () -> Unit
) {
    val colors = flowwwColors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = colors.textPrimary,
            maxLines = 1
        )
        Text(
            text = "$platform · $endpoint",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 1
        )
        Text(
            text = if (paired) "Paired" else "Not paired",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlowwwButtonOutlined(
                onClick = onOpen,
                modifier = Modifier.weight(1f)
            ) { FlowwwButtonLabel("Details") }
            FlowwwButtonOutlined(
                onClick = onConnect,
                modifier = Modifier.weight(1f)
            ) { FlowwwButtonLabel("Connect") }
        }
    }
}

private fun discoveryStatusLabel(status: DiscoveryStatus): String = when (status) {
    DiscoveryStatus.INITIALIZING -> "Initializing"
    DiscoveryStatus.DISCOVERING -> "Discovering"
    DiscoveryStatus.DEVICES_FOUND -> "Devices found"
    DiscoveryStatus.NO_DEVICES -> "No devices found"
    DiscoveryStatus.PERMISSION_UNAVAILABLE -> "Permission unavailable"
    DiscoveryStatus.NETWORK_UNAVAILABLE -> "Network unavailable"
    DiscoveryStatus.DISCOVERY_ERROR -> "Discovery failed"
    DiscoveryStatus.SERVICE_UNAVAILABLE -> "Discovery unavailable"
}
