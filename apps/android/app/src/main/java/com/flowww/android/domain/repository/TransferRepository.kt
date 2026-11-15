package com.flowww.android.domain.repository

import com.flowww.android.connection.TransferRecord
import kotlinx.coroutines.flow.StateFlow

interface TransferRepository {
    /** Live stream of every locally known transfer (store + service). Never empty-fabricated. */
    val transfers: StateFlow<List<TransferRecord>>

    suspend fun refresh(): Result<Unit>

    /** Cancellation only succeeds when the underlying service can actually cancel. */
    suspend fun cancel(transferId: String): Result<Boolean>

    /** Called by the future Rust event bridge whenever a transfer event arrives. */
    suspend fun recordTransfer(record: TransferRecord): Result<Unit>
}
