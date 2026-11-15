package com.flowww.android.connection

import com.flowww.android.content.ContentType
import java.io.Serializable

/**
 * Platform types supported by the flowww protocol.
 */
enum class Platform {
    Windows, Android, Web, Macos, Linux, Unknown
}

/**
 * Public, non-secret information about a device.
 */
data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val platform: Platform,
    val protocolVersion: String,
    val capabilities: Set<String> = emptySet()
)

/**
 * A persisted relationship with another device.
 */
data class Device(
    val info: DeviceInfo,
    val publicKeyFingerprint: String,
    val trusted: Boolean,
    val lastSeenMs: Long?,
    val createdAtMs: Long
)

/**
 * Pairing state of a device relationship.
 */
enum class PairingState {
    UNPAIRED, DISCOVERED, PAIRING, AWAITING_APPROVAL, PAIRED, CONNECTED, DISCONNECTED, REVOKED
}

/**
 * A discovered peer on the network.
 */
data class DiscoveredPeer(
    val device: DeviceInfo,
    val endpoint: String?,
    val paired: Boolean = false
)

/**
 * Transfer direction.
 */
enum class TransferDirection { OUTGOING, INCOMING }

/**
 * Transfer state.
 */
enum class TransferState {
    OFFERED, ACCEPTED, CONNECTING, TRANSFERRING, COMPLETED, CANCELLED, FAILED
}

fun TransferState.isTerminal(): Boolean =
    this == TransferState.COMPLETED ||
        this == TransferState.CANCELLED ||
        this == TransferState.FAILED

/**
 * Transfer record persisted locally.
 */
data class TransferRecord(
    val id: String,
    val sourceDeviceId: String,
    val destinationDeviceId: String,
    val filename: String?,
    val size: Long,
    val status: TransferState,
    val route: String,
    val direction: TransferDirection,
    val bytesTransferred: Long,
    val error: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val mimeType: String?
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Result of initiating a transfer.
 */
data class InitiateResult(
    val transferId: String,
    val status: TransferState
)

/**
 * Capabilities a device can offer.
 */
enum class Capability {
    FileSend, FileReceive, UrlSend, UrlReceive, TextSend, TextReceive, SpatialTarget
}

fun Capability.asString(): String = when (this) {
    Capability.FileSend -> "file_send"
    Capability.FileReceive -> "file_receive"
    Capability.UrlSend -> "url_send"
    Capability.UrlReceive -> "url_receive"
    Capability.TextSend -> "text_send"
    Capability.TextReceive -> "text_receive"
    Capability.SpatialTarget -> "spatial_target"
}
