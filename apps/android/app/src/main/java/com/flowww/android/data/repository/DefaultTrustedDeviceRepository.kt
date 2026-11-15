package com.flowww.android.data.repository

import com.flowww.android.data.local.TrustedDeviceStore
import com.flowww.android.domain.models.LocalTrustedDevice
import com.flowww.android.domain.repository.TrustedDeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Keeps the device records stored on this phone.
 *
 * The records are organisational only. They are never presented as a secure
 * pairing, because this build has no protocol that can prove one.
 */
class DefaultTrustedDeviceRepository(
    private val store: TrustedDeviceStore
) : TrustedDeviceRepository {

    override fun trustedDevices(): Flow<List<LocalTrustedDevice>> = store.devices

    override suspend fun refresh(): Result<List<LocalTrustedDevice>> = withContext(Dispatchers.IO) {
        runCatching { store.devices.value }
    }

    override suspend fun add(record: LocalTrustedDevice): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { store.put(record) }
        }

    override suspend fun forget(deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!store.remove(deviceId)) {
                throw IllegalStateException("That device is no longer in the list")
            }
        }
    }

    override suspend fun rename(deviceId: String, displayName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = store.find(deviceId)
                    ?: throw IllegalStateException("That device is no longer in the list")
                store.put(current.copy(displayName = displayName.trim()))
            }
        }
}
