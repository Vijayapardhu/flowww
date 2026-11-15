package com.flowww.android.domain.repository

import com.flowww.android.domain.models.LocalOperation
import com.flowww.android.domain.models.LocalOperationStatus
import com.flowww.android.domain.models.LocalOperationType
import kotlinx.coroutines.flow.StateFlow

/**
 * Local operations performed on staged content.
 *
 * Every record in this repository corresponds to an operation that really
 * started on the device: exports, Android shares, local saves and handoffs to
 * other apps. Network transfers are not part of this repository — they are
 * unavailable in the standalone build.
 */
interface OperationRepository {
    /** All known operations, newest first. */
    val operations: StateFlow<List<LocalOperation>>

    /** Registers a new running operation. */
    suspend fun start(
        type: LocalOperationType,
        title: String,
        contentIds: List<String>,
        fileNames: List<String>,
        totalBytes: Long
    ): Result<LocalOperation>

    /** Updates the number of bytes written so far. */
    suspend fun reportProgress(id: String, writtenBytes: Long): Result<Unit>

    /** Marks the operation as finished successfully. */
    suspend fun succeed(id: String, destination: String?): Result<Unit>

    /** Marks the operation as failed, with the reason shown to the user. */
    suspend fun fail(id: String, message: String): Result<Unit>

    /** Marks a running operation as cancelled. */
    suspend fun cancel(id: String): Result<Unit>

    /** Removes every record; returns how many were removed. */
    suspend fun clear(): Result<Int>

    /** A single operation by id, or null. */
    suspend fun find(id: String): LocalOperation?

    /** Status helper for callers that only need the state of an operation. */
    suspend fun statusOf(id: String): LocalOperationStatus?
}
