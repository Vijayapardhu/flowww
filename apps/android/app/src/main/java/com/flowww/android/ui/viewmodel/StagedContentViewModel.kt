package com.flowww.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowww.android.connection.Device
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagedContent
import com.flowww.android.domain.models.StagedContentItemUi
import com.flowww.android.domain.models.StagedContentUiState
import com.flowww.android.domain.repository.StagedContentRepository
import com.flowww.android.domain.repository.StagedPreview
import com.flowww.android.domain.repository.TrustedDeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StagedContentViewModel(
    private val repository: StagedContentRepository,
    trustedDeviceRepository: TrustedDeviceRepository
) : ViewModel() {

    private val loading = MutableStateFlow(true)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val error = MutableStateFlow<String?>(null)
    private val info = MutableStateFlow<String?>(null)
    private val usageBytes = MutableStateFlow(0L)

    /**
     * Real available destinations. Empty until a trusted device exists.
     * The send flow must not offer phantom devices.
     */
    val destinations: StateFlow<List<Device>> = trustedDeviceRepository
        .trustedDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val uiItems = repository.items
        .map { items -> items.map(::toUi) }

    private val baseState: StateFlow<StagedContentUiState> = combine(
        uiItems, loading, selected, error, info
    ) { uiItems, isLoading, selectedIds, err, inf ->
        val valid = selectedIds.intersect(uiItems.mapTo(mutableSetOf()) { it.id })
        StagedContentUiState(
            isLoading = isLoading,
            items = uiItems,
            selectedIds = valid,
            errorMessage = err,
            infoMessage = inf,
            storageUsageBytes = 0L,
            totalCount = uiItems.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StagedContentUiState())

    val uiState: StateFlow<StagedContentUiState> = combine(baseState, usageBytes) { base, usage ->
        base.copy(storageUsageBytes = usage)
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

    fun toggleSelection(id: String) {
        selected.value = selected.value.toMutableSet().let {
            if (!it.add(id)) it.remove(id)
            it
        }
    }

    fun selectAll() {
        selected.value = repository.items.value.map { it.contentId }.toSet()
    }

    fun clearSelection() { selected.value = emptySet() }

    fun remove(id: String) {
        viewModelScope.launch {
            repository.remove(id)
                .onSuccess { selected.value = selected.value - id }
                .onFailure { error.value = it.message ?: "Failed to remove item" }
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
     * Attempts a real send through the connection service. The service is
     * expected to fail honestly until the Rust bridge exists.
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
                        is com.flowww.android.FlowwwError.ProviderUnavailable ->
                            "Transfer unavailable: the Rust transfer engine is not connected yet. Staged items remain local."
                        else -> e.message ?: "Send failed"
                    }
                }
        }
    }

    /** Preview handle for an item; TEXT/URL items carry their payload, files carry a content:// URI. */
    suspend fun preview(id: String): Result<StagedPreview> = repository.preview(id)

    fun consumeMessage() { info.value = null; error.value = null }

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
