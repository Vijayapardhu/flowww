package com.flowww.android.domain.repository

import com.flowww.android.domain.models.LocalTrustedDevice
import kotlinx.coroutines.flow.Flow

/**
 * Device records kept on this phone.
 *
 * These are local records for organisation. They are deliberately not called
 * "trusted": without the Flowww secure pairing protocol the app cannot prove
 * that a device holds a verified key.
 */
interface TrustedDeviceRepository {
    /** Live stream of the stored device records. */
    fun trustedDevices(): Flow<List<LocalTrustedDevice>>

    /** Re-reads the store. */
    suspend fun refresh(): Result<List<LocalTrustedDevice>>

    /** Adds or replaces a record. */
    suspend fun add(record: LocalTrustedDevice): Result<Unit>

    /** Removes a record. */
    suspend fun forget(deviceId: String): Result<Unit>

    /** Renames a record. */
    suspend fun rename(deviceId: String, displayName: String): Result<Unit>
}
