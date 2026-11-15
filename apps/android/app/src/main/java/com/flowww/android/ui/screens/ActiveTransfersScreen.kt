package com.flowww.android.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.domain.models.LocalOperationStatus
import com.flowww.android.domain.models.LocalOperationType
import com.flowww.android.domain.models.LocalOperationUi
import com.flowww.android.domain.models.OperationFilter
import com.flowww.android.ui.components.BannerTone
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwFilterChips
import com.flowww.android.ui.components.FlowwwInfoBanner
import com.flowww.android.ui.components.FlowwwSectionHeader
import com.flowww.android.ui.components.LoadingState
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.theme.FlowwwColors
import com.flowww.android.ui.theme.flowwwColors
import com.flowww.android.ui.viewmodel.TransferViewModel

/**
 * The transfer manager.
 *
 * This build performs local operations only — exports, Android shares, local
 * saves and handoffs to other apps. Every record shown here corresponds to an
 * operation that really happened on this device; network transfer to a Flowww
 * peer is unavailable in this build and is reported as such.
 */
@Composable
fun ActiveTransfersScreen(
    viewModel: TransferViewModel,
    onOpenHistory: () -> Unit,
    onOpenDetails: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = flowwwColors
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("transfers_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FlowwwSectionHeader(
                title = "Transfer manager",
                subtitle = "Local operations only — sharing, exporting and handing content to other Android apps."
            )
        }

        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        OperationStat(
                            label = "Running",
                            value = state.runningCount.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        OperationStat(
                            label = "Total operations",
                            value = state.allOperations.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    FlowwwInfoBanner(
                        message = LocalOperationType.NETWORK_TRANSFER.description,
                        tone = BannerTone.WARNING
                    )
                }
            }
        }

        item {
            FlowwwFilterChips(
                options = OperationFilter.entries.map { it.label },
                selected = state.filter.label,
                onSelect = { label ->
                    OperationFilter.entries.firstOrNull { it.label == label }?.let { viewModel.setFilter(it) }
                }
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowwwButtonOutlined(
                    onClick = { viewModel.refresh() },
                    modifier = Modifier.weight(1f)
                ) { FlowwwButtonLabel("Refresh") }
                FlowwwButton(
                    onClick = onOpenHistory,
                    modifier = Modifier.weight(1f)
                ) { FlowwwButtonLabel("Open history") }
            }
        }

        state.errorMessage?.let { message ->
            item { FlowwwInfoBanner(message = message, tone = BannerTone.ERROR) }
        }
        state.infoMessage?.let { message ->
            item { FlowwwInfoBanner(message = message, tone = BannerTone.INFO) }
        }

        when {
            state.isLoading -> item { LoadingState("Loading local operations.") }

            state.operations.isEmpty() -> item {
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.SwapHoriz,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = colors.textMuted
                            )
                        },
                        title = "No local operations yet",
                        subtitle = "Share content into Flowww and export or hand it off to another app — every operation is recorded here."
                    )
                }
            }

            else -> items(state.operations, key = { it.id }) { operation ->
                OperationCard(
                    operation = operation,
                    onOpen = { onOpenDetails(operation.id) },
                    onCancel = { viewModel.cancelOperation(operation.id) }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun OperationStat(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = flowwwColors
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OperationCard(
    operation: LocalOperationUi,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = flowwwColors
    FlowwwCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = operation.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = operation.type.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = operation.status.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = operation.status.tint(colors),
                    maxLines = 1,
                    softWrap = false
                )
            }

            operation.destination?.takeIf { it.isNotBlank() }?.let { destination ->
                Text(
                    text = "To $destination",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = "${formatBytes(operation.totalBytes)}  ·  " +
                    DateUtils.getRelativeTimeSpanString(operation.createdAt).toString(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )

            operation.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (operation.isRunning) {
                LinearProgressIndicator(
                    progress = { operation.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.border
                )
                FlowwwButtonOutlined(onClick = onCancel) { FlowwwButtonLabel("Cancel") }
            }
        }
    }
}

/** The semantic colour of a status label. */
private fun LocalOperationStatus.tint(colors: FlowwwColors): Color = when (this) {
    LocalOperationStatus.RUNNING -> colors.accent
    LocalOperationStatus.SUCCESS -> colors.success
    LocalOperationStatus.FAILED -> colors.error
    LocalOperationStatus.CANCELLED -> colors.textMuted
}
