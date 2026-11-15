package com.flowww.android.ui.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowww.android.FlowwwError
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagedContent
import com.flowww.android.domain.models.ExportProgress
import com.flowww.android.domain.models.LocalOperationType
import com.flowww.android.domain.models.StagedContentItemUi
import com.flowww.android.domain.models.StagedContentUiState
import com.flowww.android.domain.models.StagedPreviewUi
import com.flowww.android.domain.models.StagedSortOrder
import com.flowww.android.domain.models.StagedTypeFilter
import com.flowww.android.domain.repository.LocalContentGateway
import com.flowww.android.domain.repository.ContentShareBuilder
import com.flowww.android.domain.repository.OperationRepository
import com.flowww.android.domain.repository.StagedContentRepository
import com.flowww.android.domain.repository.StagedPreview
import com.flowww.android.domain.repository.TrustedDeviceRepository
import com.flowww.android.transfer.LocalContentHandoff
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Staged content: the list, the selection and the local handoff actions.
 *
 * The ViewModel owns the filtering state and performs the real local
 * operations (share, export, copy, open). It never fabricates a result and
 * never shows a filesystem path.
 */
class StagedContentViewModel(
    private val repository: StagedContentRepository,
    trustedDeviceRepository: TrustedDeviceRepository,
    private val gateway: LocalContentGateway,
    private val handoff: LocalContentHandoff,
    private val operations: OperationRepository,
    private val shareBuilder: ContentShareBuilder = handoff
) : ViewModel() {

    private val loading = MutableStateFlow(true)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val error = MutableStateFlow<String?>(null)
    private val info = MutableStateFlow<String?>(null)
    private val usageBytes = MutableStateFlow(0L)
    private val query = MutableStateFlow("")
    private val sortOrder = MutableStateFlow(StagedSortOrder.NEWEST)
    private val typeFilter = MutableStateFlow(StagedTypeFilter.ALL)
    private val previewState = MutableStateFlow<StagedPreviewUi?>(null)
    private val preparingPreview = MutableStateFlow(false)
    private val exportProgress = MutableStateFlow<ExportProgress?>(null)
    private var exportJob: Job? = null
    @Volatile
    private var exportCancelled = false

    /**
     * Real available destinations. Empty until a device record exists.
     * The send flow must not offer phantom devices.
     */
    val destinations: StateFlow<List<com.flowww.android.domain.models.LocalTrustedDevice>> =
        trustedDeviceRepository
            .trustedDevices()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val uiItems = repository.items
        .map { items -> items.map(::toUi) }

    private data class Filters(
        val query: String = "",
        val sort: StagedSortOrder = StagedSortOrder.NEWEST,
        val filter: StagedTypeFilter = StagedTypeFilter.ALL
    )

    private data class PreviewData(
        val preview: StagedPreviewUi? = null,
        val preparing: Boolean = false,
        val exportProgress: ExportProgress? = null
    )

    private val filters = combine(query, sortOrder, typeFilter) { q, sort, filter ->
        Filters(q, sort, filter)
    }

    private val previewData = combine(previewState, preparingPreview, exportProgress) { preview, preparing, progress ->
        PreviewData(preview, preparing, progress)
    }

    private val messages = combine(error, info) { err, inf -> err to inf }

    private val baseState: StateFlow<StagedContentUiState> = combine(
        uiItems, filters, previewData, selected, messages
    ) { all, filters, preview, selectedIds, (err, inf) ->
        val valid = selectedIds.intersect(all.mapTo(mutableSetOf()) { it.id })
        val visible = applyFilters(all, filters.query, filters.filter, filters.sort)
        StagedContentUiState(
            isLoading = loading.value,
            items = visible,
            allItems = all,
            selectedIds = valid,
            errorMessage = err,
            infoMessage = inf,
            totalCount = all.size,
            query = filters.query,
            sortOrder = filters.sort,
            typeFilter = filters.filter,
            preview = preview.preview,
            isPreparingPreview = preview.preparing,
            exportProgress = preview.exportProgress
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StagedContentUiState())

    val uiState: StateFlow<StagedContentUiState> = combine(baseState, loading, usageBytes) { base, isLoading, usage ->
        base.copy(isLoading = isLoading, storageUsageBytes = usage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StagedContentUiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            runCatching { repository.refresh() }
                .onFailure { error.value = it.message ?: "Failed to refresh staged content" }
            repository.storageUsage().onSuccess { usageBytes.value = it }
            loading.value = false
        }
    }

    fun setQuery(value: String) { query.value = value }

    fun setSortOrder(order: StagedSortOrder) { sortOrder.value = order }

    fun setTypeFilter(filter: StagedTypeFilter) { typeFilter.value = filter }

    fun clearFilters() {
        query.value = ""
        typeFilter.value = StagedTypeFilter.ALL
    }

    fun toggleSelection(id: String) {
        selected.value = selected.value.toMutableSet().let {
            if (!it.add(id)) it.remove(id)
            it
        }
    }

    /** Selects every item currently visible, or clears the selection if all are selected. */
    fun selectAll() {
        val visible = uiState.value.items.map { it.id }.toSet()
        selected.value = if (visible.isNotEmpty() && selected.value.containsAll(visible)) {
            emptySet()
        } else {
            visible
        }
    }

    fun clearSelection() { selected.value = emptySet() }

    fun remove(id: String) {
        viewModelScope.launch {
            repository.remove(id)
                .onSuccess { selected.value = selected.value - id }
                .onFailure { error.value = it.message ?: "Failed to remove item" }
            refresh()
        }
    }

    fun removeSelected() {
        val ids = selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.removeMany(ids)
                .onSuccess {
                    selected.value = emptySet()
                    info.value = "Removed $it item(s)"
                }
                .onFailure { error.value = it.message ?: "Failed to remove selected items" }
            refresh()
        }
    }

    /**
     * Attempts a real send to a device. The network path is unavailable in
     * the standalone build, and the failure is reported honestly.
     */
    fun sendSelected(destinationDeviceId: String) {
        val ids = selected.value
        if (ids.isEmpty()) {
            info.value = "Select at least one staged item before sending."
            return
        }
        viewModelScope.launch {
            repository.send(ids, destinationDeviceId)
                .onSuccess {
                    info.value = "Send requested for ${ids.size} item(s)."
                    selected.value = emptySet()
                    refresh()
                }
                .onFailure { e ->
                    error.value = when (e) {
                        is FlowwwError.ProviderUnavailable ->
                            "Network transfer is unavailable in this build. Use Share or Export to move the content to another app."
                        else -> e.message ?: "Send failed"
                    }
                }
        }
    }

    /** Opens the preview for an item. */
    fun openPreview(id: String) {
        viewModelScope.launch {
            preparingPreview.value = true
            previewState.value = null
            val result = gateway.describe(id)
            preparingPreview.value = false
            result
                .onSuccess { handle ->
                    previewState.value = when (handle.kind) {
                        com.flowww.android.domain.models.ContentKind.BLOB ->
                            StagedPreviewUi.Blob(
                                contentId = handle.contentId,
                                title = handle.displayName,
                                mimeType = handle.mimeType,
                                uri = handle.uri ?: return@onSuccess
                            )
                        com.flowww.android.domain.models.ContentKind.URL ->
                            StagedPreviewUi.Text(
                                contentId = handle.contentId,
                                title = handle.displayName,
                                body = handle.text.orEmpty(),
                                isLink = true
                            )
                        else ->
                            StagedPreviewUi.Text(
                                contentId = handle.contentId,
                                title = handle.displayName,
                                body = handle.text.orEmpty(),
                                isLink = false
                            )
                    }
                }
                .onFailure { error.value = it.message ?: "This item cannot be previewed" }
        }
    }

    fun dismissPreview() { previewState.value = null }

    /** Preview handle for an item; TEXT/URL items carry their payload, files carry a content:// URI. */
    suspend fun preview(id: String): Result<StagedPreview> = repository.preview(id)

    /**
     * Builds the system Sharesheet intent for the given items, defaulting to
     * the current selection.
     *
     * The caller launches the returned intent; nothing is shared without the
     * user picking a target app.
     */
    suspend fun prepareShare(contentIds: Set<String> = selected.value): Result<Pair<Intent, String>> {
        val ids = contentIds.toList()
        if (ids.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one item to share"))
        }
        return gateway.describeAll(ids).mapCatching { handles ->
            shareBuilder.build(handles).getOrThrow()
        }.recoverCatching { e ->
            throw IllegalStateException(e.message ?: "The items cannot be shared", e)
        }
    }

    /**
     * Exports the selection into the folder the user picked.
     *
     * The export is streamed, reports progress and can be cancelled.
     */
    fun exportToTree(treeUri: Uri) {
        val ids = selected.value.toList()
        if (ids.isEmpty()) {
            info.value = "Select at least one item to export."
            return
        }
        if (exportJob?.isActive == true) {
            info.value = "An export is already running."
            return
        }
        exportJob = viewModelScope.launch {
            exportCancelled = false
            val handles = gateway.describeAll(ids).getOrElse { e ->
                error.value = e.message ?: "The items cannot be exported"
                return@launch
            }
            val totalBytes = handles.sumOf { it.size }
            val title = buildTitle(handles.size, totalBytes)
            val operation = operations.start(
                type = LocalOperationType.LOCAL_EXPORT,
                title = title,
                contentIds = ids,
                fileNames = handles.map { it.displayName },
                totalBytes = totalBytes
            ).getOrElse { e ->
                error.value = e.message ?: "The export could not be started"
                return@launch
            }
            exportProgress.value = ExportProgress(operation.id, 0L, totalBytes)
            val outcome = handoff.exportToTree(
                handles = handles,
                treeUri = treeUri,
                onProgress = { written -> exportProgress.value = ExportProgress(operation.id, written, totalBytes) },
                isCancelled = { exportCancelled }
            )
            exportProgress.value = null
            outcome
                .onSuccess { result ->
                    if (result.cancelled) {
                        operations.cancel(operation.id)
                        info.value = "Export cancelled."
                    } else {
                        operations.succeed(operation.id, result.destination)
                        info.value = "Exported ${result.savedCount} item(s) to ${result.destination ?: "the chosen folder"}."
                        selected.value = emptySet()
                    }
                }
                .onFailure { e ->
                    operations.fail(operation.id, e.message ?: "The export failed")
                    error.value = e.message ?: "The export failed"
                }
        }
    }

    /** Cancels a running export. */
    fun cancelExport() {
        exportCancelled = true
    }

    /** Copies the text of an item to the clipboard. */
    fun copyText(contentId: String) {
        viewModelScope.launch {
            gateway.describe(contentId)
                .onSuccess { handle ->
                    val text = handle.text
                    if (text.isNullOrBlank()) {
                        info.value = "Only shared text and links can be copied."
                    } else {
                        handoff.copyText(text)
                            .onSuccess { info.value = "Copied to the clipboard." }
                            .onFailure { error.value = it.message ?: "The clipboard is unavailable" }
                    }
                }
                .onFailure { error.value = it.message ?: "This item cannot be copied" }
        }
    }

    /** Copies the text of the selected item to the clipboard. */
    fun copySelectedText() {
        val id = selected.value.firstOrNull()
        if (id == null) {
            info.value = "Select one item to copy its text."
            return
        }
        copyText(id)
    }

    /** Opens an item with whichever app can show it. */
    fun openItem(contentId: String) {
        viewModelScope.launch {
            gateway.describe(contentId)
                .onSuccess { handle ->
                    handoff.openWith(handle)
                        .onSuccess { info.value = "Opened ${handle.displayName}." }
                        .onFailure { error.value = it.message ?: "This item cannot be opened" }
                }
                .onFailure { error.value = it.message ?: "This item cannot be opened" }
        }
    }

    /** Opens the selected item with whichever app can show it. */
    fun openSelected() {
        val id = selected.value.firstOrNull()
        if (id == null) {
            info.value = "Select one item to open it."
            return
        }
        openItem(id)
    }

    fun consumeMessage() { info.value = null; error.value = null }

    private fun buildTitle(count: Int, bytes: Long): String {
        val size = if (bytes <= 0L) "" else " · ${formatBytes(bytes)}"
        return if (count == 1) "1 item$size" else "$count items$size"
    }

    private fun applyFilters(
        items: List<StagedContentItemUi>,
        query: String,
        filter: StagedTypeFilter,
        sort: StagedSortOrder
    ): List<StagedContentItemUi> {
        val needle = query.trim().lowercase()
        val filtered = items.filter { item ->
            val matchesQuery = needle.isEmpty() ||
                item.name.lowercase().contains(needle) ||
                (item.mimeType?.lowercase()?.contains(needle) == true)
            val matchesType = when (filter) {
                StagedTypeFilter.ALL -> true
                StagedTypeFilter.TEXT -> item.contentType == ContentType.TEXT
                StagedTypeFilter.URL -> item.contentType == ContentType.URL
                StagedTypeFilter.IMAGE -> item.contentType == ContentType.IMAGE
                StagedTypeFilter.VIDEO -> item.contentType == ContentType.VIDEO
                StagedTypeFilter.DOCUMENT -> item.contentType == ContentType.DOCUMENT
                StagedTypeFilter.FILE -> item.contentType == ContentType.FILE ||
                    item.contentType == ContentType.MULTIPLE_FILES
            }
            matchesQuery && matchesType
        }
        return when (sort) {
            StagedSortOrder.NEWEST -> filtered.sortedByDescending { it.createdAt }
            StagedSortOrder.OLDEST -> filtered.sortedBy { it.createdAt }
            StagedSortOrder.NAME -> filtered.sortedBy { it.name.lowercase() }
            StagedSortOrder.SIZE -> filtered.sortedByDescending { it.sizeBytes ?: 0L }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = listOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format("%.1f %s", value, units[unitIndex])
    }

    private fun toUi(item: StagedContent): StagedContentItemUi {
        val blobMissing = when (item.contentType) {
            ContentType.TEXT, ContentType.URL -> false
            else -> !isBlobPresent(item)
        }
        val expired = item.isExpired() || item.lifecycleState == ContentLifecycleState.EXPIRED
        return StagedContentItemUi(
            id = item.contentId,
            name = item.displayName,
            mimeType = item.mimeType,
            contentType = item.contentType,
            sizeBytes = item.size,
            createdAt = item.createdAt,
            lifecycleState = item.lifecycleState,
            isExpired = expired,
            blobMissing = blobMissing,
            canPreview = !expired && !blobMissing && item.lifecycleState == ContentLifecycleState.STAGED
        )
    }

    private fun isBlobPresent(item: StagedContent): Boolean = try {
        java.io.File(item.contentReference).isFile
    } catch (_: Exception) {
        false
    }
}
