package com.flowww.android.domain.models

/** Filter for the local operation list. */
enum class OperationFilter(val label: String) {
    ALL("All"),
    RUNNING("Running"),
    SUCCESS("Success"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

/** Sort order for the local operation list. */
enum class OperationSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("Name"),
    SIZE("Size")
}

/** UI projection of a local operation. */
data class LocalOperationUi(
    val id: String,
    val type: LocalOperationType,
    val status: LocalOperationStatus,
    val title: String,
    val fileNames: List<String>,
    val totalBytes: Long,
    val writtenBytes: Long,
    val destination: String?,
    val createdAt: Long,
    val finishedAt: Long?,
    val errorMessage: String?
) {
    val progress: Float
        get() = if (totalBytes <= 0L) {
            if (status == LocalOperationStatus.SUCCESS) 1f else 0f
        } else {
            (writtenBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        }

    val isRunning: Boolean get() = status == LocalOperationStatus.RUNNING
}

/** Operations grouped by day for the history screen. */
data class OperationGroup(
    val dateEpochDay: Long,
    val items: List<LocalOperationUi>
)

/** The transfer manager screen state. */
data class TransferManagerUiState(
    val isLoading: Boolean = true,
    val operations: List<LocalOperationUi> = emptyList(),
    val allOperations: List<LocalOperationUi> = emptyList(),
    val runningCount: Int = 0,
    val filter: OperationFilter = OperationFilter.ALL,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

/** The operation history screen state. */
data class OperationHistoryUiState(
    val isLoading: Boolean = true,
    val operations: List<LocalOperationUi> = emptyList(),
    val groups: List<OperationGroup> = emptyList(),
    val query: String = "",
    val filter: OperationFilter = OperationFilter.ALL,
    val sort: OperationSort = OperationSort.NEWEST,
    val totalCount: Int = 0,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)
