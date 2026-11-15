package com.flowww.android.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.content.ContentType
import com.flowww.android.domain.models.StagedContentItemUi
import com.flowww.android.domain.models.StagedPreviewUi
import com.flowww.android.domain.models.StagedSortOrder
import com.flowww.android.domain.models.StagedTypeFilter
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.BannerTone
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.ConfirmDialog
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.FlowwwFilterChips
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.FlowwwInfoBanner
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.FlowwwOptionRow
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.FlowwwSearchField
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.FlowwwSectionHeader
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.LoadingState
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.formatBytes
import com.flowww.android.ui.components.FlowwwIconButton
import com.flowww.android.ui.components.SelectionToolbar
import com.flowww.android.ui.theme.flowwwColors
import com.flowww.android.ui.viewmodel.StagedContentViewModel
import kotlinx.coroutines.launch

/**
 * Staged content.
 *
 * Everything on this screen works locally: the list is the real staging
 * store, and the actions share, export, copy or open the content with other
 * apps on this phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StagedContentScreen(
    viewModel: StagedContentViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = flowwwColors
    val scope = rememberCoroutineScope()

    var pendingRemoveAll by remember { mutableStateOf(false) }
    var pendingRemoveItem by remember { mutableStateOf<StagedContentItemUi?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.exportToTree(it) } }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FlowwwSectionHeader(
                title = "Staged content",
                subtitle = "Content shared into Flowww stays on this phone until you export, share or remove it."
            )
        }

        state.errorMessage?.let { message ->
            item { FlowwwInfoBanner(message, BannerTone.ERROR) }
        }
        state.infoMessage?.let { message ->
            item { FlowwwInfoBanner(message, BannerTone.INFO) }
        }

        item {
            FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Using ${formatBytes(state.storageUsageBytes)} across ${state.totalCount} item(s)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowwwButtonOutlined(
                            onClick = { viewModel.refresh() },
                            modifier = Modifier.weight(1f)
                        ) { FlowwwButtonLabel("Refresh") }
                        FlowwwButton(
                            onClick = {
                                scope.launch {
                                    viewModel.prepareShare()
                                        .onSuccess { (intent, _) -> shareLauncher.launch(intent) }
                                        .onFailure { viewModel.consumeMessage() }
                                }
                            },
                            enabled = state.selectedIds.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { FlowwwButtonLabel("Share") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowwwButtonOutlined(
                            onClick = { exportLauncher.launch(null) },
                            enabled = state.selectedIds.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { FlowwwButtonLabel("Export") }
                        FlowwwButtonOutlined(
                            onClick = { viewModel.copySelectedText() },
                            enabled = state.selectedIds.size == 1,
                            modifier = Modifier.weight(1f)
                        ) { FlowwwButtonLabel("Copy text") }
                        FlowwwButtonOutlined(
                            onClick = { viewModel.openSelected() },
                            enabled = state.selectedIds.size == 1,
                            modifier = Modifier.weight(1f)
                        ) { FlowwwButtonLabel("Open") }
                    }
                }
            }
        }

        state.exportProgress?.let { progress ->
            item {
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Exporting ${formatBytes(progress.writtenBytes)} of ${formatBytes(progress.totalBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary
                        )
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            color = colors.accent,
                            trackColor = colors.surfaceElevated,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowwwButtonOutlined(
                            onClick = { viewModel.cancelExport() },
                            modifier = Modifier.fillMaxWidth()
                        ) { FlowwwButtonLabel("Cancel export") }
                    }
                }
            }
        }

        item {
            FlowwwSearchField(
                value = state.query,
                onValueChange = { viewModel.setQuery(it) },
                placeholder = "Search staged content",
                testTag = "staged_search_field"
            )
        }

        item {
            FlowwwFilterChips(
                options = StagedTypeFilter.entries.map { it.label },
                selected = state.typeFilter.label,
                onSelect = { label ->
                    StagedTypeFilter.entries.firstOrNull { it.label == label }?.let { viewModel.setTypeFilter(it) }
                }
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${state.items.size} of ${state.allItems.size} item(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                FlowwwButtonOutlined(
                    onClick = { sortMenuOpen = !sortMenuOpen },
                    modifier = Modifier.width(150.dp)
                ) { FlowwwButtonLabel(state.sortOrder.label) }
            }
            if (sortMenuOpen) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    StagedSortOrder.entries.forEach { option ->
                        FlowwwOptionRow(
                            label = option.label,
                            selected = option == state.sortOrder,
                            onClick = {
                                viewModel.setSortOrder(option)
                                sortMenuOpen = false
                            }
                        )
                    }
                }
            }
        }

        if (state.items.isNotEmpty()) {
            item {
                SelectionToolbar(
                    selectedCount = state.selectedIds.size,
                    totalCount = state.items.size,
                    allSelected = state.allSelected,
                    onSelectAll = { viewModel.selectAll() },
                    onClearSelection = { viewModel.clearSelection() },
                    onRemoveSelected = { pendingRemoveAll = true },
                    onDismiss = { viewModel.clearSelection() }
                )
            }
        }

        when {
            state.isLoading -> item { LoadingState("Loading staged content.") }
            state.allItems.isEmpty() -> item {
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = {
                            Icon(
                                Icons.Outlined.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = colors.textMuted
                            )
                        },
                        title = "Nothing staged yet",
                        subtitle = "Share text, links or files into Flowww from any app and they will appear here."
                    )
                }
            }
            state.items.isEmpty() -> item {
                FlowwwCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = {
                            Icon(
                                Icons.Outlined.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = colors.textMuted
                            )
                        },
                        title = "No matching items",
                        subtitle = "No staged item matches the current search and filter."
                    )
                }
            }
            else -> items(state.items, key = { it.id }) { item ->
                StagedItemRow(
                    item = item,
                    selected = item.id in state.selectedIds,
                    onToggleSelect = { viewModel.toggleSelection(item.id) },
                    onRemove = { pendingRemoveItem = item },
                    onPreview = { viewModel.openPreview(item.id) }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (pendingRemoveAll) {
        ConfirmDialog(
            title = "Remove selected items?",
            message = "This removes ${state.selectedIds.size} staged item(s) and their local files. This cannot be undone.",
            confirmLabel = "Remove",
            destructive = true,
            confirmTestTag = "staged_remove_selected_confirm",
            onConfirm = { viewModel.removeSelected(); pendingRemoveAll = false },
            onDismiss = { pendingRemoveAll = false }
        )
    }

    pendingRemoveItem?.let { item ->
        ConfirmDialog(
            title = "Remove item?",
            message = "“${item.name}” will be removed from staged content and its local file deleted. This cannot be undone.",
            confirmLabel = "Remove",
            destructive = true,
            confirmTestTag = "staged_remove_item_confirm",
            onConfirm = { viewModel.remove(item.id); pendingRemoveItem = null },
            onDismiss = { pendingRemoveItem = null }
        )
    }

    state.preview?.let { preview ->
        ModalBottomSheet(onDismissRequest = { viewModel.dismissPreview() }, sheetState = sheetState) {
            PreviewContent(
                preview = preview,
                onCopy = { viewModel.copyText(preview.contentId); viewModel.dismissPreview() },
                onOpen = { viewModel.openItem(preview.contentId); viewModel.dismissPreview() },
                onShare = {
                    scope.launch {
                        viewModel.prepareShare(setOf(preview.contentId))
                            .onSuccess { (intent, _) -> shareLauncher.launch(intent) }
                    }
                    viewModel.dismissPreview()
                }
            )
        }
    }
}

@Composable
private fun StagedItemRow(
    item: StagedContentItemUi,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onRemove: () -> Unit,
    onPreview: () -> Unit
) {
    val colors = flowwwColors
    FlowwwCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            append(contentTypeLabel(item.contentType))
                            item.sizeBytes?.let { append(" · ${formatBytes(it)}") }
                            append(" · ${item.mimeType ?: "unknown type"}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StagedSelectToggle(selected = selected, onClick = onToggleSelect)
            }
            if (item.blobMissing || item.isExpired) {
                Text(
                    if (item.isExpired) "This item has expired." else "The staged file is missing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowwwButtonOutlined(
                    onClick = onPreview,
                    enabled = item.canPreview,
                    modifier = Modifier.weight(1f)
                ) { FlowwwButtonLabel("Preview") }
                FlowwwIconButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Remove ${item.name}",
                        tint = colors.error
                    )
                }            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewContent(
    preview: StagedPreviewUi,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val colors = flowwwColors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            when (preview) {
                is StagedPreviewUi.Text -> preview.title
                is StagedPreviewUi.Blob -> preview.title
            },
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        when (preview) {
            is StagedPreviewUi.Text -> {
                if (preview.isLink) {
                    FlowwwInfoBanner(preview.body, BannerTone.INFO)
                } else {
                    Text(
                        preview.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowwwButtonOutlined(onClick = onCopy, modifier = Modifier.weight(1f)) {
                        FlowwwButtonLabel("Copy")
                    }
                    if (preview.isLink) {
                        FlowwwButtonOutlined(onClick = onOpen, modifier = Modifier.weight(1f)) {
                            FlowwwButtonLabel("Open link")
                        }
                    }
                }
            }
            is StagedPreviewUi.Blob -> {
                FlowwwInfoBanner(
                    "This item is a file. Open it with any app on this phone, or share it from here.",
                    BannerTone.INFO
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowwwButtonOutlined(onClick = onOpen, modifier = Modifier.weight(1f)) {
                        FlowwwButtonLabel("Open with…")
                    }
                    FlowwwButtonOutlined(onClick = onShare, modifier = Modifier.weight(1f)) {
                        FlowwwButtonLabel("Share")
                    }
                }
            }
        }
    }
}

@Composable
private fun StagedSelectToggle(selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Checkbox(
        checked = selected,
        onCheckedChange = { onClick() },
        modifier = Modifier.testTag("staged_select_toggle")
    )
}

internal fun contentTypeLabel(type: ContentType): String = when (type) {
    ContentType.FILE -> "File"
    ContentType.IMAGE -> "Image"
    ContentType.VIDEO -> "Video"
    ContentType.TEXT -> "Text"
    ContentType.URL -> "Link"
    ContentType.DOCUMENT -> "Document"
    ContentType.MULTIPLE_FILES -> "Files"
}

