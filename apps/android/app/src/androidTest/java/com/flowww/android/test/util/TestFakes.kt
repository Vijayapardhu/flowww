package com.flowww.android.test.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.flowww.android.FlowwwError
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagedContent
import com.flowww.android.domain.models.ContentKind
import com.flowww.android.domain.models.FlowwwSettings
import com.flowww.android.domain.models.LocalContentHandle
import com.flowww.android.domain.models.LocalOperation
import com.flowww.android.domain.models.LocalOperationStatus
import com.flowww.android.domain.models.LocalOperationType
import com.flowww.android.domain.models.LocalTrustedDevice
import com.flowww.android.domain.models.NetworkEnvironment
import com.flowww.android.domain.models.WifiNetwork
import com.flowww.android.domain.models.WifiPermissionState
import com.flowww.android.domain.repository.ContentShareBuilder
import com.flowww.android.domain.repository.DiscoveryRepository
import com.flowww.android.domain.repository.DiscoveryResult
import com.flowww.android.domain.repository.LocalContentGateway
import com.flowww.android.domain.repository.OperationRepository
import com.flowww.android.domain.repository.PairingRepository
import com.flowww.android.domain.repository.SettingsRepository
import com.flowww.android.domain.repository.StagedContentRepository
import com.flowww.android.domain.repository.StagedPreview
import com.flowww.android.domain.repository.TrustedDeviceRepository
import com.flowww.android.pairing.PairingResult
import com.flowww.android.pairing.PairingUriData
import com.flowww.android.connection.DeviceInfo
import com.flowww.android.connection.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID

// ========================================================================
// Staged Content
// ========================================================================

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

// ========================================================================
// Trusted Devices (LocalTrustedDevice, not Device)
// ========================================================================

/** Trusted-device fake backed by a live StateFlow. */
class FakeTrustedRepo(initial: List<LocalTrustedDevice> = emptyList()) : TrustedDeviceRepository {
    private val _devices = MutableStateFlow(initial)

    override fun trustedDevices(): Flow<List<LocalTrustedDevice>> = _devices

    fun addDevice(device: LocalTrustedDevice) { _devices.value = _devices.value + device }

    override suspend fun refresh(): Result<List<LocalTrustedDevice>> = Result.success(_devices.value)

    override suspend fun add(record: LocalTrustedDevice): Result<Unit> = runCatching {
        _devices.value = _devices.value + record
    }

    override suspend fun forget(deviceId: String): Result<Unit> = runCatching {
        _devices.value = _devices.value.filterNot { it.deviceId == deviceId }
    }

    override suspend fun rename(deviceId: String, displayName: String): Result<Unit> = runCatching {
        val idx = _devices.value.indexOfFirst { it.deviceId == deviceId }
        if (idx >= 0) {
            val d = _devices.value[idx]
            _devices.value = _devices.value.toMutableList().also { it[idx] = d.copy(displayName = displayName) }
        }
    }
}

fun localTrustedDevice(
    id: String = "local_${UUID.randomUUID()}",
    name: String = "Test Device",
    platform: String = "Windows",
    fingerprint: String? = "fp-$id"
) = LocalTrustedDevice(
    deviceId = id,
    displayName = name,
    platform = platform,
    fingerprint = fingerprint
)

// ========================================================================
// Settings
// ========================================================================

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

// ========================================================================
// Operations (LocalOperation)
// ========================================================================

/** Operation fake with a controllable list. */
class FakeOperationRepo(initial: List<LocalOperation> = emptyList()) : OperationRepository {
    private val _operations = MutableStateFlow(initial)
    override val operations: StateFlow<List<LocalOperation>> = _operations.asStateFlow()

    override suspend fun start(
        type: LocalOperationType,
        title: String,
        contentIds: List<String>,
        fileNames: List<String>,
        totalBytes: Long
    ): Result<LocalOperation> {
        val op = LocalOperation(
            id = UUID.randomUUID().toString(),
            type = type,
            status = LocalOperationStatus.RUNNING,
            title = title,
            contentIds = contentIds,
            fileNames = fileNames,
            totalBytes = totalBytes,
            createdAt = System.currentTimeMillis()
        )
        _operations.value = (_operations.value + op).sortedByDescending { it.createdAt }
        return Result.success(op)
    }

    override suspend fun reportProgress(id: String, writtenBytes: Long): Result<Unit> = runCatching {
        val idx = _operations.value.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val op = _operations.value[idx]
            _operations.value = _operations.value.toMutableList().also {
                it[idx] = op.copy(writtenBytes = writtenBytes)
            }
        }
    }

    override suspend fun succeed(id: String, destination: String?): Result<Unit> = runCatching {
        val idx = _operations.value.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val op = _operations.value[idx]
            _operations.value = _operations.value.toMutableList().also {
                it[idx] = op.copy(
                    status = LocalOperationStatus.SUCCESS,
                    destination = destination,
                    finishedAt = System.currentTimeMillis()
                )
            }
        }
    }

    override suspend fun fail(id: String, message: String): Result<Unit> = runCatching {
        val idx = _operations.value.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val op = _operations.value[idx]
            _operations.value = _operations.value.toMutableList().also {
                it[idx] = op.copy(
                    status = LocalOperationStatus.FAILED,
                    errorMessage = message,
                    finishedAt = System.currentTimeMillis()
                )
            }
        }
    }

    override suspend fun cancel(id: String): Result<Unit> = runCatching {
        val idx = _operations.value.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val op = _operations.value[idx]
            _operations.value = _operations.value.toMutableList().also {
                it[idx] = op.copy(
                    status = LocalOperationStatus.CANCELLED,
                    finishedAt = System.currentTimeMillis()
                )
            }
        }
    }

    override suspend fun clear(): Result<Int> = runCatching {
        val before = _operations.value.size
        _operations.value = emptyList()
        before
    }

    override suspend fun find(id: String): LocalOperation? =
        _operations.value.firstOrNull { it.id == id }

    override suspend fun statusOf(id: String): LocalOperationStatus? =
        _operations.value.firstOrNull { it.id == id }?.status

    fun add(op: LocalOperation) { _operations.value = _operations.value + op }
}

