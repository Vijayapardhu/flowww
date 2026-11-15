package com.flowww.android.domain.models

/** Where peer discovery currently stands. */
enum class DiscoveryStatus {
    INITIALIZING,
    DISCOVERING,
    DEVICES_FOUND,
    NO_DEVICES,
    PERMISSION_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    DISCOVERY_ERROR,
    SERVICE_UNAVAILABLE
}

/** The Nearby screen state. */
data class NearbyUiState(
    val status: DiscoveryStatus = DiscoveryStatus.INITIALIZING,
    val devices: List<com.flowww.android.connection.DiscoveredPeer> = emptyList(),
    val environment: NetworkEnvironment = NetworkEnvironment(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

/** The Trusted devices screen state. */
data class TrustedUiState(
    val isLoading: Boolean = true,
    val devices: List<LocalTrustedDevice> = emptyList(),
    val errorMessage: String? = null,
    val infoMessage: String? = null
)
