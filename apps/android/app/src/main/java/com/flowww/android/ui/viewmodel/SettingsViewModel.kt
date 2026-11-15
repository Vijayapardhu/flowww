package com.flowww.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowww.android.domain.models.FlowwwSettings
import com.flowww.android.domain.models.ThemeMode
import com.flowww.android.domain.repository.OperationRepository
import com.flowww.android.domain.repository.SettingsRepository
import com.flowww.android.domain.repository.StagedContentRepository
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

/**
 * Settings.
 *
 * Every setter writes through to DataStore and the screen reads the persisted
 * value back, so a switch can never drift from what is stored. The privacy
 * actions work on the real stores.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val stagedContentRepository: StagedContentRepository? = null,
    private val operationRepository: OperationRepository? = null
) : ViewModel() {

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

    // Device
    fun setDeviceName(name: String) = mutate({
        it.copy(deviceDisplayName = name.ifBlank { FlowwwSettings.DEFAULT_DEVICE_NAME })
    })

    // Appearance
    fun setThemeMode(mode: ThemeMode) = mutate(
        { it.copy(themeMode = mode) },
        "Appearance set to ${mode.label.lowercase()}."
    )

    // Content
    fun setDefaultSaveLocation(uri: String?) = mutate(
        { it.copy(defaultSaveLocationUri = uri) },
        if (uri == null) "Default export folder cleared." else "Default export folder saved."
    )

    fun setMaxStagedSize(bytes: Long) = mutate({
        it.copy(maxStagedBytes = bytes.coerceIn(FlowwwSettings.MIN_MAX_STAGED_BYTES, FlowwwSettings.MAX_MAX_STAGED_BYTES))
    })

    fun setAutoExpire(enabled: Boolean) = mutate(
        { it.copy(autoExpireEnabled = enabled) },
        if (enabled) "Staged content expires automatically." else "Staged content is kept until you remove it."
    )

    fun setExpireAfterHours(hours: Int) = mutate({
        it.copy(expireAfterHours = hours.coerceIn(FlowwwSettings.MIN_EXPIRE_HOURS, FlowwwSettings.MAX_EXPIRE_HOURS))
    })

    // Motion
    fun setMotionGestures(enabled: Boolean) = mutate(
        { it.copy(motionGesturesEnabled = enabled) },
        if (enabled) {
            "Gesture control enabled. Gestures act on the local staged content and never transfer by themselves."
        } else {
            "Gesture control disabled."
        }
    )

    fun setSensitivity(value: Float) = mutate({
        it.copy(gestureSensitivity = value.coerceIn(FlowwwSettings.MIN_SENSITIVITY, FlowwwSettings.MAX_SENSITIVITY))
    })

    fun setGestureFeedback(enabled: Boolean) = mutate({
        it.copy(gestureFeedbackEnabled = enabled)
    })

    // Notifications
    fun setNotifications(enabled: Boolean) = mutate({
        it.copy(transferNotifications = enabled)
    })

    fun setNotificationProgress(enabled: Boolean) = mutate({
        it.copy(notificationProgressEnabled = enabled)
    })

    fun setNotificationCompletion(enabled: Boolean) = mutate({
        it.copy(notificationCompletionEnabled = enabled)
    })

    // Policy
    fun setIncomingApproval(enabled: Boolean) = mutate(
        { it.copy(incomingApprovalRequired = true) },
        "Incoming content approval remains required by security policy."
    )

    fun setAutoConnect(enabled: Boolean) = mutate({ it.copy(autoConnectLastDevice = enabled) })

    // Privacy
    /** Removes every staged item from this phone. */
    fun clearStagedContent() {
        val staged = stagedContentRepository
        if (staged == null) {
            transientError.value = "Staged content is unavailable."
            return
        }
        viewModelScope.launch {
            val ids = staged.items.value.map { it.contentId }.toSet()
            if (ids.isEmpty()) {
                transientInfo.value = "There was nothing staged to clear."
                return@launch
            }
            staged.removeMany(ids)
                .onSuccess {
                    transientInfo.value = "Cleared $it staged item(s) and their local files."
                    staged.refresh()
                }
                .onFailure { transientError.value = it.message ?: "The staged content could not be cleared" }
        }
    }

    /** Removes every local operation record. */
    fun clearLocalHistory() {
        val history = operationRepository
        if (history == null) {
            transientError.value = "Local history is unavailable."
            return
        }
        viewModelScope.launch {
            history.clear()
                .onSuccess { count ->
                    transientInfo.value =
                        if (count == 0) "There was no local history to clear." else "Cleared $count history record(s)."
                }
                .onFailure { transientError.value = it.message ?: "The history could not be cleared" }
        }
    }

    fun consumeMessage() { transientError.value = null; transientInfo.value = null }
}
