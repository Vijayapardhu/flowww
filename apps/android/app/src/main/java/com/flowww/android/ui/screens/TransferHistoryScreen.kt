package com.flowww.android.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.domain.models.LocalOperationStatus
import com.flowww.android.domain.models.LocalOperationUi
import com.flowww.android.domain.models.OperationFilter
import com.flowww.android.domain.models.OperationSort
import com.flowww.android.ui.components.BannerTone
import com.flowww.android.ui.components.ConfirmDialog
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwFilterChips
import com.flowww.android.ui.components.FlowwwInfoBanner
import com.flowww.android.ui.components.FlowwwSearchField
import com.flowww.android.ui.components.FlowwwTopBar
import com.flowww.android.ui.components.LoadingState
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.theme.FlowwwColors
import com.flowww.android.ui.theme.flowwwColors
import com.flowww.android.ui.viewmodel.TransferViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The formatter used for the day headers of the history list. */
private val HistoryDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")

/**
 * The persisted operation history.
 *
 * It lists every local operation that was recorded on this device, grouped by
 * day, and can be searched, filtered and sorted.
 */
@Composable
fun TransferHistoryScreen(
    viewModel: TransferViewModel,
    onBack: () -> Unit,
    onOpenDetails: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = flowwwColors
    val state by viewModel.historyState.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        FlowwwTopBar(
            title = "Operation history",
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .testTag("history_list"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                FlowwwSearchField(
                    value = state.query,
                    onValueChange = { viewModel.setQuery(it) },
                    placeholder = "Search operations",
                    testTag = "history_search"
                )
            }

            item {
                FlowwwFilterChips(
                    options = OperationFilter.entries.map { it.label },
                    selected = state.filter.label,
                    onSelect = { label ->
                        OperationFilter.entries.firstOrNull { it.label == label }
                            ?.let { viewModel.setHistoryFilter(it) }
                    }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SortButton(
                        label = "Newest",
                        sort = OperationSort.NEWEST,
                        selected = state.sort,
                        onSelect = { viewModel.setSort(it) }
                    )
                    SortButton(
                        label = "Oldest",
                        sort = OperationSort.OLDEST,
                        selected = state.sort,
                        onSelect = { viewModel.setSort(it) }
                    )
                    SortButton(
                        label = "Name",
                        sort = OperationSort.NAME,
                        selected = state.sort,
                        onSelect = { viewModel.setSort(it) }
                    )
                    SortButton(
                        label = "Size",
                        sort = OperationSort.SIZE,
                        selected = state.sort,
                        onSelect = { viewModel.setSort(it) }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.totalCount} recorded operation(s)",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textMuted,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    FlowwwButtonOutlined(onClick = { confirmClear = true }) {
                        FlowwwButtonLabel("Clear history")
                    }
                }
            }

            state.errorMessage?.let { message ->
                item { FlowwwInfoBanner(message = message, tone = BannerTone.ERROR) }
            }
            state.infoMessage?.let { message ->
                item { FlowwwInfoBanner(message = message, tone = BannerTone.INFO) }
            }

            when {
                state.isLoading -> item { LoadingState("Loading operation history.") }

                state.groups.isEmpty() -> item {
                    FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                        EmptyState(
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(52.dp),
                                    tint = colors.textMuted
                                )
                            },
                            title = "No operations recorded",
                            subtitle = "Operations appear here after you export, share or hand off staged content."
                        )
                    }
                }

                else -> {
                    state.groups.forEach { group ->
                        item(key = "group-${group.dateEpochDay}") {
                            Text(
                                text = group.dateEpochDay.toDayHeader(),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        items(group.items, key = { it.id }) { operation ->
                            HistoryOperationCard(
                                operation = operation,
                                onOpen = { onOpenDetails(operation.id) }
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear history?",
            message = "Every recorded local operation will be removed from this phone. Files you exported are not affected.",
            confirmLabel = "Clear",
            destructive = true,
            confirmTestTag = "history_clear_confirm",
            onConfirm = {
                viewModel.clearHistory()
                confirmClear = false
            },
            onDismiss = { confirmClear = false }
        )
    }
}

@Composable
private fun SortButton(
    label: String,
    sort: OperationSort,
    selected: OperationSort,
    onSelect: (OperationSort) -> Unit
) {
    if (selected == sort) {
        FlowwwButton(onClick = { onSelect(sort) }) { FlowwwButtonLabel(label) }
    } else {
        FlowwwButtonOutlined(onClick = { onSelect(sort) }) { FlowwwButtonLabel(label) }
    }
}

@Composable
private fun HistoryOperationCard(
    operation: LocalOperationUi,
    onOpen: () -> Unit,
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
            }
        }
    }
}

/** Formats an epoch day as "EEE, MMM d, yyyy". */
private fun Long.toDayHeader(): String =
    LocalDate.ofEpochDay(this).format(HistoryDateFormatter)

/** The semantic colour of a status label. */
private fun LocalOperationStatus.tint(colors: FlowwwColors): Color = when (this) {
    LocalOperationStatus.RUNNING -> colors.accent
    LocalOperationStatus.SUCCESS -> colors.success
    LocalOperationStatus.FAILED -> colors.error
    LocalOperationStatus.CANCELLED -> colors.textMuted
}
