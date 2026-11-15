package com.flowww.android.ui.screens

import android.text.format.DateUtils
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.connection.Device
import com.flowww.android.ui.components.ConfirmDialog
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.ErrorState
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader
import com.flowww.android.ui.components.LoadingState
import com.flowww.android.ui.components.StatusIndicator
import com.flowww.android.ui.viewmodel.TrustedDevicesViewModel

@Composable
fun TrustedDevicesScreen(
    viewModel: TrustedDevicesViewModel,
    onOpenPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var pendingForget by remember { mutableStateOf<Device?>(null) }
    var pendingRename by remember { mutableStateOf<Device?>(null) }
    var renameText by remember { mutableStateOf("") }
    var detailsDevice by remember { mutableStateOf<Device?>(null) }

    LazyColumn(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FlowwwSectionHeader(title = "Trusted devices", subtitle = "Paired devices managed by the Rust trust store (integration pending).") }
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pairing", style = MaterialTheme.typography.titleSmall, color = Color(0xFFF5F5F5))
                    Text("Add a device using a pairing URI from Windows. Secure pairing completes only after Rust integration.", color = Color(0xFFA1A1A1), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowwwButton(onClick = onOpenPairing, modifier = Modifier.weight(1f)) { FlowwwButtonLabel("Pair new device") }
                        FlowwwButtonOutlined(onClick = { viewModel.refresh() }, modifier = Modifier.weight(1f)) { FlowwwButtonLabel("Refresh") }
                    }
                    state.infoMessage?.let { Text(it, color = Color(0xFFA1A1A1), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        when {
            state.isLoading -> item { LoadingState("Loading trusted devices.") }
            state.errorMessage != null && state.devices.isEmpty() -> item { ErrorState(state.errorMessage!!, onRetry = { viewModel.refresh() }) }
            state.devices.isEmpty() -> item {
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = { Icon(Icons.Outlined.People, contentDescription = null, modifier = Modifier.size(52.dp), tint = Color(0xFF707070)) },
                        title = "No trusted devices",
                        subtitle = "Pairing integration is pending. Once connected, trusted devices will appear with status and actions."
                    )
                }
            }
            else -> items(state.devices, key = { it.info.deviceId }) { device ->
                TrustedDeviceRow(
                    device = device,
                    onDetails = { detailsDevice = device },
                    onRename = {
                        renameText = device.info.deviceName
                        pendingRename = device
                    },
                    onForget = { pendingForget = device }
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    pendingForget?.let { device ->
        ConfirmDialog(
            title = "Forget device?",
            message = "“${device.info.deviceName}” will be removed from trusted devices on this phone once Rust trust-store integration is connected. The device keeps its own copy of the pairing.",
            confirmLabel = "Forget",
            destructive = true,
            onConfirm = { viewModel.forget(device.info.deviceId); pendingForget = null },
            onDismiss = { pendingForget = null }
        )
    }

    pendingRename?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Rename device", maxLines = 1, softWrap = false) },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("Device name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.rename(device.info.deviceId, renameText.trim())
                        pendingRename = null
                    },
                    enabled = renameText.isNotBlank()
                ) { Text("Save", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("Cancel", maxLines = 1, softWrap = false) }
            },
            containerColor = Color(0xFF191919),
            titleContentColor = Color(0xFFF5F5F5),
            textContentColor = Color(0xFFA1A1A1),
            iconContentColor = Color(0xFFFF7A1D)
        )
    }

    detailsDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { detailsDevice = null },
            title = { Text(device.info.deviceName, maxLines = 1, softWrap = false) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ID: ${device.info.deviceId}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1))
                    Text("Platform: ${device.info.platform}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1))
                    Text("Protocol: ${device.info.protocolVersion}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1))
                    Text("Fingerprint: ${device.publicKeyFingerprint.ifBlank { "Unknown" }}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1))
                    Text("Trusted: ${if (device.trusted) "Yes" else "No"}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1))
                    device.lastSeenMs?.let { Text("Last seen: ${DateUtils.getRelativeTimeSpanString(it)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1)) }
                    Text("Created: ${DateUtils.getRelativeTimeSpanString(device.createdAtMs)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1))
                    Text("Connection actions require Rust trust-store integration.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF707070))
                }
            },
            confirmButton = {
                TextButton(onClick = { detailsDevice = null }) { Text("Close", maxLines = 1, softWrap = false) }
            },
            containerColor = Color(0xFF191919),
            titleContentColor = Color(0xFFF5F5F5),
            textContentColor = Color(0xFFA1A1A1),
            iconContentColor = Color(0xFFFF7A1D)
        )
    }
}

@Composable
private fun TrustedDeviceRow(
    device: Device,
    onDetails: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit
) {
    FlowwwCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.info.deviceName, style = MaterialTheme.typography.titleSmall, color = Color(0xFFF5F5F5), maxLines = 1, softWrap = false)
                    Text("${device.info.platform}  ·  ${device.info.deviceId}", color = Color(0xFFA1A1A1), style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
                }
                StatusIndicator(if (device.trusted) "paired" else "unpaired")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowwwButtonOutlined(onClick = onDetails, modifier = Modifier.weight(1f)) { FlowwwButtonLabel("Details") }
                FlowwwButtonOutlined(onClick = onRename, modifier = Modifier.weight(1f)) { FlowwwButtonLabel("Rename") }
                FlowwwButtonOutlined(onClick = onForget, modifier = Modifier.weight(1f)) { FlowwwButtonLabel("Forget") }
            }
            Text("Forget/rename/connect actions require Rust trust-store integration.", color = Color(0xFF707070), style = MaterialTheme.typography.labelSmall)
        }
    }
}

