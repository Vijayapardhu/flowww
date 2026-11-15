package com.flowww.android.ui.screens

import android.text.format.DateUtils
import android.text.format.Formatter
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.connection.TransferDirection
import com.flowww.android.connection.isTerminal
import com.flowww.android.domain.models.TransferItemUi
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwTopBar
import com.flowww.android.ui.components.StatusIndicator
import com.flowww.android.ui.viewmodel.TransferViewModel

@Composable
fun TransferDetailsScreen(
    transferId: String,
    viewModel: TransferViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val item: TransferItemUi? = viewModel.findTransfer(transferId)

    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FlowwwTopBar(
                title = "Transfer details",
                subtitle = "ID: $transferId",
                onBack = onBack
            )
        }
        state.errorMessage?.let { error ->
            item {
                Text(error, color = Color(0xFFD95A5A), modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (item == null) {
            item {
                FlowwwCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No transfer data", style = MaterialTheme.typography.titleSmall, color = Color(0xFFF5F5F5))
                        Text(
                            "Details, progress and ETA appear here only when a real transfer exists. Transfer execution requires Rust/FFI integration.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA1A1A1)
                        )
                    }
                }
            }
        } else {
            item {
                FlowwwCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.filename, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5))
                        DetailRow("Direction", if (item.direction == TransferDirection.OUTGOING) "Sent to ${item.destinationLabel}" else "Received from ${item.sourceLabel}")
                        DetailRow("Size", Formatter.formatShortFileSize(context, item.sizeBytes))
                        DetailRow("Progress", "${Formatter.formatShortFileSize(context, item.bytesTransferred)} of ${Formatter.formatShortFileSize(context, item.sizeBytes)}")
                        DetailRow("Type", item.mimeType ?: "Unknown")
                        DetailRow("Created", DateUtils.getRelativeTimeSpanString(item.createdAt).toString())
                        item.completedAt?.let { DetailRow("Completed", DateUtils.getRelativeTimeSpanString(it).toString()) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Status", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1))
                            Spacer(Modifier.padding(4.dp))
                            StatusIndicator(item.status.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                        item.error?.let {
                            Text("Reason: $it", color = Color(0xFFD95A5A), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (!item.status.isTerminal()) {
                item {
                    FlowwwButton(onClick = { viewModel.cancel(item.id) }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                        FlowwwButtonLabel("Cancel transfer")
                    }
                }
            } else if (item.status.name == "FAILED") {
                item {
                    FlowwwButtonOutlined(onClick = { viewModel.retryUnavailable() }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                        FlowwwButtonLabel("Retry (unavailable until Rust integration)")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1), modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Color(0xFFF5F5F5))
    }
}
