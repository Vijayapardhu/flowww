package com.flowww.android.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader
import com.flowww.android.ui.components.LoadingState
import com.flowww.android.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGestureDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val s = state.settings

    // Slider holds local state so dragging does not write to DataStore on every pixel;
    // the value is persisted only when the user releases it.
    var sliderSensitivity by remember(s.gestureSensitivity) { mutableStateOf(s.gestureSensitivity) }
    LaunchedEffect(s.gestureSensitivity) { sliderSensitivity = s.gestureSensitivity }

    val pickSaveLocation = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, flags)
                viewModel.setDefaultSaveLocation(it.toString())
            } catch (e: SecurityException) {
                viewModel.setDefaultSaveLocation(null)
            }
        }
    }

    if (state.isLoading) { LoadingState("Loading settings."); return }

    LazyColumn(modifier = modifier.fillMaxSize().padding(20.dp)) {
        item { FlowwwSectionHeader(title = "Settings", subtitle = "Preferences are stored locally and restored on restart.") }
        item { state.errorMessage?.let { Text(it, color = Color(0xFFD95A5A), modifier = Modifier.padding(bottom = 8.dp)) } }
        item { state.infoMessage?.let { Text(it, color = Color(0xFFFF7A1D), modifier = Modifier.padding(bottom = 8.dp)) } }
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Device", style = MaterialTheme.typography.titleSmall, color = Color(0xFFF5F5F5))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = s.deviceDisplayName,
                        onValueChange = { viewModel.setDeviceName(it) },
                        label = { Text("Device display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("device_name_field")
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Identity and secure status: unavailable until Rust node/FFI integration.", color = Color(0xFFA1A1A1), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column {
                    SettingToggle("Auto-connect to last device", "Reconnect quickly when available (requires connection integration)", s.autoConnectLastDevice, onChecked = { viewModel.setAutoConnect(it) })
                    SettingToggle("Transfer notifications", "Show progress and completion alerts", s.transferNotifications, onChecked = { viewModel.setNotifications(it) })
                    SettingToggle("Motion gestures", "Experimental preference; real handoff requires Rust integration", s.motionGesturesEnabled, onChecked = { viewModel.setMotionGestures(it) })
                }
            }
        }
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Gesture sensitivity: ${"%.2f".format(sliderSensitivity)}", color = Color(0xFFF5F5F5))
                    Slider(
                        value = sliderSensitivity,
                        onValueChange = { sliderSensitivity = it },
                        onValueChangeFinished = { viewModel.setSensitivity(sliderSensitivity) },
                        valueRange = 0.3f..1.0f
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowwwButtonOutlined(onClick = onOpenGestureDiagnostics, modifier = Modifier.fillMaxWidth()) { FlowwwButtonLabel("Gesture diagnostics & calibration") }
                }
            }
        }
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Security policy", style = MaterialTheme.typography.titleSmall, color = Color(0xFFF5F5F5))
                    Spacer(Modifier.height(8.dp))
                    // Security invariant: incoming approval cannot be disabled from settings.
                    SettingToggle(
                        label = "Require approval for incoming transfers",
                        hint = "Security policy: approval cannot be disabled",
                        checked = true,
                        enabled = false,
                        onChecked = {}
                    )
                    Spacer(Modifier.height(4.dp))
                    val saveLabel = s.defaultSaveLocationUri?.let { uri ->
                        uri.substringAfterLast('/').substringAfter("%3A").ifBlank { uri }
                    } ?: "Not set"
                    Text("Default save location: $saveLabel", color = Color(0xFFA1A1A1), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        FlowwwButtonOutlined(onClick = { pickSaveLocation.launch(null) }, modifier = Modifier.weight(1f)) { FlowwwButtonLabel("Choose folder") }
                        if (s.defaultSaveLocationUri != null) {
                            Spacer(Modifier.padding(4.dp))
                            FlowwwButtonOutlined(onClick = { viewModel.setDefaultSaveLocation(null) }, modifier = Modifier.weight(1f)) { FlowwwButtonLabel("Clear") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowwwButtonOutlined(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) { FlowwwButtonLabel("Transfer history") }
                }
            }
        }
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clickable { onOpenAbout() }) {
                Column(Modifier.padding(16.dp)) {
                    Text("About Flowww", style = MaterialTheme.typography.titleSmall, color = Color(0xFFF5F5F5))
                    Text("Version, diagnostics and project info", color = Color(0xFFA1A1A1), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingToggle(
    label: String,
    hint: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Color(0xFFF5F5F5) else Color(0xFFA1A1A1))
            Spacer(Modifier.height(2.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9A9A))
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label })
    }
}
