package com.flowww.android.domain.models

import com.flowww.android.connection.DiscoveredPeer
import com.flowww.android.connection.Device

enum class DiscoveryStatus {
    INITIALIZING, DISCOVERING, DEVICES_FOUND, NO_DEVICES, PERMISSION_UNAVAILABLE, NETWORK_UNAVAILABLE, DISCOVERY_ERROR, SERVICE_UNAVAILABLE
}

data class NearbyUiState(
    val status: DiscoveryStatus = DiscoveryStatus.SERVICE_UNAVAILABLE,
    val devices: List<DiscoveredPeer> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = "Discovery service is not connected (pending Rust/FFI integration)."
)

data class TrustedUiState(
    val isLoading: Boolean = false,
    val devices: List<Device> = emptyList(),
    val errorMessage: String? = null,
    val infoMessage: String? = "Trusted-device store unavailable until Rust trust integration is connected."
)
