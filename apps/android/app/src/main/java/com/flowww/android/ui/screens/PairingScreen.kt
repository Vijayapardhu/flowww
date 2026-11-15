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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.ui.components.BannerTone
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwDetailRow
import com.flowww.android.ui.components.FlowwwInfoBanner
import com.flowww.android.ui.components.FlowwwTopBar
import com.flowww.android.ui.components.QrScannerView
import com.flowww.android.ui.theme.flowwwColors
import com.flowww.android.ui.viewmodel.PairingViewModel

/**
 * Pairing.
 *
 * This build parses and validates a pairing URI. It cannot complete a secure
 * pairing: the Flowww secure protocol lives in the Rust core, which is not
 * connected here. The screen therefore never claims a device was paired.
 */
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = flowwwColors
    val clipboard = LocalClipboardManager.current
    var scanning by remember { mutableStateOf(false) }

    val parsed = state.parsed
    val validationError = state.validationError
    val failureMessage = state.failureMessage
    val expiredMessage = state.expiredMessage
    val unavailableMessage = state.unavailableMessage
    val successMessage = state.successMessage
    val serviceMessage = state.serviceMessage
    val isBusy = state.isValidating || state.isSubmitting

    Column(modifier = modifier.fillMaxSize()) {
        FlowwwTopBar(title = "Pair a device", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "What this screen can do",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "This build can parse and validate a pairing URI from the Windows " +
                                "Flowww app. It cannot complete a secure pairing: the Flowww secure " +
                                "protocol (challenge, signature verification and mTLS bootstrap) is " +
                                "implemented in the Rust core, which is not connected in this build.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                        FlowwwInfoBanner(
                            message = "A valid URI only means the URI is well formed and unexpired. " +
                                "Nothing on this screen pairs a device.",
                            tone = BannerTone.INFO
                        )
                    }
                }
            }

            item {
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = state.uriInput,
                            onValueChange = viewModel::onUriChanged,
                            label = { Text(text = "Pairing URI") },
                            placeholder = { Text(text = "flowww://pair/v1/…") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth().testTag("pairing_uri_input")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowwwButtonOutlined(
                                onClick = { viewModel.pasteFromClipboard(clipboard.getText()?.text) },
                                modifier = Modifier.weight(1f)
                            ) { FlowwwButtonLabel("Paste") }
                            FlowwwButtonOutlined(
                                onClick = { viewModel.clear() },
                                modifier = Modifier.weight(1f)
                            ) { FlowwwButtonLabel("Clear") }
                        }
                        FlowwwButton(
                            onClick = { viewModel.validateAndSubmit() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isBusy
                        ) {
                            FlowwwButtonLabel(if (isBusy) "Checking…" else "Validate URI")
                        }
                         FlowwwButtonOutlined(
                             onClick = { scanning = !scanning },
                             modifier = Modifier.fillMaxWidth()
                         ) { FlowwwButtonLabel(if (scanning) "Close QR scanner" else "Scan QR code") }
                         if (scanning) {
                             QrScannerView(
                                 modifier = Modifier.fillMaxWidth().height(260.dp),
                                 onScanned = { uri ->
                                     scanning = false
                                     viewModel.onUriChanged(uri)
                                     viewModel.validateAndSubmit()
                                 }
                             )
                         }
                    }
                }
            }

            if (validationError != null) {
                item { FlowwwInfoBanner(message = validationError, tone = BannerTone.ERROR) }
            }
            if (failureMessage != null) {
                item { FlowwwInfoBanner(message = failureMessage, tone = BannerTone.ERROR) }
            }
            if (expiredMessage != null) {
                item { FlowwwInfoBanner(message = expiredMessage, tone = BannerTone.WARNING) }
            }
            if (unavailableMessage != null) {
                item { FlowwwInfoBanner(message = unavailableMessage, tone = BannerTone.WARNING) }
            }
            if (successMessage != null) {
                item { FlowwwInfoBanner(message = successMessage, tone = BannerTone.SUCCESS) }
            }
            if (serviceMessage != null) {
                item { FlowwwInfoBanner(message = serviceMessage, tone = BannerTone.INFO) }
            }

            if (parsed != null) {
                item {
                    FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Parsed pairing request",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "These are the values the URI carries. Nothing was contacted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted
                            )
                            FlowwwDetailRow(label = "Session", value = parsed.sessionId)
                            FlowwwDetailRow(label = "Host device", value = parsed.hostDevice.deviceName)
                            FlowwwDetailRow(label = "Platform", value = parsed.hostDevice.platform.name)
                            FlowwwDetailRow(label = "Endpoint", value = parsed.endpoint)
                            FlowwwDetailRow(
                                label = "Expires",
                                value = DateUtils.getRelativeTimeSpanString(parsed.expiresAt).toString()
                            )
                            FlowwwDetailRow(
                                label = "TLS fingerprint",
                                value = parsed.hostTlsFingerprint.ifBlank { "Not present in the URI" }
                            )
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
                            text = "Save as a local device record",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "A parsed URI can be kept on this phone as a plain device record, " +
                                "the same kind you manage in the Trusted tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                        FlowwwInfoBanner(
                            message = "Saving is done from the Trusted tab: open Devices and use " +
                                "“Add device”. A local record is not a verified pairing.",
                            tone = BannerTone.INFO
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}
