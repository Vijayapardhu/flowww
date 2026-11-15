package com.flowww.android.data.repository

import com.flowww.android.connection.ConnectionService
import com.flowww.android.connection.Device
import com.flowww.android.domain.repository.TrustedDeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DefaultTrustedDeviceRepository(private val connectionService: ConnectionService) : TrustedDeviceRepository {
    override fun trustedDevices(): Flow<List<Device>> = flow { emit(connectionService.getTrustedDevices()) }

    override suspend fun refresh(): Result<List<Device>> = runCatching { connectionService.getTrustedDevices() }

    override suspend fun forget(deviceId: String): Result<Unit> = Result.failure(
        UnsupportedOperationException("Forgetting a trusted device requires the Rust trust store, which is not connected yet.")
    )

    override suspend fun rename(deviceId: String, displayName: String): Result<Unit> = Result.failure(
        UnsupportedOperationException("Renaming a trusted device requires the Rust trust store, which is not connected yet.")
    )
}
