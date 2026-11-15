package com.flowww.android.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.domain.models.LocalOperationType
import com.flowww.android.domain.models.LocalOperationUi
import com.flowww.android.ui.components.BannerTone
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwDetailRow
import com.flowww.android.ui.components.FlowwwInfoBanner
import com.flowww.android.ui.components.FlowwwTopBar
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.theme.flowwwColors
import com.flowww.android.ui.viewmodel.TransferViewModel

/**
 * Details of a single local operation.
 *
 * The record is read from the operation repository, so it reflects what really
 * happened on the device; nothing is reconstructed or estimated.
 */
@Composable
fun TransferDetailsScreen(
    operationId: String,
    viewModel: TransferViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = flowwwColors
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val operation = remember(operationId) { mutableStateOf<LocalOperationUi?>(null) }

    LaunchedEffect(operationId) { operation.value = viewModel.findOperation(operationId) }

    // Keep the record in sync when the underlying operation changes, e.g. after
    // it was cancelled from this screen.
    LaunchedEffect(state.allOperations) {
        state.allOperations.firstOrNull { it.id == operationId }?.let { operation.value = it }
    }

    Column(modifier = modifier.fillMaxSize()) {
        FlowwwTopBar(
            title = "Operation details",
            subtitle = "ID: $operationId",
            onBack = onBack
        )

        val current = operation.value
        if (current == null) {
            EmptyState(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = colors.textMuted
                    )
                },
                title = "Operation not found",
                subtitle = "It may have been cleared from the history.",
                action = {
                    FlowwwButton(onClick = onBack) { FlowwwButtonLabel("Back") }
                },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                state.errorMessage?.let { message ->
                    item { FlowwwInfoBanner(message = message, tone = BannerTone.ERROR) }
                }
                state.infoMessage?.let { message ->
                    item { FlowwwInfoBanner(message = message, tone = BannerTone.INFO) }
                }

                item {
                    FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = current.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = current.type.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary
                            )

                            if (current.isRunning) {
                                LinearProgressIndicator(
                                    progress = { current.progress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colors.accent,
                                    trackColor = colors.border
                                )
                                Text(
                                    text = "${(current.progress * 100).toInt()}% written",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textMuted
                                )
                            }

                            FlowwwDetailRow(
                                label = "Operation type",
                                value = "${current.type.label} — ${current.type.description}"
                            )
                            FlowwwDetailRow(label = "Status", value = current.status.label)
                            FlowwwDetailRow(label = "Title", value = current.title)
                            FlowwwDetailRow(
                                label = "Destination",
                                value = current.destination ?: "Not recorded"
                            )
                            FlowwwDetailRow(label = "Size", value = formatBytes(current.totalBytes))
                            FlowwwDetailRow(
                                label = "Started",
                                value = DateUtils.getRelativeTimeSpanString(current.createdAt).toString()
                            )
                            FlowwwDetailRow(
                                label = "Finished",
                                value = current.finishedAt
                                    ?.let { DateUtils.getRelativeTimeSpanString(it).toString() }
                                    ?: if (current.isRunning) "Still running" else "—"
                            )
                            FlowwwDetailRow(
                                label = "Files",
                                value = current.fileNames.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "—"
                            )
                            FlowwwDetailRow(label = "Error", value = current.errorMessage ?: "None")
                        }
                    }
                }

                if (current.type == LocalOperationType.NETWORK_TRANSFER) {
                    item {
                        FlowwwInfoBanner(message = current.type.description, tone = BannerTone.WARNING)
                    }
                }

                if (current.isRunning) {
                    item {
                        FlowwwButtonOutlined(
                            onClick = { viewModel.cancelOperation(current.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) { FlowwwButtonLabel("Cancel operation") }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