fun transferOperation(
    id: String,
    fileName: String,
    type: LocalOperationType = LocalOperationType.LOCAL_EXPORT,
    status: LocalOperationStatus = LocalOperationStatus.SUCCESS,
    totalBytes: Long = 100_000L,
    title: String = "Exported 1 item(s)"
) = LocalOperation(
    id = id,
    type = type,
    status = status,
    title = title,
    fileNames = listOf(fileName),
    contentIds = listOf("content-$id"),
    totalBytes = totalBytes,
    writtenBytes = totalBytes,
    destination = "Downloads",
    createdAt = System.currentTimeMillis() - 60_000L,
    finishedAt = System.currentTimeMillis()
)

// ========================================================================
// Local Content Gateway
// ========================================================================

/** Gateway fake that yields inline TEXT handles. */
class FakeGateway(initial: Map<String, LocalContentHandle> = emptyMap()) : LocalContentGateway {
    private val handles = initial.toMutableMap()

    fun put(handle: LocalContentHandle) { handles[handle.contentId] = handle }

    override suspend fun describe(contentId: String): Result<LocalContentHandle> =
        handles[contentId]?.let { Result.success(it) }
            ?: Result.failure(FlowwwError.ContentNotFound(contentId))

    override suspend fun describeAll(contentIds: List<String>): Result<List<LocalContentHandle>> = runCatching {
        contentIds.map { id ->
            handles[id] ?: throw FlowwwError.ContentNotFound(id)
        }
    }

    override suspend fun openStream(contentId: String): Result<InputStream> = runCatching {
        handles[contentId]?.text?.let { ByteArrayInputStream(it.toByteArray()) }
            ?: throw FlowwwError.ContentNotFound(contentId)
    }
}

fun textHandle(
    id: String,
    name: String = "Text $id",
    text: String = "Hello from Flowww",
    mimeType: String = "text/plain"
) = LocalContentHandle(
    contentId = id,
    displayName = name,
    mimeType = mimeType,
    size = text.length.toLong(),
    kind = ContentKind.TEXT,
    text = text
)

fun blobHandle(
    id: String,
    name: String = "File $id",
    mimeType: String = "application/octet-stream",
    size: Long = 1024L,
    uri: Uri = Uri.parse("content://com.flowww.fileprovider/test/$id")
) = LocalContentHandle(
    contentId = id,
    displayName = name,
    mimeType = mimeType,
    size = size,
    kind = ContentKind.BLOB,
    uri = uri
)

// ========================================================================
// Content Share Builder
// ========================================================================

/** Share builder fake that returns a canned intent. */
class FakeShareBuilder(initial: Result<Pair<Intent, String>> = Result.failure(IllegalStateException("Not configured"))) : ContentShareBuilder {
    var response = initial

    override fun build(handles: List<LocalContentHandle>): Result<Pair<Intent, String>> = response
}

// ========================================================================
// Discovery (Nearby)
// ========================================================================

class FakeDiscoveryRepo(
    initialEnv: NetworkEnvironment = NetworkEnvironment(
        isConnected = true,
        transport = "Wi-Fi",
        isWifiEnabled = true,
        permissionState = WifiPermissionState.GRANTED,
        requiredPermission = null
    ),
    initialResult: DiscoveryResult = DiscoveryResult.Success(emptyList())
) : DiscoveryRepository {
    var env = initialEnv
    var result = initialResult
    var scan = Result.success(emptyList<WifiNetwork>())

    override fun discover(): Flow<DiscoveryResult> =
        MutableStateFlow(result).asStateFlow()

    override suspend fun refresh(): DiscoveryResult = result

    override suspend fun environment(): NetworkEnvironment = env

    override suspend fun scanNetworks(): Result<List<WifiNetwork>> = scan
}

// ========================================================================
// Pairing
// ========================================================================

/** Pairing fake: validation and pairing are independently configurable. */
class FakePairingRepo(
    var validationResult: Result<PairingUriData> = Result.failure(FlowwwError.InvalidUri("unset")),
    var pairResult: Result<PairingResult> = Result.success(
        PairingResult.Unavailable("The secure pairing protocol is not available in this build.")
    )
) : PairingRepository {
    override suspend fun validate(uri: String): Result<PairingUriData> = validationResult
    override suspend fun pair(uri: String): Result<PairingResult> = pairResult
}

fun pairingUriData(expiresAt: Long = System.currentTimeMillis() + 60_000L) = PairingUriData(
    version = 1,
    sessionId = "sess-test",
    token = "tok-test",
    hostDevice = DeviceInfo(
        deviceId = "win-1",
        deviceName = "Office PC",
        platform = Platform.Windows,
        protocolVersion = "FLW/1.0"
    ),
    hostPublicKey = "key",
    hostTlsFingerprint = "fp",
    endpoint = "192.168.1.2:4400",
    nonce = "nonce-1",
    expiresAt = expiresAt
)

// ========================================================================
// Context helper for LocalContentHandoff (needs a real Context)
// ========================================================================

/** Wraps the Android Context for use in instrumented tests. */
class TestContextProvider(val context: Context) {
    operator fun invoke(): Context = context
}