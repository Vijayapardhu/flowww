package com.flowww.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowww.android.domain.models.LocalTrustedDevice
import com.flowww.android.domain.models.TrustedUiState
import com.flowww.android.domain.repository.TrustedDeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The device records kept on this phone.
 *
 * These are organisational records, not verified trust: the screen says so
 * explicitly and the model carries a [LocalTrustedDevice.trustLevel].
 */
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
                            infoMessage = if (devices.isEmpty()) {
                                "No devices saved yet. Add one to keep track of the computers you use Flowww with."
                            } else {
                                null
                            }
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load device records")
                    }
                }
        }
    }

    /** Adds a device record the user entered by hand. */
    fun add(displayName: String, platform: String, fingerprint: String? = null, note: String? = null) {
        val name = displayName.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter a device name.") }
            return
        }
        viewModelScope.launch {
            val record = LocalTrustedDevice(
                deviceId = "local_${name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                displayName = name,
                platform = platform.trim().ifBlank { "Unknown" },
                fingerprint = fingerprint?.trim()?.ifBlank { null },
                note = note?.trim()?.ifBlank { null }
            )
            repository.add(record)
                .onSuccess {
                    _uiState.update {
                        it.copy(devices = it.devices + record, infoMessage = "Saved “${record.displayName}” on this phone.")
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message ?: "The device could not be saved") } }
        }
    }

    fun forget(deviceId: String) {
        viewModelScope.launch {
            repository.forget(deviceId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            devices = it.devices.filterNot { device -> device.deviceId == deviceId },
                            infoMessage = "Device removed from this phone."
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message ?: "The device could not be removed") } }
        }
    }

    fun rename(deviceId: String, displayName: String) {
        if (displayName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter a device name.") }
            return
        }
        viewModelScope.launch {
            repository.rename(deviceId, displayName)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            devices = it.devices.map { device ->
                                if (device.deviceId == deviceId) device.copy(displayName = displayName) else device
                            },
                            infoMessage = "Device renamed."
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message ?: "The device could not be renamed") } }
        }
    }

    fun consumeMessage() { _uiState.update { it.copy(infoMessage = null, errorMessage = null) } }
}
