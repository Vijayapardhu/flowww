package com.flowww.android.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.domain.models.StagedContentItemUi
import com.flowww.android.domain.repository.StagedPreview
import com.flowww.android.ui.components.ConfirmDialog
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.ErrorState
import com.flowww.android.ui.components.FileTypeIcon
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader
import com.flowww.android.ui.theme.FlowwwShapes
import com.flowww.android.ui.components.LoadingState
import com.flowww.android.ui.components.SelectionToolbar
import com.flowww.android.ui.viewmodel.StagedContentViewModel
import kotlinx.coroutines.launch

@Composable
fun StagedContentScreen(viewModel: StagedContentViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val destinations by viewModel.destinations.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var confirmRemoveAll by remember { mutableStateOf(false) }
    var itemPendingRemoval by remember { mutableStateOf<StagedContentItemUi?>(null) }
    var showDestinationDialog by remember { mutableStateOf(false) }
    var previewRequest by remember { mutableStateOf<StagedPreviewRequest?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var previewLoadingId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    fun requestPreview(id: String) {
        previewLoadingId = id
        previewError = null
        lifecycleOwner.lifecycleScope.launch {
            viewModel.preview(id)
                .onSuccess { previewRequest = StagedPreviewRequest.Ready(it) }
                .onFailure { previewError = it.message ?: "Preview unavailable" }
            previewLoadingId = null
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FlowwwSectionHeader(
                title = "Staged content",
                subtitle = "Shared items stored locally. Nothing leaves this device without a real transfer."
            )
        }
        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Storage", style = MaterialTheme.typography.titleSmall, color = Color(0xFFF5F5F5))
                    Text(
                        "Using ~${Formatter.formatShortFileSize(context, state.storageUsageBytes)} across ${state.totalCount} item(s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFA1A1A1)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowwwButtonOutlined(onClick = { viewModel.refresh() }, modifier = Modifier.weight(1f)) { FlowwwButtonLabel("Refresh") }
                        if (state.selectionMode) {
                            FlowwwButton(onClick = { confirmRemoveAll = true }, modifier = Modifier.weight(1f)) { FlowwwButtonLabel("Remove selected") }
                        } else {
                            FlowwwButton(onClick = { showDestinationDialog = true }, modifier = Modifier.weight(1f)) { FlowwwButtonLabel("Send") }
                        }
                    }
                    state.infoMessage?.let { Text(it, color = Color(0xFFFF7A1D), style = MaterialTheme.typography.bodySmall) }
                    state.errorMessage?.let { Text(it, color = Color(0xFFD95A5A), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (state.items.isNotEmpty()) {
            item {
                SelectionToolbar(
                    selectedCount = state.selectedIds.size,
                    totalCount = state.items.size,
                    allSelected = state.allSelected,
                    onSelectAll = viewModel::selectAll,
                    onClearSelection = viewModel::clearSelection,
                    onRemoveSelected = { confirmRemoveAll = true },
                    onDismiss = viewModel::clearSelection
                )
            }
        }
        when {
            state.isLoading -> item { LoadingState("Loading staged content.") }
            state.errorMessage != null && state.items.isEmpty() -> item { ErrorState(state.errorMessage!!, onRetry = { viewModel.refresh() }) }
            state.items.isEmpty() -> item {
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(52.dp), tint = Color(0xFF707070)) },
                        title = "Nothing staged yet",
                        subtitle = "Share text, links, images, videos, PDFs or documents to Flowww. Staged items appear here."
                    )
                }
            }
            else -> items(state.items, key = { it.id }) { item ->
                StagedItemRow(
                    item = item,
                    selected = item.id in state.selectedIds,
                    previewLoading = previewLoadingId == item.id,
                    onToggleSelect = { viewModel.toggleSelection(item.id) },
                    onRemove = { itemPendingRemoval = item },
                    onPreview = { requestPreview(item.id) }
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (confirmRemoveAll) {
        ConfirmDialog(
            title = "Remove selected items?",
            message = "This removes ${state.selectedIds.size} staged item(s) and their local files. This cannot be undone.",
            confirmLabel = "Remove",
            destructive = true,
            confirmTestTag = "staged_remove_selected_confirm",
            onConfirm = { viewModel.removeSelected(); confirmRemoveAll = false },
            onDismiss = { confirmRemoveAll = false }
        )
    }
    itemPendingRemoval?.let { pending ->
        ConfirmDialog(
            title = "Remove item?",
            message = "“${pending.name}” will be removed from staged content and its local file deleted. This cannot be undone.",
            confirmLabel = "Remove",
            destructive = true,
            confirmTestTag = "staged_remove_item_confirm",
            onConfirm = { viewModel.remove(pending.id); itemPendingRemoval = null },
            onDismiss = { itemPendingRemoval = null }
        )
    }
    if (showDestinationDialog) {
        DestinationDialog(
            destinations = destinations,
            selectedCount = state.selectedIds.size,
            onSend = { deviceId ->
                viewModel.sendSelected(deviceId)
                showDestinationDialog = false
            },
            onDismiss = { showDestinationDialog = false }
        )
    }
    previewRequest?.let { request ->
        when (request) {
            is StagedPreviewRequest.Ready -> when (val preview = request.preview) {
                is StagedPreview.Text -> {
                    ConfirmDialog(
                        title = "Preview",
                        message = preview.text.take(2000).ifBlank { "(empty)" },
                        confirmLabel = "Close",
                        onConfirm = { previewRequest = null },
                        onDismiss = { previewRequest = null }
                    )
                }
                is StagedPreview.Blob -> {
                    LaunchedEffect(preview.uri) {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(preview.uri, preview.mimeType ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            previewError = "No app can open this content type."
                        }
                        previewRequest = null
                    }
                }
            }
        }
    }
    previewError?.let { error ->
        ConfirmDialog(
            title = "Preview unavailable",
            message = error,
            confirmLabel = "Close",
            onConfirm = { previewError = null },
            onDismiss = { previewError = null }
        )
    }
}

private sealed class StagedPreviewRequest {
    data class Ready(val preview: StagedPreview) : StagedPreviewRequest()
}

@Composable
private fun StagedItemRow(
    item: StagedContentItemUi,
    selected: Boolean,
    previewLoading: Boolean,
    onToggleSelect: () -> Unit,
    onRemove: () -> Unit,
    onPreview: () -> Unit
) {
    val context = LocalContext.current
    val size = item.sizeBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "Unknown size"

    FlowwwCard(modifier = Modifier.fillMaxWidth().clickable { onToggleSelect() }) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            FileTypeIcon(item.contentType, item.mimeType, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, color = Color(0xFFF5F5F5), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                val meta = buildList {
                    add(item.mimeType ?: item.contentType.name.lowercase())
                    add(size)
                    add(DateUtils.getRelativeTimeSpanString(item.createdAt).toString())
                }.joinToString("  ·  ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                val (status, statusColor) = when {
                    item.isExpired -> "Expired" to Color(0xFFD95A5A)
                    item.blobMissing -> "Missing file" to Color(0xFFD95A5A)
                    item.lifecycleState == ContentLifecycleState.STAGED -> "Staged" to Color(0xFF4A9E4A)
                    else -> item.lifecycleState.name.lowercase().replaceFirstChar { it.uppercase() } to Color(0xFFA1A1A1)
                }
                Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (previewLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFFFF7A1D))
                } else if (item.canPreview) {
                    TextButton(onClick = onPreview) { Text("Preview", maxLines = 1, softWrap = false, color = Color(0xFFFF7A1D)) }
                }
                if (!item.blobMissing && !item.isExpired) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = "Available", tint = Color(0xFF4A9E4A), modifier = Modifier.size(16.dp))
                }
                TextButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Remove ${item.name}", tint = Color(0xFFA1A1A1), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun DestinationDialog(
    destinations: List<com.flowww.android.connection.Device>,
    selectedCount: Int,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = FlowwwShapes.large,
            color = Color(0xFF222222),
            modifier = Modifier.fillMaxWidth().padding(4.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Send to device", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5), maxLines = 1, softWrap = false)
                if (destinations.isEmpty()) {
                    Text(
                        "No trusted devices are available yet. Sending requires a real device plus the Rust transfer engine. Pair a device first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFA1A1A1)
                    )
                } else {
                    Text(
                        "Select a destination. Transfer execution depends on the Rust transfer engine (not connected in this build).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA1A1A1)
                    )
                    destinations.forEach { device ->
                        FlowwwCard(modifier = Modifier.fillMaxWidth().clickable { onSend(device.info.deviceId) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(device.info.deviceName, style = MaterialTheme.typography.titleSmall, color = Color(0xFFF5F5F5), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                Text("${device.info.platform}  ·  ${device.info.protocolVersion}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1))
                            }
                        }
                    }
                }
                FlowwwButtonOutlined(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { FlowwwButtonLabel("Close") }
            }
        }
    }
}
