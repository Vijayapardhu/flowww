package com.flowww.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowww.android.domain.models.LocalOperation
import com.flowww.android.domain.models.LocalOperationStatus
import com.flowww.android.domain.models.LocalOperationUi
import com.flowww.android.domain.models.OperationFilter
import com.flowww.android.domain.models.OperationGroup
import com.flowww.android.domain.models.OperationHistoryUiState
import com.flowww.android.domain.models.OperationSort
import com.flowww.android.domain.models.TransferManagerUiState
import com.flowww.android.domain.repository.OperationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * The transfer manager and the operation history.
 *
 * Both read the same real operation records: exports, Android shares, local
 * saves and handoffs that actually happened on this phone. Network transfers
 * are not part of this build and are never simulated.
 */
class TransferViewModel(
    private val operations: OperationRepository
) : ViewModel() {

    private val filter = MutableStateFlow(OperationFilter.ALL)
    private val historyFilter = MutableStateFlow(OperationFilter.ALL)
    private val sort = MutableStateFlow(OperationSort.NEWEST)
    private val query = MutableStateFlow("")
    private val error = MutableStateFlow<String?>(null)
    private val info = MutableStateFlow<String?>(null)
    private val loading = MutableStateFlow(true)

    private val operationItems = operations.operations.map { list -> list.map(::toUi) }

    private data class HistoryControls(
        val filter: OperationFilter = OperationFilter.ALL,
        val sort: OperationSort = OperationSort.NEWEST,
        val query: String = ""
    )

    private data class Messages(
        val error: String? = null,
        val info: String? = null
    )

    private val historyControls = combine(historyFilter, sort, query) { f, s, q ->
        HistoryControls(f, s, q)
    }

    private val messages = combine(error, info) { e, i -> Messages(e, i) }

    val uiState: StateFlow<TransferManagerUiState> = combine(
        operationItems, filter, loading, messages
    ) { items, activeFilter, isLoading, msgs ->
        val filtered = items.filter { matches(it, activeFilter) }
        TransferManagerUiState(
            isLoading = isLoading,
            operations = filtered,
            allOperations = items,
            runningCount = items.count { it.isRunning },
            filter = activeFilter,
            errorMessage = msgs.error,
            infoMessage = msgs.info
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransferManagerUiState())

    val historyState: StateFlow<OperationHistoryUiState> = combine(
        operationItems, historyControls, loading, messages
    ) { items, controls, isLoading, msgs ->
        val searched = items.filter { matchesQuery(it, controls.query) }
        val filtered = searched.filter { matches(it, controls.filter) }
        val sorted = sortOperations(filtered, controls.sort)
        OperationHistoryUiState(
            isLoading = isLoading,
            operations = sorted,
            groups = groupByDay(sorted),
            query = controls.query,
            filter = controls.filter,
            sort = controls.sort,
            totalCount = searched.size,
            errorMessage = msgs.error,
            infoMessage = msgs.info
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OperationHistoryUiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            loading.value = false
        }
    }

    fun setFilter(value: OperationFilter) { filter.value = value }

    fun setHistoryFilter(value: OperationFilter) { historyFilter.value = value }

    fun setSort(value: OperationSort) { sort.value = value }

    fun setQuery(value: String) { query.value = value }

    fun clearQuery() { query.value = "" }

    /** A single operation by id, for the details screen. */
    suspend fun findOperation(id: String): LocalOperationUi? =
        operations.find(id)?.let(::toUi)

    /** Cancels a running operation. */
    fun cancelOperation(id: String) {
        viewModelScope.launch {
            operations.cancel(id)
                .onSuccess { info.value = "Operation cancelled." }
                .onFailure { error.value = it.message ?: "The operation could not be cancelled" }
        }
    }

    /** Removes every history record. */
    fun clearHistory() {
        viewModelScope.launch {
            operations.clear()
                .onSuccess { count ->
                    info.value = if (count == 0) "There was nothing to clear." else "Cleared $count record(s)."
                }
                .onFailure { error.value = it.message ?: "The history could not be cleared" }
        }
    }

    fun clearError() { error.value = null }

    fun consumeMessage() { error.value = null; info.value = null }

    private fun matches(item: LocalOperationUi, activeFilter: OperationFilter): Boolean =
        when (activeFilter) {
            OperationFilter.ALL -> true
            OperationFilter.RUNNING -> item.status == LocalOperationStatus.RUNNING
            OperationFilter.SUCCESS -> item.status == LocalOperationStatus.SUCCESS
            OperationFilter.FAILED -> item.status == LocalOperationStatus.FAILED
            OperationFilter.CANCELLED -> item.status == LocalOperationStatus.CANCELLED
        }

    private fun matchesQuery(item: LocalOperationUi, rawQuery: String): Boolean {
        val needle = rawQuery.trim().lowercase()
        if (needle.isEmpty()) return true
        return item.title.lowercase().contains(needle) ||
            item.type.label.lowercase().contains(needle) ||
            item.destination?.lowercase()?.contains(needle) == true ||
            item.fileNames.any { it.lowercase().contains(needle) }
    }

    private fun sortOperations(items: List<LocalOperationUi>, activeSort: OperationSort): List<LocalOperationUi> =
        when (activeSort) {
            OperationSort.NEWEST -> items.sortedByDescending { it.createdAt }
            OperationSort.OLDEST -> items.sortedBy { it.createdAt }
            OperationSort.NAME -> items.sortedBy { it.title.lowercase() }
            OperationSort.SIZE -> items.sortedByDescending { it.totalBytes }
        }

    private fun groupByDay(items: List<LocalOperationUi>): List<OperationGroup> {
        val zone = ZoneId.systemDefault()
        return items
            .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate().toEpochDay() }
            .toList()
            .sortedByDescending { it.first }
            .map { (day, dayItems) -> OperationGroup(day, dayItems) }
    }

    private fun toUi(operation: LocalOperation) = LocalOperationUi(
        id = operation.id,
        type = operation.type,
        status = operation.status,
        title = operation.title,
        fileNames = operation.fileNames,
        totalBytes = operation.totalBytes,
        writtenBytes = operation.writtenBytes,
        destination = operation.destination,
        createdAt = operation.createdAt,
        finishedAt = operation.finishedAt,
        errorMessage = operation.errorMessage
    )
}
