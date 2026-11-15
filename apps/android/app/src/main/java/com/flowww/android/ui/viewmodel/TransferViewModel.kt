package com.flowww.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowww.android.connection.TransferDirection
import com.flowww.android.connection.TransferRecord
import com.flowww.android.connection.TransferState
import com.flowww.android.domain.models.HistoryFilter
import com.flowww.android.domain.models.HistoryGroup
import com.flowww.android.domain.models.TransferFilter
import com.flowww.android.domain.models.TransferItemUi
import com.flowww.android.domain.models.TransferHistoryUiState
import com.flowww.android.domain.models.TransfersUiState
import com.flowww.android.domain.repository.TransferRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class TransferViewModel(private val repository: TransferRepository) : ViewModel() {

    private val dashboardFilter = MutableStateFlow(TransferFilter.ACTIVE)
    private val historyFilter = MutableStateFlow(HistoryFilter.ALL)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TransfersUiState> = combine(
        repository.transfers, dashboardFilter, error
    ) { transfers, filter, err ->
        TransfersUiState(
            filter = filter,
            transfers = transfers.map { it.toUi() }.filter { matches(filter, it.status) },
            isLoading = false,
            errorMessage = err,
            infoMessage = if (transfers.isEmpty())
                "Transfer engine not connected (pending Rust/FFI integration). No fabricated transfers, progress, speed, or ETA are shown."
            else null
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransfersUiState())

    val historyState: StateFlow<TransferHistoryUiState> = combine(
        repository.transfers, historyFilter, error
    ) { transfers, filter, err ->
        val filtered = transfers.asSequence()
            .map { it.toUi() }
            .filter {
                when (filter) {
                    HistoryFilter.ALL -> true
                    HistoryFilter.SENT -> it.direction == TransferDirection.OUTGOING
                    HistoryFilter.RECEIVED -> it.direction == TransferDirection.INCOMING
                }
            }
            .sortedByDescending { it.createdAt }
            .toList()
        TransferHistoryUiState(
            filter = filter,
            groups = groupByDay(filtered),
            totalCount = filtered.size,
            isLoading = false,
            errorMessage = err,
            infoMessage = if (transfers.isEmpty())
                "History is not fabricated. It populates from persisted transfer events once Rust transfer integration is connected."
            else null
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransferHistoryUiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            error.value = null
            runCatching { repository.refresh() }
                .onFailure { error.value = it.message ?: "Failed to load transfers" }
        }
    }

    fun setFilter(filter: TransferFilter) { dashboardFilter.value = filter }

    fun setHistoryFilter(filter: HistoryFilter) { historyFilter.value = filter }

    fun findTransfer(id: String): TransferItemUi? = repository.transfers.value
        .map { it.toUi() }
        .firstOrNull { it.id == id }

    fun cancel(transferId: String) {
        viewModelScope.launch {
            repository.cancel(transferId)
                .onSuccess { cancelled ->
                    if (!cancelled) {
                        error.value = "Cancel is unavailable until the Rust transfer engine is connected."
                    } else {
                        refresh()
                    }
                }
                .onFailure { error.value = it.message ?: "Cancel unavailable" }
        }
    }

    fun retryUnavailable() {
        error.value = "Retry is unavailable until the Rust transfer engine is connected."
    }

    fun clearError() { error.value = null }

    private fun matches(filter: TransferFilter, state: TransferState): Boolean = when (filter) {
        TransferFilter.ALL -> true
        TransferFilter.ACTIVE -> when (state) {
            TransferState.OFFERED, TransferState.ACCEPTED, TransferState.CONNECTING, TransferState.TRANSFERRING -> true
            else -> false
        }
        TransferFilter.COMPLETED -> state == TransferState.COMPLETED
        TransferFilter.FAILED -> state == TransferState.FAILED || state == TransferState.CANCELLED
    }

    private fun groupByDay(items: List<TransferItemUi>): List<HistoryGroup> {
        val zone = ZoneId.systemDefault()
        return items
            .groupBy { item ->
                Instant.ofEpochMilli(item.createdAt).atZone(zone).toLocalDate().toEpochDay()
            }
            .toSortedMap(compareByDescending { it })
            .map { (day, groupItems) -> HistoryGroup(day, groupItems) }
    }

    private fun TransferRecord.toUi(): TransferItemUi {
        val deviceLabel = { id: String -> if (id.isBlank()) "Unknown device" else "Device ${id.take(6)}" }
        val local = "This device"
        val (source, destination) = when (direction) {
            TransferDirection.OUTGOING -> local to deviceLabel(destinationDeviceId)
            TransferDirection.INCOMING -> deviceLabel(sourceDeviceId) to local
        }
        val progress = if (size > 0) (bytesTransferred.toFloat() / size).coerceIn(0f, 1f) else 0f
        return TransferItemUi(
            id = id,
            filename = filename ?: "Transfer",
            direction = direction,
            sourceLabel = source,
            destinationLabel = destination,
            sizeBytes = size,
            bytesTransferred = bytesTransferred,
            progress = progress,
            status = status,
            createdAt = createdAt,
            completedAt = completedAt,
            mimeType = mimeType,
            error = error
        )
    }
}
