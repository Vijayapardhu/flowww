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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.domain.models.HistoryFilter
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.ErrorState
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwTopBar
import com.flowww.android.ui.components.LoadingState
import com.flowww.android.ui.components.TransferCard
import com.flowww.android.ui.viewmodel.TransferViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun TransferHistoryScreen(
    viewModel: TransferViewModel,
    onBack: () -> Unit,
    onOpenDetails: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.historyState.collectAsStateWithLifecycle()
    LazyColumn(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FlowwwTopBar(
                title = "Transfer history",
                subtitle = "Sent and received activity from real transfer events (pending integration).",
                onBack = onBack
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryChip("All", state.filter == HistoryFilter.ALL, { viewModel.setHistoryFilter(HistoryFilter.ALL) }, Modifier.weight(1f))
                HistoryChip("Sent", state.filter == HistoryFilter.SENT, { viewModel.setHistoryFilter(HistoryFilter.SENT) }, Modifier.weight(1f))
                HistoryChip("Received", state.filter == HistoryFilter.RECEIVED, { viewModel.setHistoryFilter(HistoryFilter.RECEIVED) }, Modifier.weight(1f))
            }
        }
        state.infoMessage?.let { info ->
            item { Text(info, color = Color(0xFFA1A1A1), style = MaterialTheme.typography.bodySmall) }
        }
        state.errorMessage?.let { error ->
            item { Text(error, color = Color(0xFFD95A5A), style = MaterialTheme.typography.bodySmall) }
        }
        when {
            state.isLoading -> item { LoadingState("Loading history.") }
            state.groups.isEmpty() -> item {
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(52.dp), tint = Color(0xFF707070)) },
                        title = "No history yet",
                        subtitle = "History is not fabricated. It will populate from persisted transfer events once Rust transfer integration is connected."
                    )
                }
            }
            else -> {
                state.groups.forEach { group ->
                    item(key = "header-${group.dateEpochDay}") {
                        Text(
                            dateHeader(group.dateEpochDay),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFFA1A1A1),
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                    }
                    items(group.items, key = { it.id }) { transfer ->
                        TransferCard(
                            item = transfer,
                            onClick = { onOpenDetails(transfer.id) }
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun HistoryChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FlowwwButtonOutlined(onClick = onClick, modifier = modifier, enabled = true) {
        Text(label, maxLines = 1, softWrap = false, color = if (selected) Color(0xFFFF7A1D) else Color(0xFFA1A1A1))
    }
}

private fun dateHeader(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    return date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy"))
}
