package com.flowww.android.test.util

import com.flowww.android.FlowwwError
import com.flowww.android.connection.Device
import com.flowww.android.connection.DeviceInfo
import com.flowww.android.connection.Platform
import com.flowww.android.connection.TransferDirection
import com.flowww.android.connection.TransferRecord
import com.flowww.android.connection.TransferState
import com.flowww.android.connection.isTerminal
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagedContent
import com.flowww.android.domain.models.FlowwwSettings
import com.flowww.android.domain.repository.SettingsRepository
import com.flowww.android.domain.repository.StagedContentRepository
import com.flowww.android.domain.repository.StagedPreview
import com.flowww.android.domain.repository.TransferRepository
import com.flowww.android.domain.repository.TrustedDeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Builds a [StagedContent] without touching any filesystem paths. */
fun stagedItem(
    id: String,
    name: String = "item-$id",
    contentType: ContentType = ContentType.TEXT,
    size: Long? = 10L
) = StagedContent(
    contentId = id,
    contentType = contentType,
    lifecycleState = ContentLifecycleState.STAGED,
    displayName = name,
    mimeType = "text/plain",
    size = size,
    sourceApplication = null,
    createdAt = System.currentTimeMillis(),
    expiresAt = Long.MAX_VALUE,
    contentReference = "inline-payload-$id",
    authorizationContext = null
)

/** Staged-content fake backed by a live StateFlow; send() fails honestly. */
class FakeStagedRepo(initial: List<StagedContent> = emptyList()) : StagedContentRepository {
    private val _items = MutableStateFlow(initial)
    override val items: StateFlow<List<StagedContent>> = _items

    var sendCalls = 0
        private set

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)

    override suspend fun remove(contentId: String): Result<Unit> = runCatching {
        _items.value = _items.value.filterNot { it.contentId == contentId }
    }

    override suspend fun removeMany(contentIds: Set<String>): Result<Int> = runCatching {
        val before = _items.value.size
        _items.value = _items.value.filterNot { it.contentId in contentIds }
        before - _items.value.size
    }

    override suspend fun clearExpired(): Result<Int> = Result.success(0)
    override suspend fun cleanupOrphans(): Result<Int> = Result.success(0)

    override suspend fun storageUsage(): Result<Long> =
        Result.success(_items.value.sumOf { it.size ?: 0L })

    override suspend fun preview(contentId: String): Result<StagedPreview> =
        _items.value.firstOrNull { it.contentId == contentId }
            ?.let { Result.success(StagedPreview.Text(it.contentReference, it.mimeType)) }
            ?: Result.failure(FlowwwError.ContentNotFound(contentId))

    override suspend fun send(contentIds: Set<String>, destinationDeviceId: String): Result<Unit> {
        sendCalls++
        return Result.failure(FlowwwError.ProviderUnavailable("rust-transfer"))
    }
}

/** Trusted-device fake; empty by default so send flows surface the honest state. */
class FakeTrustedRepo(initial: List<Device> = emptyList()) : TrustedDeviceRepository {
    private val _devices = MutableStateFlow(initial)

    override fun trustedDevices(): Flow<List<Device>> = _devices

    fun add(device: Device) { _devices.value = _devices.value + device }

    override suspend fun refresh(): Result<List<Device>> = Result.success(_devices.value)

    override suspend fun forget(deviceId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Trusted-device removal is unavailable until the Rust trust store is connected"))

    override suspend fun rename(deviceId: String, displayName: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Renaming is unavailable until the Rust trust store is connected"))
}

fun trustedDevice(id: String, name: String) = Device(
    info = DeviceInfo(
        deviceId = id,
        deviceName = name,
        platform = Platform.Windows,
        protocolVersion = "FLW/1.0"
    ),
    publicKeyFingerprint = "fp-$id",
    trusted = true,
    lastSeenMs = null,
    createdAtMs = System.currentTimeMillis()
)

/** Settings fake that actually applies updates so persistence is observable. */
class FakeSettingsRepo(initial: FlowwwSettings = FlowwwSettings()) : SettingsRepository {
    val current = MutableStateFlow(initial)
    private val _settings = MutableStateFlow<Result<FlowwwSettings>>(Result.success(initial))
    override val settings: Flow<Result<FlowwwSettings>> = _settings

    val updates = mutableListOf<FlowwwSettings>()

    override suspend fun update(transform: (FlowwwSettings) -> FlowwwSettings): Result<Unit> {
        val updated = transform(current.value)
        current.value = updated
        _settings.value = Result.success(updated)
        updates += updated
        return Result.success(Unit)
    }
}

/** Transfer fake with a controllable list; cancel() fails honestly by default. */
class FakeTransferRepo(initial: List<TransferRecord> = emptyList()) : TransferRepository {
    private val _transfers = MutableStateFlow(initial)
    override val transfers: StateFlow<List<TransferRecord>> = _transfers

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)

    override suspend fun cancel(transferId: String): Result<Boolean> = Result.success(false)

    override suspend fun recordTransfer(record: TransferRecord): Result<Unit> = runCatching {
        _transfers.value = _transfers.value + record
    }

    fun add(record: TransferRecord) { _transfers.value = _transfers.value + record }

    companion object {
        fun record(
            id: String,
            status: TransferState = TransferState.COMPLETED,
            direction: TransferDirection = TransferDirection.OUTGOING,
            createdAt: Long = System.currentTimeMillis()
        ) = TransferRecord(
            id = id,
            sourceDeviceId = if (direction == TransferDirection.OUTGOING) "" else "src-dev",
            destinationDeviceId = if (direction == TransferDirection.OUTGOING) "dst-dev" else "",
            filename = "file-$id.bin",
            size = 1000L,
            status = status,
            route = "direct",
            direction = direction,
            bytesTransferred = 0L,
            error = null,
            createdAt = createdAt,
            completedAt = if (status.isTerminal()) createdAt + 100 else null,
            mimeType = "application/octet-stream"
        )
    }
}
