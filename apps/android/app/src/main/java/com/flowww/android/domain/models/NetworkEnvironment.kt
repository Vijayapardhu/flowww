package com.flowww.android.domain.models

/** Whether the permission needed to read Wi-Fi scan results is available. */
enum class WifiPermissionState {
    /** The permission is granted, scan results can be read. */
    GRANTED,

    /** The user has not granted the permission yet. */
    NOT_GRANTED,

    /** No permission is needed on this platform version. */
    NOT_REQUIRED
}

/** A Wi-Fi network seen by this phone. Not a Flowww peer. */
data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val signalLevel: Int,
    val frequencyMhz: Int,
    val capabilities: String
) {
    val signalLabel: String
        get() = when {
            signalLevel >= -55 -> "Excellent"
            signalLevel >= -67 -> "Good"
            signalLevel >= -75 -> "Fair"
            else -> "Weak"
        }
}

/**
 * The network environment of this phone.
 *
 * This is real device state: connectivity, transport, Wi-Fi radio state and
 * the permission needed to read scan results. It says nothing about Flowww
 * peers.
 */
data class NetworkEnvironment(
    val isConnected: Boolean = false,
    val transport: String = "Offline",
    val isWifiEnabled: Boolean = false,
    val permissionState: WifiPermissionState = WifiPermissionState.NOT_REQUIRED,
    val requiredPermission: String? = null,
    val networks: List<WifiNetwork> = emptyList(),
    val lastScanAt: Long? = null
) {
    val canScan: Boolean
        get() = isWifiEnabled && permissionState != WifiPermissionState.NOT_GRANTED
}
