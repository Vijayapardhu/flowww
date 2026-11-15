package com.flowww.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowww.android.domain.models.DiscoveryStatus
import com.flowww.android.domain.models.NearbyUiState
import com.flowww.android.domain.repository.DiscoveryRepository
import com.flowww.android.domain.repository.DiscoveryResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NearbyDevicesViewModel(private val repository: DiscoveryRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(NearbyUiState())
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            when (val result = repository.refresh()) {
                is DiscoveryResult.Success -> _uiState.value = NearbyUiState(
                    status = if (result.peers.isEmpty()) DiscoveryStatus.NO_DEVICES else DiscoveryStatus.DEVICES_FOUND,
                    devices = result.peers,
                    infoMessage = if (result.peers.isEmpty()) "No devices found." else null
                )
                is DiscoveryResult.Unavailable -> _uiState.value = NearbyUiState(
                    status = DiscoveryStatus.SERVICE_UNAVAILABLE,
                    infoMessage = result.reason
                )
                is DiscoveryResult.Error -> _uiState.value = NearbyUiState(
                    status = DiscoveryStatus.DISCOVERY_ERROR,
                    errorMessage = result.message
                )
            }
        }
    }

    /** Honest user-facing notice for the not-yet-wired connect action. */
    fun markConnectUnavailable() {
        _uiState.update { it.copy(infoMessage = "Connect is unavailable until the Rust/FFI bridge is connected.") }
    }
}
