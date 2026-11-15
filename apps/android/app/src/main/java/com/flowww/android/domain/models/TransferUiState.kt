package com.flowww.android.domain.models

import com.flowww.android.connection.TransferDirection
import com.flowww.android.connection.TransferState

enum class TransferFilter { ALL, ACTIVE, COMPLETED, FAILED }

enum class HistoryFilter { ALL, SENT, RECEIVED }

/** UI-safe projection of a transfer record. Contains no paths or secrets. */
data class TransferItemUi(
    val id: String,
    val filename: String,
    val direction: TransferDirection,
    val sourceLabel: String,
    val destinationLabel: String,
    val sizeBytes: Long,
    val bytesTransferred: Long,
    val progress: Float,
    val status: TransferState,
    val createdAt: Long,
    val completedAt: Long?,
    val mimeType: String?,
    val error: String?
)

data class TransfersUiState(
    val filter: TransferFilter = TransferFilter.ACTIVE,
    val transfers: List<TransferItemUi> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val infoMessage: String? = "Transfer engine not connected (pending Rust/FFI integration). No fabricated progress is shown."
)

/** A dated bucket of history rows, keyed by local date (epoch day). */
data class HistoryGroup(
    val dateEpochDay: Long,
    val items: List<TransferItemUi>
)

data class TransferHistoryUiState(
    val filter: HistoryFilter = HistoryFilter.ALL,
    val groups: List<HistoryGroup> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val infoMessage: String? = "History is not fabricated. It populates from persisted transfer events once Rust transfer integration is connected."
)
