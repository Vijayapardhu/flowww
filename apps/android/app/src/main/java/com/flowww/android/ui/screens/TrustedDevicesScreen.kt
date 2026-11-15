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
import androidx.compose.material.icons.outlined.Devices
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.domain.models.DeviceTrustLevel
import com.flowww.android.domain.models.LocalTrustedDevice
import com.flowww.android.ui.components.BannerTone
import com.flowww.android.ui.components.ConfirmDialog
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwDetailRow
import com.flowww.android.ui.components.FlowwwInfoBanner
import com.flowww.android.ui.components.FlowwwSectionHeader
import com.flowww.android.ui.components.LoadingState
import com.flowww.android.ui.components.StatusIndicator
import com.flowww.android.ui.theme.flowwwColors
import com.flowww.android.ui.viewmodel.TrustedDevicesViewModel

/**
 * The devices this phone keeps a record of.
 *
 * Every entry is a local note. The screen says so on the surface, because a
 * saved record is not evidence of a secure pairing.
 */
@Composable
fun TrustedDevicesScreen(
    viewModel: TrustedDevicesViewModel,
    onOpenPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = flowwwColors

    val devices = state.devices
    val errorMessage = state.errorMessage
    val infoMessage = state.infoMessage

    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<LocalTrustedDevice?>(null) }
    var detailsTarget by remember { mutableStateOf<LocalTrustedDevice?>(null) }
    var forgetTarget by remember { mutableStateOf<LocalTrustedDevice?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FlowwwSectionHeader(
                title = "Devices",
                subtitle = "Devices you use Flowww with, saved on this phone."
            )
        }

        item {
            FlowwwInfoBanner(
                message = "${DeviceTrustLevel.LOCAL_RECORD_ONLY.explanation} " +
                    "The entries below are local records only, not verified pairings.",
                tone = BannerTone.WARNING
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Saved devices",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Add a device by hand to keep track of the computers you use Flowww with.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowwwButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.weight(1f)
                        ) { FlowwwButtonLabel("Add device") }
                        FlowwwButtonOutlined(
                            onClick = { viewModel.refresh() },
                            modifier = Modifier.weight(1f)
                        ) { FlowwwButtonLabel("Refresh") }
                    }
                }
            }
        }

        item {
            FlowwwButtonOutlined(
                onClick = onOpenPairing,
                modifier = Modifier.fillMaxWidth()
            ) { FlowwwButtonLabel("Pair with a pairing URI") }
        }

        when {
            state.isLoading && devices.isEmpty() -> item { LoadingState("Loading device records…") }
            devices.isEmpty() -> item {
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = colors.textMuted
                            )
                        },
                        title = "No devices saved",
                        subtitle = "Add the computers you use Flowww with so they are one tap away."
                    )
                }
            }
            else -> items(devices, key = { it.deviceId }) { device ->
                TrustedDeviceCard(
                    device = device,
                    onDetails = { detailsTarget = device },
                    onRename = { renameTarget = device },
                    onForget = { forgetTarget = device }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }

    if (showAddDialog) {
        AddDeviceDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, platform, note ->
                viewModel.add(displayName = name, platform = platform, note = note)
                showAddDialog = false
            }
        )
    }

    renameTarget?.let { target ->
        RenameDeviceDialog(
            currentName = target.displayName,
            onDismiss = { renameTarget = null },
            onSave = { newName ->
                viewModel.rename(target.deviceId, newName.trim())
                renameTarget = null
            }
        )
    }

    detailsTarget?.let { target ->
        DeviceDetailsDialog(device = target, onDismiss = { detailsTarget = null })
    }

    forgetTarget?.let { target ->
        ConfirmDialog(
            title = "Remove device?",
            message = "“${target.displayName}” will be removed from this phone. " +
                "This only deletes the local record; it does not unpair anything.",
            confirmLabel = "Remove",
            destructive = true,
            confirmTestTag = "trusted_forget_confirm",
            onConfirm = {
                viewModel.forget(target.deviceId)
                forgetTarget = null
            },
            onDismiss = { forgetTarget = null }
        )
    }
}

@Composable
private fun TrustedDeviceCard(
    device: LocalTrustedDevice,
    onDetails: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit
) {
    val colors = flowwwColors
    FlowwwCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = "${device.platform} · added ${DateUtils.getRelativeTimeSpanString(device.addedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1
                    )
                }
                StatusIndicator(status = device.trustLevel.label)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowwwButtonOutlined(
                    onClick = onDetails,
                    modifier = Modifier.weight(1f)
                ) { FlowwwButtonLabel("Details") }
                FlowwwButtonOutlined(
                    onClick = onRename,
                    modifier = Modifier.weight(1f)
                ) { FlowwwButtonLabel("Rename") }
                TextButton(onClick = onForget) {
                    FlowwwButtonLabel("Forget")
                }
            }
        }
    }
}

@Composable
private fun AddDeviceDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, platform: String, note: String) -> Unit
) {
    val colors = flowwwColors
    var name by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Add device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "The record is stored on this phone only. It is not proof of a secure pairing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = "Device name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("device_name_input")
                )
                OutlinedTextField(
                    value = platform,
                    onValueChange = { platform = it },
                    label = { Text(text = "Platform") },
                    placeholder = { Text(text = "Windows") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("device_platform_input")
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(text = "Fingerprint or note") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("device_note_input")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, platform, note) },
                enabled = name.isNotBlank()
            ) { FlowwwButtonLabel("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { FlowwwButtonLabel("Cancel") }
        },
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        iconContentColor = colors.accent
    )
}

@Composable
private fun RenameDeviceDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val colors = flowwwColors
    var text by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Rename device") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(text = "Device name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_device_input")
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { FlowwwButtonLabel("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { FlowwwButtonLabel("Cancel") }
        },
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        iconContentColor = colors.accent
    )
}

@Composable
private fun DeviceDetailsDialog(
    device: LocalTrustedDevice,
    onDismiss: () -> Unit
) {
    val colors = flowwwColors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = device.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowwwDetailRow(label = "Name", value = device.displayName)
                FlowwwDetailRow(label = "Platform", value = device.platform)
                FlowwwDetailRow(
                    label = "Fingerprint",
                    value = device.fingerprint?.takeIf { it.isNotBlank() } ?: "Not recorded"
                )
                FlowwwDetailRow(
                    label = "Added",
                    value = DateUtils.getRelativeTimeSpanString(device.addedAt).toString()
                )
                FlowwwDetailRow(
                    label = "Last seen",
                    value = device.lastSeenAt
                        ?.let { DateUtils.getRelativeTimeSpanString(it).toString() }
                        ?: "Never"
                )
                FlowwwDetailRow(
                    label = "Note",
                    value = device.note?.takeIf { it.isNotBlank() } ?: "None"
                )
                FlowwwDetailRow(
                    label = "Trust level",
                    value = "${device.trustLevel.label} — ${device.trustLevel.explanation}"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { FlowwwButtonLabel("Close") }
        },
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        iconContentColor = colors.accent
    )
}
