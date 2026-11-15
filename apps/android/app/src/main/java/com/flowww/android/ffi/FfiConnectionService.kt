package com.flowww.android.ffi

import com.flowww.android.FlowwwError
import com.flowww.android.connection.ConnectionService
import com.flowww.android.connection.Device
import com.flowww.android.connection.DeviceInfo
import com.flowww.android.connection.DiscoveredPeer
import com.flowww.android.connection.TransferInitiationResult
import com.flowww.android.connection.TransferRecord
import com.flowww.android.connection.TransferState
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.storage.StagedStore
import com.flowww.android.uniffi.FlowwwEvent
import com.flowww.android.uniffi.FlowwwException
import com.flowww.android.uniffi.TransferRecordDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
import com.flowww.android.ffi.FfiMappers.toDomain

/**
 * The real connection layer: everything is delegated to the Rust core through
 * the UniFFI surface owned by [NodeBridge]. No crypto, no protocol, no state
 * invention lives here.
 *
 * Semantics of the protocol (there are no persistent connections):
 * - [isDeviceConnected] / [connectToDevice] answer "is this peer pinned in the
 *   trust store?", because mTLS sessions here are opened per transfer, after
 *   pairing state is checked. There is no keep-alive to hold.
 * - [disconnectDevice] is a deliberate no-op: sessions close themselves when a
 *   transfer completes. It exists so callers can express intent honestly.
 *
 * [initiateSend] launches the send on the container's scope and returns as
 * soon as the node's first record for the send is known (the real transfer
 * id), not when the transfer completes; progress arrives via the event stream.
 * If no event arrives in time (a stalled send), it falls back to awaiting the
 * terminal record.
 */
class FfiConnectionService(
    private val bridge: NodeBridge,
    private val stagedStore: StagedStore,
    private val appScope: CoroutineScope
) : ConnectionService {

    override suspend fun getLocalDeviceInfo(): DeviceInfo {
        ensureStarted()
        try {
            return bridge.node().localDevice().toDomain()
        } catch (e: FlowwwException) {
            throw mapFlowwwError(e)
        }
    }

    override suspend fun getTrustedDevices(): List<Device> {
        ensureStarted()
        return try {
            bridge.node().listTrusted().map { it.toDomain() }
        } catch (e: FlowwwException) {
            throw mapFlowwwError(e)
        }
    }

    override suspend fun discoverPeers(): List<DiscoveredPeer> {
        ensureStarted()
        return try {
            bridge.node().listPeers().map { it.toDomain() }
        } catch (e: FlowwwException) {
            throw mapFlowwwError(e)
        }
    }

    override suspend fun connectToDevice(deviceId: String): Boolean {
        ensureStarted()
        return isTrusted(deviceId)
    }

    /** Deliberate no-op; see class documentation. */
    override suspend fun disconnectDevice(deviceId: String) = Unit

    override suspend fun isDeviceConnected(deviceId: String): Boolean {
        ensureStarted()
        return isTrusted(deviceId)
    }

    override suspend fun initiateSend(
        deviceId: String,
        contentId: String
    ): TransferInitiationResult = withContext(Dispatchers.IO) {
        ensureStarted()
        val item = stagedStore.get(contentId)
            ?: throw FlowwwError.ContentNotFound(contentId)
        if (item.lifecycleState != ContentLifecycleState.STAGED &&
            item.lifecycleState != ContentLifecycleState.RESERVED
        ) {
            throw FlowwwError.NotTransferable(contentId)
        }

        val localId = bridge.localDevice?.deviceId

        // Launch on the container's scope: the send outlives this call, and
        // progress flows through the node's event stream.
        val send = appScope.async(Dispatchers.IO) {
            try {
                when (item.contentType) {
                    ContentType.TEXT -> bridge.node().sendText(deviceId, item.contentReference)
                    ContentType.URL -> bridge.node().sendUrl(deviceId, item.contentReference)
                    // The Rust core sandboxes this path to the node's data dir;
                    // staged blobs live under filesDir, so they comply.
                    else -> bridge.node().sendFile(deviceId, item.contentReference)
                }
            } catch (e: FlowwwException) {
                throw mapFlowwwError(e)
            }
        }

        // The first outgoing record for this send yields the real id; take
        // whichever of event-vs-terminal-record produces it first.
        val outgoing = bridge.events
            .filterIsInstance<FlowwwEvent.TransferUpdated>()
            .map { it.record }
            .filter { record ->
                record.direction == "outgoing" && (localId == null || record.sourceDeviceId == localId)
            }
        val first = withTimeoutOrNull(SEND_START_TIMEOUT_MS) { outgoing.first() }
        val record: TransferRecordDto = first ?: send.await()
        TransferInitiationResult(record.id, record.status.toTransferStateStrict())
    }

    override suspend fun cancelTransfer(transferId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!bridge.isRunning) return@withContext false
            try {
                bridge.node().cancelTransfer(transferId)
            } catch (e: FlowwwException) {
                // A cancel that cannot reach the node is not a cancel.
                false
            }
        }

    override suspend fun getTransferStatus(transferId: String): TransferRecord? =
        withContext(Dispatchers.IO) {
            try {
                bridge.node().transferStatus(transferId)?.toDomain()
            } catch (e: FlowwwException) {
                throw mapFlowwwError(e)
            }
        }

    override suspend fun getActiveTransfers(): List<TransferRecord> =
        withContext(Dispatchers.IO) {
            ensureStarted()
            try {
                bridge.node().transferHistory()
                    .filter { it.isActive }
                    .mapNotNull { it.toDomain() }
            } catch (e: FlowwwException) {
                throw mapFlowwwError(e)
            }
        }

    override suspend fun shutdown() {
        bridge.stop()
    }

    override suspend fun requestContent(deviceId: String, provider: String): String =
        withContext(Dispatchers.IO) {
            ensureStarted()
            bridge.requestContent(deviceId, provider).getOrElse { error ->
                throw when (error) {
                    is FlowwwError -> error
                    else -> FlowwwError.Internal(error.message ?: "content request failed")
                }
            }
        }

    private suspend fun ensureStarted() {
        bridge.start()
    }

    private suspend fun isTrusted(deviceId: String): Boolean = try {
        bridge.node().listTrusted().any { it.deviceId == deviceId && it.trusted }
    } catch (e: FlowwwException) {
        throw mapFlowwwError(e)
    }

    private fun String.toTransferStateStrict(): TransferState = when (this) {
        "CREATED" -> TransferState.CREATED
        "OFFERED" -> TransferState.OFFERED
        "ACCEPTED" -> TransferState.ACCEPTED
        "CONNECTING" -> TransferState.CONNECTING
        "TRANSFERRING" -> TransferState.TRANSFERRING
        "PAUSED" -> TransferState.PAUSED
        "VERIFYING" -> TransferState.VERIFYING
        "COMPLETED" -> TransferState.COMPLETED
        "CANCELLED" -> TransferState.CANCELLED
        "FAILED" -> TransferState.FAILED
        else -> throw FlowwwError.Internal("Unrecognized transfer status from Rust: $this")
    }

    private companion object {
        /** Long enough for a healthy node to emit its first record. */
        const val SEND_START_TIMEOUT_MS = 15_000L
    }
}
