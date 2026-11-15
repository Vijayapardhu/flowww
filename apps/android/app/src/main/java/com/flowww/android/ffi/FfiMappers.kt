package com.flowww.android.ffi

import com.flowww.android.connection.Device
import com.flowww.android.connection.DeviceInfo
import com.flowww.android.connection.DiscoveredPeer
import com.flowww.android.connection.Platform
import com.flowww.android.connection.TransferDirection
import com.flowww.android.connection.TransferRecord
import com.flowww.android.connection.TransferState
import com.flowww.android.uniffi.DeviceDto
import com.flowww.android.uniffi.PeerDto
import com.flowww.android.uniffi.TransferRecordDto
import com.flowww.android.uniffi.TrustedDeviceDto

/**
 * Mapping between the UniFFI DTOs and the Android domain models.
 *
 * The Rust core owns every field: these functions only translate shapes.
 * No value is invented here, and anything the Kotlin side cannot interpret is
 * dropped (null) rather than fabricated - callers treat null as "not a record
 * this app understands yet".
 */
internal object FfiMappers {

    fun DeviceDto.toDomain(): DeviceInfo = DeviceInfo(
        deviceId = deviceId,
        deviceName = deviceName,
        platform = platform.toPlatform(),
        protocolVersion = protocolVersion,
        capabilities = capabilities.toSet()
    )

    fun TrustedDeviceDto.toDomain(): Device = Device(
        info = DeviceInfo(
            deviceId = deviceId,
            deviceName = deviceName,
            platform = platform.toPlatform(),
            protocolVersion = "",
            capabilities = emptySet()
        ),
        publicKeyFingerprint = publicKeyFingerprint,
        trusted = trusted,
        lastSeenMs = lastSeenMs?.toLong(),
        createdAtMs = createdAtMs.toLong()
    )

    fun PeerDto.toDomain(): DiscoveredPeer = DiscoveredPeer(
        device = DeviceInfo(
            deviceId = deviceId,
            deviceName = name,
            platform = platform.toPlatform(),
            protocolVersion = "",
            capabilities = emptySet()
        ),
        endpoint = address,
        paired = paired
    )

    /**
     * A Rust transfer record, or null when the record carries a status or
     * direction this build does not know how to represent. Dropping the
     * record is safer than guessing at its meaning.
     */
    fun TransferRecordDto.toDomain(): TransferRecord? {
        val state = status.toState() ?: return null
        val direction = direction.toDirection() ?: return null
        return TransferRecord(
            id = id,
            sourceDeviceId = sourceDeviceId,
            destinationDeviceId = destinationDeviceId,
            filename = filename,
            size = size.toLong(),
            status = state,
            route = route,
            direction = direction,
            bytesTransferred = bytesTransferred.toLong(),
            error = error,
            createdAt = createdAt.toLong(),
            completedAt = completedAt?.toLong(),
            mimeType = mimeType
        )
    }

    private fun String.toPlatform(): Platform = when (this) {
        "windows" -> Platform.Windows
        "android" -> Platform.Android
        "web" -> Platform.Web
        "macos" -> Platform.Macos
        "linux" -> Platform.Linux
        else -> Platform.Unknown
    }

    private fun String.toState(): TransferState? = when (this) {
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
        else -> null
    }

    private fun String.toDirection(): TransferDirection? = when (this) {
        "outgoing" -> TransferDirection.OUTGOING
        "incoming" -> TransferDirection.INCOMING
        else -> null
    }
}
