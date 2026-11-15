package com.flowww.android.data.repository

import com.flowww.android.data.local.LocalOperationStore
import com.flowww.android.domain.models.LocalOperation
import com.flowww.android.domain.models.LocalOperationStatus
import com.flowww.android.domain.models.LocalOperationType
import com.flowww.android.domain.repository.OperationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Stores real local operations. Nothing is recorded that did not happen on
 * the device.
 */
class DefaultOperationRepository(
    private val store: LocalOperationStore
) : OperationRepository {

    override val operations: StateFlow<List<LocalOperation>> = store.operations

    override suspend fun start(
        type: LocalOperationType,
        title: String,
        contentIds: List<String>,
        fileNames: List<String>,
        totalBytes: Long
    ): Result<LocalOperation> = withContext(Dispatchers.IO) {
        runCatching {
            val operation = LocalOperation(
                id = UUID.randomUUID().toString(),
                type = type,
                status = LocalOperationStatus.RUNNING,
                title = title,
                contentIds = contentIds,
                fileNames = fileNames,
                totalBytes = totalBytes,
                createdAt = System.currentTimeMillis()
            )
            store.put(operation)
            operation
        }
    }

    override suspend fun reportProgress(id: String, writtenBytes: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = store.find(id) ?: return@runCatching
                store.put(current.copy(writtenBytes = writtenBytes))
            }
        }

    override suspend fun succeed(id: String, destination: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = store.find(id) ?: return@runCatching
                store.put(
                    current.copy(
                        status = LocalOperationStatus.SUCCESS,
                        destination = destination ?: current.destination,
                        finishedAt = System.currentTimeMillis(),
                        writtenBytes = current.totalBytes,
                        errorMessage = null
                    )
                )
            }
        }

    override suspend fun fail(id: String, message: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = store.find(id) ?: return@runCatching
                store.put(
                    current.copy(
                        status = LocalOperationStatus.FAILED,
                        finishedAt = System.currentTimeMillis(),
                        errorMessage = message
                    )
                )
            }
        }

    override suspend fun cancel(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val current = store.find(id) ?: return@runCatching
            if (current.status.isTerminal) return@runCatching
            store.put(
                current.copy(
                    status = LocalOperationStatus.CANCELLED,
                    finishedAt = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun clear(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching { store.clear() }
    }

    override suspend fun find(id: String): LocalOperation? =
        withContext(Dispatchers.IO) { store.find(id) }

    override suspend fun statusOf(id: String): LocalOperationStatus? =
        withContext(Dispatchers.IO) { store.find(id)?.status }
}
