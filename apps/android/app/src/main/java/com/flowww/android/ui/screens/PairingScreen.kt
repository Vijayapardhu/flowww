package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader
import com.flowww.android.ui.viewmodel.PairingViewModel

@Composable
fun PairingScreen(viewModel: PairingViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    LazyColumn(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color(0xFFF5F5F5)) }
                FlowwwSectionHeader(title = "Pair device", subtitle = "Enter or paste a pairing URI from a trusted Windows Flowww desktop.")
            }
        }
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.serviceMessage?.let { Text(it, color = Color(0xFFA1A1A1), style = MaterialTheme.typography.bodySmall) }
                    OutlinedTextField(
                        value = state.uriInput,
                        onValueChange = viewModel::onUriChanged,
                        label = { Text("Pairing URI (flowww://pair/...)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 3
                    )
                    state.validationError?.let { Text(it, color = Color(0xFFD95A5A), style = MaterialTheme.typography.bodySmall) }
                    state.failureMessage?.let { Text(it, color = Color(0xFFD95A5A), style = MaterialTheme.typography.bodySmall) }
                    state.expiredMessage?.let { Text(it, color = Color(0xFFD95A5A), style = MaterialTheme.typography.bodySmall) }
                    state.unavailableMessage?.let { Text(it, color = Color(0xFFFF7A1D), style = MaterialTheme.typography.bodySmall) }
                    state.successMessage?.let { Text(it, color = Color(0xFF4A9E4A), style = MaterialTheme.typography.bodySmall) }
                    state.parsed?.let { parsed ->
                        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Parsed host: ${parsed.hostDevice.deviceName}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1))
                            Text("Session: ${parsed.sessionId.take(8)}…  expires in ${((parsed.expiresAt - System.currentTimeMillis()) / 1000)}s", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowwwButtonOutlined(onClick = { clipboard.getText()?.text?.let { viewModel.onUriChanged(it) } }, modifier = Modifier.weight(1f)) { Text("Paste", maxLines = 1) }
                        FlowwwButtonOutlined(onClick = { viewModel.clear() }, modifier = Modifier.weight(1f)) { Text("Clear", maxLines = 1) }
                    }
                    FlowwwButton(
                        onClick = { viewModel.validateAndSubmit() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isValidating && !state.isSubmitting
                    ) {
                        Text(if (state.isValidating || state.isSubmitting) "Checking…" else "Validate & request pairing", maxLines = 1)
                    }
                    Text("QR scanning is not enabled in this milestone to avoid requesting camera permission without a complete implementation.", color = Color(0xFF707070), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}