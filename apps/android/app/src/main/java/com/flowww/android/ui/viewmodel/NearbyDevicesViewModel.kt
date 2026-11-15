package com.flowww.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowww.android.connection.ConnectionService
import com.flowww.android.domain.models.DiscoveryStatus
import com.flowww.android.domain.models.NearbyUiState
import com.flowww.android.domain.models.WifiPermissionState
import com.flowww.android.domain.repository.DiscoveryRepository
import com.flowww.android.domain.repository.DiscoveryResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Nearby devices.
 *
 * Discovery is backed by the Rust mDNS node via [FfiDiscoveryRepository].
 * Connecting to a discovered peer checks the trust store via
 * [ConnectionService.connectToDevice]; paired peers can then receive sends.
 */
class NearbyDevicesViewModel(
    private val repository: DiscoveryRepository,
    private val connectionService: ConnectionService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NearbyUiState())
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            val environment = repository.environment()
            _uiState.update { it.copy(environment = environment) }

            when (val result = repository.refresh()) {
                is DiscoveryResult.Success -> _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        status = if (result.peers.isEmpty()) DiscoveryStatus.NO_DEVICES else DiscoveryStatus.DEVICES_FOUND,
                        devices = result.peers,
                        infoMessage = if (result.peers.isEmpty()) "No Flowww peers answered." else null
                    )
                }
                is DiscoveryResult.Unavailable -> _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        status = when {
                            !environment.isConnected -> DiscoveryStatus.NETWORK_UNAVAILABLE
                            environment.permissionState == WifiPermissionState.NOT_GRANTED ->
                                DiscoveryStatus.PERMISSION_UNAVAILABLE
                            else -> DiscoveryStatus.SERVICE_UNAVAILABLE
                        },
                        infoMessage = result.reason
                    )
                }
                is DiscoveryResult.Error -> _uiState.update {
                    it.copy(isRefreshing = false, status = DiscoveryStatus.DISCOVERY_ERROR, errorMessage = result.message)
                }
            }
        }
    }

    /** Scans the Wi-Fi networks this phone can see. These are not Flowww peers. */
    fun scanWifiNetworks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            repository.scanNetworks()
                .onSuccess { networks ->
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            environment = it.environment.copy(networks = networks, lastScanAt = System.currentTimeMillis())
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isRefreshing = false, errorMessage = e.message ?: "The Wi-Fi scan failed") }
                }
        }
    }

    /** Called when the nearby-devices permission is granted. */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            refresh()
        } else {
            _uiState.update {
                it.copy(
                    status = DiscoveryStatus.PERMISSION_UNAVAILABLE,
                    infoMessage = "Without the nearby-devices permission this phone cannot read Wi-Fi scan results."
                )
            }
        }
    }

    /**
     * Attempts to connect to a discovered peer.
     *
     * A connection can only succeed if the peer is in the local trust store
     * (paired via QR code). Unpaired peers must be paired first.
     */
    suspend fun connectToDevice(deviceId: String): Boolean {
        return try {
            connectionService.connectToDevice(deviceId)
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = e.message ?: "Connection failed") }
            false
        }
    }

    fun consumeMessage() { _uiState.update { it.copy(infoMessage = null, errorMessage = null) } }
}
