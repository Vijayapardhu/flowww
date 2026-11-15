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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.domain.models.TransferFilter
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.ErrorState
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader
import com.flowww.android.ui.components.LoadingState
import com.flowww.android.ui.components.TransferCard
import com.flowww.android.ui.viewmodel.TransferViewModel

@Composable
fun ActiveTransfersScreen(
    viewModel: TransferViewModel,
    onOpenHistory: () -> Unit,
    onOpenDetails: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FlowwwSectionHeader(title = "Transfers", subtitle = "Active, completed, and failed transfers (no simulated progress).") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipLike("Active", state.filter == TransferFilter.ACTIVE, { viewModel.setFilter(TransferFilter.ACTIVE) }, Modifier.weight(1f))
                FilterChipLike("Completed", state.filter == TransferFilter.COMPLETED, { viewModel.setFilter(TransferFilter.COMPLETED) }, Modifier.weight(1f))
                FilterChipLike("Failed", state.filter == TransferFilter.FAILED, { viewModel.setFilter(TransferFilter.FAILED) }, Modifier.weight(1f))
            }
        }
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        state.infoMessage ?: "Transfer events appear here when Rust integration provides real state.",
                        color = Color(0xFFA1A1A1),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowwwButtonOutlined(onClick = { viewModel.refresh() }, modifier = Modifier.weight(1f)) { FlowwwButtonLabel("Refresh") }
                        FlowwwButtonOutlined(onClick = onOpenHistory, modifier = Modifier.weight(1f)) { FlowwwButtonLabel("Open history") }
                    }
                }
            }
        }
        when {
            state.isLoading -> item { LoadingState("Loading transfers.") }
            state.errorMessage != null && state.transfers.isEmpty() -> item { ErrorState(state.errorMessage!!, onRetry = { viewModel.refresh() }) }
            state.transfers.isEmpty() -> item {
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(52.dp), tint = Color(0xFF707070)) },
                        title = "No ${state.filter.name.lowercase()} transfers",
                        subtitle = "Real transfers, progress, speed, ETA, cancel/retry and incoming approval depend on Rust/FFI integration."
                    )
                }
            }
            else -> items(state.transfers, key = { it.id }) { transfer ->
                TransferCard(
                    item = transfer,
                    onClick = { onOpenDetails(transfer.id) },
                    onCancel = if (state.filter == TransferFilter.ACTIVE) { { viewModel.cancel(transfer.id) } } else null
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun FilterChipLike(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FlowwwButtonOutlined(onClick = onClick, modifier = modifier, enabled = true) {
        Text(label, maxLines = 1, softWrap = false, color = if (selected) Color(0xFFFF7A1D) else Color(0xFFA1A1A1))
    }
}
