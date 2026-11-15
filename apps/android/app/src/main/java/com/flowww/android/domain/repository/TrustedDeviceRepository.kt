package com.flowww.android.domain.repository

import com.flowww.android.connection.Device
import kotlinx.coroutines.flow.Flow

interface TrustedDeviceRepository {
    fun trustedDevices(): Flow<List<Device>>
    suspend fun refresh(): Result<List<Device>>

    /** Removes a trusted device. Fails honestly while the Rust trust store is unconnected. */
    suspend fun forget(deviceId: String): Result<Unit>

    /** Renames a trusted device locally. Fails honestly while the Rust trust store is unconnected. */
    suspend fun rename(deviceId: String, displayName: String): Result<Unit>
}
