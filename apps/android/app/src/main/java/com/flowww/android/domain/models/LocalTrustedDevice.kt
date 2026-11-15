package com.flowww.android.domain.models

import java.io.Serializable

/**
 * How much a stored device record actually proves.
 *
 * This build has no secure pairing protocol, so a record can only ever be a
 * local reminder. It is never presented as verified trust.
 */
enum class DeviceTrustLevel(val label: String, val explanation: String) : Serializable {
    LOCAL_RECORD_ONLY(
        "Local record",
        "Saved on this phone only. It is not proof of a secure pairing."
    )
}

/**
 * A device record kept on this phone.
 *
 * The record exists so the user can organise devices they use with Flowww; it
 * is not a cryptographic trust decision.
 */
data class LocalTrustedDevice(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val fingerprint: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long? = null,
    val note: String? = null,
    val trustLevel: DeviceTrustLevel = DeviceTrustLevel.LOCAL_RECORD_ONLY
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
