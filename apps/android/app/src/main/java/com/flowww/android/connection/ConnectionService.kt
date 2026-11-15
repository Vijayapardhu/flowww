package com.flowww.android.connection

import com.flowww.android.FlowwwError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of initiating a content transfer.
 */
data class TransferInitiationResult(
    val transferId: String,
    val state: TransferState
)

/**
 * Interface for connection operations that delegate to the Rust core via FFI.
 */
interface ConnectionService {
    suspend fun getLocalDeviceInfo(): DeviceInfo
    suspend fun getTrustedDevices(): List<Device>
    suspend fun discoverPeers(): List<DiscoveredPeer>
    suspend fun connectToDevice(deviceId: String): Boolean
    suspend fun disconnectDevice(deviceId: String)
    suspend fun isDeviceConnected(deviceId: String): Boolean
    suspend fun initiateSend(deviceId: String, contentId: String): TransferInitiationResult
    suspend fun cancelTransfer(transferId: String): Boolean
    suspend fun getTransferStatus(transferId: String): TransferRecord?
    suspend fun getActiveTransfers(): List<TransferRecord>
    suspend fun shutdown()
    /** Asks a paired peer for the content it currently holds from a provider. */
    suspend fun requestContent(deviceId: String, provider: String): String
}

/**
 * Stub implementation of ConnectionService. In production, this delegates to
 * the Rust node via UniFFI/JNI FFI bindings.
 */
class ConnectionServiceImpl(
    private val scope: CoroutineScope
) : ConnectionService {

    override suspend fun getLocalDeviceInfo(): DeviceInfo {
        return withContext(Dispatchers.IO) {
            // In production, call Ffi.getDeviceInfo()
            DeviceInfo(
                deviceId = "fw_android_${java.util.UUID.randomUUID().toString().replace("-", "")}",
                deviceName = "Flowww Android",
                platform = Platform.Android,
                protocolVersion = "FLW/1.0",
                capabilities = hashSetOf(
                    "file_send",
                    "file_receive",
                    "url_send",
                    "url_receive",
                    "text_send",
                    "text_receive"
                )
            )
        }
    }

    override suspend fun getTrustedDevices(): List<Device> {
        return withContext(Dispatchers.IO) {
            // In production, query Rust node trust store
            emptyList()
        }
    }

    override suspend fun discoverPeers(): List<DiscoveredPeer> {
        return withContext(Dispatchers.IO) {
            // In production, use mDNS discovery via Rust
            emptyList()
        }
    }

    override suspend fun connectToDevice(deviceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            // In production, establish pinned mTLS connection via Rust.
            // Until the FFI bridge exists, connections cannot be established.
            false
        }
    }

    override suspend fun disconnectDevice(deviceId: String) {
        withContext(Dispatchers.IO) {
            // Close connection via Rust
        }
    }

    override suspend fun isDeviceConnected(deviceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            false
        }
    }

    override suspend fun initiateSend(deviceId: String, contentId: String): TransferInitiationResult {
        return withContext(Dispatchers.IO) {
            // No fabricated transfer state: until the Rust node is bridged via
            // UniFFI/JNI, send operations are unavailable. Callers must surface
            // this honestly instead of reporting a fake CONNECTING transfer.
            throw FlowwwError.ProviderUnavailable("Rust transfer node")
        }
    }

    override suspend fun cancelTransfer(transferId: String): Boolean {
        return withContext(Dispatchers.IO) {
            // In production, call Ffi.cancelTransfer(transferId).
            // Without the bridge no transfer can be cancelled.
            false
        }
    }

    override suspend fun getTransferStatus(transferId: String): TransferRecord? {
        return withContext(Dispatchers.IO) {
            null
        }
    }

    override suspend fun getActiveTransfers(): List<TransferRecord> {
        return withContext(Dispatchers.IO) {
            emptyList()
        }
    }

    override suspend fun shutdown() {
        withContext(Dispatchers.IO) {
            // Shutdown Rust node via FFI
        }
    }

    override suspend fun requestContent(deviceId: String, provider: String): String =
        withContext(Dispatchers.IO) {
            throw FlowwwError.ProviderUnavailable("content request path")
        }
}
