package com.flowww.android.data.repository

import com.flowww.android.connection.ConnectionService
import com.flowww.android.connection.TransferRecord
import com.flowww.android.data.local.TransferHistoryStore
import com.flowww.android.domain.repository.TransferRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class DefaultTransferRepository(
    private val connectionService: ConnectionService,
    private val historyStore: TransferHistoryStore,
    private val scope: CoroutineScope
) : TransferRepository {

    override val transfers: StateFlow<List<TransferRecord>> = historyStore.records

    init {
        // Merge anything the service already knows (currently none) into the
        // persistent history store so terminal records are kept across restarts.
        scope.launch { mergeServiceRecords() }
    }

    override suspend fun refresh(): Result<Unit> = runCatching {
        mergeServiceRecords()
    }

    private suspend fun mergeServiceRecords() = withContext(Dispatchers.IO) {
        runCatching { connectionService.getActiveTransfers() }
            .onSuccess { serviceRecords ->
                if (serviceRecords.isNotEmpty()) {
                    historyStore.recordAll(serviceRecords)
                }
            }
    }

    override suspend fun cancel(transferId: String): Result<Boolean> =
        runCatching { connectionService.cancelTransfer(transferId) }

    override suspend fun recordTransfer(record: TransferRecord): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { historyStore.record(record) }
    }
}
