package com.flowww.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowww.android.domain.models.FlowwwSettings
import com.flowww.android.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoading: Boolean = true,
    val settings: FlowwwSettings = FlowwwSettings(),
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    private val transientError = MutableStateFlow<String?>(null)
    private val transientInfo = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(repository.settings, transientError, transientInfo) { result, err, info ->
        result.fold(
            onSuccess = { SettingsUiState(isLoading = false, settings = it, errorMessage = err, infoMessage = info) },
            onFailure = { SettingsUiState(isLoading = false, errorMessage = err ?: (it.message ?: "Failed to load settings"), infoMessage = info) }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private fun mutate(block: (FlowwwSettings) -> FlowwwSettings, success: String? = null) {
        viewModelScope.launch {
            repository.update(block)
                .onSuccess { if (success != null) transientInfo.value = success }
                .onFailure { transientError.value = it.message ?: "Failed to save setting" }
        }
    }

    fun setDeviceName(name: String) = mutate({ it.copy(deviceDisplayName = name.ifBlank { FlowwwSettings.DEFAULT_DEVICE_NAME }) })
    fun setNotifications(enabled: Boolean) = mutate({ it.copy(transferNotifications = enabled) })
    fun setMotionGestures(enabled: Boolean) = mutate({ it.copy(motionGesturesEnabled = enabled) }, if (enabled) "Gesture preference saved. Real gesture-to-transfer handoff requires Rust integration." else null)
    fun setSensitivity(value: Float) = mutate({ it.copy(gestureSensitivity = value.coerceIn(0.3f, 1.0f)) })
    fun setIncomingApproval(enabled: Boolean) = mutate({ it.copy(incomingApprovalRequired = if (!enabled) true else true) }, "Incoming transfer approval remains required by security policy.")
    fun setAutoConnect(enabled: Boolean) = mutate({ it.copy(autoConnectLastDevice = enabled) })
    fun setDefaultSaveLocation(uri: String?) = mutate({ it.copy(defaultSaveLocationUri = uri) })
    fun consumeMessage() { transientError.value = null; transientInfo.value = null }
}
