package com.flowww.android.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.domain.models.FlowwwSettings
import com.flowww.android.domain.models.ThemeMode
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.BannerTone
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.ConfirmDialog
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwInfoBanner
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwOptionRow
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwSectionHeader
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwSettingRow
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.LoadingState
import com.flowww.android.ui.theme.flowwwColors
import com.flowww.android.ui.viewmodel.SettingsViewModel

/**
 * Settings.
 *
 * Every control here writes through to DataStore and is applied by the app:
 * the theme, the gesture preferences, the notification preferences, the
 * staging limits and the privacy actions all have a real effect.
 */
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
    val colors = flowwwColors
    val s = state.settings

    // Sliders hold local state so dragging does not write on every pixel;
    // the value is persisted only when the user releases it.
    var sliderSensitivity by remember(s.gestureSensitivity) { mutableStateOf(s.gestureSensitivity) }
    LaunchedEffect(s.gestureSensitivity) { sliderSensitivity = s.gestureSensitivity }
    var sliderMaxSize by remember(s.maxStagedBytes) { mutableStateOf(s.maxStagedBytes) }
    LaunchedEffect(s.maxStagedBytes) { sliderMaxSize = s.maxStagedBytes }

    var confirmClearStaged by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var themePickerOpen by remember { mutableStateOf(false) }

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

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)
            .testTag("settings_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FlowwwSectionHeader(
                title = "Settings",
                subtitle = "Preferences are stored on this phone and restored on restart."
            )
        }
        state.errorMessage?.let { item { FlowwwInfoBanner(it, BannerTone.ERROR) } }
        state.infoMessage?.let { item { FlowwwInfoBanner(it, BannerTone.INFO) } }

        // Device
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Device", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    OutlinedTextField(
                        value = s.deviceDisplayName,
                        onValueChange = { viewModel.setDeviceName(it) },
                        label = { Text("Device display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("device_name_field")
                    )
                    Text(
                        "This name is shown on this phone. It is not a cryptographic identity.",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Appearance
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Appearance",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(16.dp)
                    )
                    FlowwwSettingRow(
                        label = "Theme",
                        value = s.themeMode.label,
                        onClick = { themePickerOpen = true }
                    )
                }
            }
        }

        // Content
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Content", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    Text(
                        "Default export folder: ${defaultFolderLabel(s.defaultSaveLocationUri)}",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowwwButtonOutlined(
                            onClick = { pickSaveLocation.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) { FlowwwButtonLabel("Choose folder") }
                        if (s.defaultSaveLocationUri != null) {
                            FlowwwButtonOutlined(
                                onClick = { viewModel.setDefaultSaveLocation(null) },
                                modifier = Modifier.weight(1f)
                            ) { FlowwwButtonLabel("Clear") }
                        }
                    }
                    Text(
                        "Maximum staged content: ${formatBytes(sliderMaxSize)}",
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = sliderMaxSize.toFloat(),
                        onValueChange = { sliderMaxSize = it.toLong() },
                        onValueChangeFinished = { viewModel.setMaxStagedSize(sliderMaxSize) },
                        valueRange = 64f * 1024f * 1024f..2048f * 1024f * 1024f
                    )
                    SettingToggle(
                        label = "Expire staged content automatically",
                        hint = "Items are removed ${s.expireAfterHours} hour(s) after they were staged",
                        checked = s.autoExpireEnabled,
                        onChecked = { viewModel.setAutoExpire(it) }
                    )
                    if (s.autoExpireEnabled) {
                        Text(
                            "Expire after ${s.expireAfterHours} hour(s)",
                            color = colors.textPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowwwButtonOutlined(
                                onClick = { viewModel.setExpireAfterHours((s.expireAfterHours - 1).coerceAtLeast(FlowwwSettings.MIN_EXPIRE_HOURS)) },
                                modifier = Modifier.weight(1f)
                            ) { FlowwwButtonLabel("Shorter") }
                            FlowwwButtonOutlined(
                                onClick = { viewModel.setExpireAfterHours((s.expireAfterHours + 1).coerceAtMost(FlowwwSettings.MAX_EXPIRE_HOURS)) },
                                modifier = Modifier.weight(1f)
                            ) { FlowwwButtonLabel("Longer") }
                        }
                    }
                    FlowwwButtonOutlined(
                        onClick = { confirmClearStaged = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { FlowwwButtonLabel("Clear staged content") }
                }
            }
        }

        // Motion
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Motion", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    SettingToggle(
                        label = "Motion gestures",
                        hint = "Gestures act on staged content on this phone; they never send data anywhere",
                        checked = s.motionGesturesEnabled,
                        onChecked = { viewModel.setMotionGestures(it) }
                    )
                    SettingToggle(
                        label = "Gesture feedback",
                        hint = "Show the recognised gesture on screen",
                        checked = s.gestureFeedbackEnabled,
                        onChecked = { viewModel.setGestureFeedback(it) }
                    )
                    Text(
                        "Gesture sensitivity: ${"%.2f".format(sliderSensitivity)}",
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = sliderSensitivity,
                        onValueChange = { sliderSensitivity = it },
                        onValueChangeFinished = { viewModel.setSensitivity(sliderSensitivity) },
                        valueRange = FlowwwSettings.MIN_SENSITIVITY..FlowwwSettings.MAX_SENSITIVITY
                    )
                    FlowwwButtonOutlined(
                        onClick = onOpenGestureDiagnostics,
                        modifier = Modifier.fillMaxWidth()
                    ) { FlowwwButtonLabel("Gesture diagnostics & calibration") }
                }
            }
        }

        // Notifications
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Notifications",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(16.dp)
                    )
                    SettingToggle(
                        label = "Transfer notifications",
                        hint = "Show notifications for local operations",
                        checked = s.transferNotifications,
                        onChecked = { viewModel.setNotifications(it) }
                    )
                    SettingToggle(
                        label = "Progress notifications",
                        hint = "Show progress while content is being exported",
                        checked = s.notificationProgressEnabled,
                        onChecked = { viewModel.setNotificationProgress(it) }
                    )
                    SettingToggle(
                        label = "Completion notifications",
                        hint = "Notify when an export, share or handoff finished",
                        checked = s.notificationCompletionEnabled,
                        onChecked = { viewModel.setNotificationCompletion(it) }
                    )
                }
            }
        }

        // Security policy
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Security policy",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(16.dp)
                    )
                    // Security invariant: incoming approval cannot be disabled from settings.
                    SettingToggle(
                        label = "Require approval for incoming transfers",
                        hint = "Security policy: approval cannot be disabled",
                        checked = true,
                        enabled = false,
                        onChecked = {}
                    )
                    SettingToggle(
                        label = "Auto-connect to last device",
                        hint = "Reserved for the Flowww peer protocol; it has no effect in this build",
                        checked = s.autoConnectLastDevice,
                        onChecked = { viewModel.setAutoConnect(it) }
                    )
                }
            }
        }

        // Privacy
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Privacy", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    Text(
                        "Everything Flowww stores stays on this phone. Clearing removes the local copy only.",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    FlowwwButtonOutlined(
                        onClick = { confirmClearHistory = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { FlowwwButtonLabel("Clear local history") }
                    FlowwwButtonOutlined(
                        onClick = { onOpenHistory() },
                        modifier = Modifier.fillMaxWidth()
                    ) { FlowwwButtonLabel("Open operation history") }
                }
            }
        }

        // About
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("About Flowww", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    Text(
                        "Version, storage details and project information",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowwwButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) {
                        FlowwwButtonLabel("Open about")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (themePickerOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { themePickerOpen = false },
            title = { Text("Theme", maxLines = 1, softWrap = false) },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        FlowwwOptionRow(
                            label = mode.label,
                            selected = mode == s.themeMode,
                            onClick = {
                                viewModel.setThemeMode(mode)
                                themePickerOpen = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { themePickerOpen = false }) {
                    Text("Close", maxLines = 1, softWrap = false)
                }
            },
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary
        )
    }

    if (confirmClearStaged) {
        ConfirmDialog(
            title = "Clear staged content?",
            message = "Every staged item and its local file will be deleted from this phone. Files you already exported elsewhere are not affected.",
            confirmLabel = "Clear",
            destructive = true,
            confirmTestTag = "settings_clear_staged_confirm",
            onConfirm = { viewModel.clearStagedContent(); confirmClearStaged = false },
            onDismiss = { confirmClearStaged = false }
        )
    }

    if (confirmClearHistory) {
        ConfirmDialog(
            title = "Clear local history?",
            message = "Every recorded local operation will be removed from this phone. Files you already exported elsewhere are not affected.",
            confirmLabel = "Clear",
            destructive = true,
            confirmTestTag = "settings_clear_history_confirm",
            onConfirm = { viewModel.clearLocalHistory(); confirmClearHistory = false },
            onDismiss = { confirmClearHistory = false }
        )
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
    val colors = flowwwColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.textPrimary else colors.textSecondary
            )
            Spacer(Modifier.height(2.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label }
        )
    }
}

/** A readable label for the folder the user picked. */
private fun defaultFolderLabel(uri: String?): String {
    if (uri.isNullOrBlank()) return "Not set"
    val last = uri.substringAfterLast('/')
    val decoded = runCatching { Uri.decode(last) }.getOrDefault(last)
    val name = decoded.substringAfterLast(':').substringAfterLast('/').ifBlank { decoded }
    return name.ifBlank { "Selected folder" }
}
