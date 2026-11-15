package com.flowww.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowww.android.domain.models.TrustedUiState
import com.flowww.android.domain.repository.TrustedDeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TrustedDevicesViewModel(private val repository: TrustedDeviceRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(TrustedUiState())
    val uiState: StateFlow<TrustedUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.refresh()
                .onSuccess { devices ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            devices = devices,
                            infoMessage = if (devices.isEmpty()) "No trusted devices. Pairing integration is pending Rust/FFI connection." else null
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load trusted devices") }
                }
        }
    }

    fun forget(deviceId: String) {
        viewModelScope.launch {
            repository.forget(deviceId)
                .onSuccess { _uiState.update { it.copy(infoMessage = "Device removed.") } }
                .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message ?: "Forget unavailable") } }
        }
    }

    fun rename(deviceId: String, displayName: String) {
        if (displayName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter a device name.") }
            return
        }
        viewModelScope.launch {
            repository.rename(deviceId, displayName)
                .onSuccess { _uiState.update { it.copy(infoMessage = "Device renamed.") } }
                .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message ?: "Rename unavailable") } }
        }
    }

    fun consumeMessage() { _uiState.update { it.copy(infoMessage = null, errorMessage = null) } }
}
