package com.flowww.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowww.android.domain.models.PairingUiState
import com.flowww.android.domain.repository.PairingRepository
import com.flowww.android.pairing.PairingResult
import com.flowww.android.pairing.PairingUriParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PairingViewModel(private val repository: PairingRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    fun onUriChanged(value: String) {
        _uiState.update { it.copy(uriInput = value, validationError = null, successMessage = null, failureMessage = null, expiredMessage = null) }
    }

    fun clear() { _uiState.value = PairingUiState() }

    fun pasteFromClipboard(text: String?) {
        text?.let { onUriChanged(it) }
    }

    fun validateAndSubmit() {
        val uri = _uiState.value.uriInput.trim()
        if (uri.isBlank()) {
            _uiState.update { it.copy(validationError = "Enter a pairing URI.") }
            return
        }
        if (!uri.startsWith(PREFIX)) {
            _uiState.update {
                it.copy(validationError = "Pairing URI must start with “$PREFIX”. Copy it from the Windows Flowww app.")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isValidating = true, validationError = null, successMessage = null, failureMessage = null, expiredMessage = null) }
            val validation = repository.validate(uri)
            validation
                .onSuccess { parsed ->
                    // Double-check expiry locally so the user gets a dedicated expired state.
                    if (PairingUriParser.isExpired(parsed)) {
                        _uiState.update { it.copy(isValidating = false, expiredMessage = "This pairing URI has expired. Generate a new one in the Windows Flowww app.") }
                        return@launch
                    }
                    _uiState.update { it.copy(isValidating = false, isSubmitting = true, parsed = parsed) }
                    repository.pair(uri)
                        .onSuccess { result -> applyResult(result) }
                        .onFailure { e -> _uiState.update { it.copy(isSubmitting = false, failureMessage = e.message ?: "Pairing failed") } }
                }
                .onFailure { e ->
                    val message = e.message ?: "Invalid pairing URI"
                    if (message.contains("expired", ignoreCase = true)) {
                        _uiState.update { it.copy(isValidating = false, expiredMessage = "This pairing URI has expired. Generate a new one in the Windows Flowww app.") }
                    } else {
                        _uiState.update { it.copy(isValidating = false, validationError = message) }
                    }
                }
        }
    }

    private fun applyResult(result: PairingResult) {
        when (result) {
            is PairingResult.Success -> _uiState.update {
                it.copy(
                    isSubmitting = false,
                    successMessage = "Pairing completed for ${result.deviceName}. The device was added to the trust store.",
                    failureMessage = null,
                    expiredMessage = null
                )
            }
            is PairingResult.Unavailable -> _uiState.update {
                it.copy(isSubmitting = false, failureMessage = null, expiredMessage = null, unavailableMessage = result.reason)
            }
            is PairingResult.Expired -> _uiState.update {
                it.copy(isSubmitting = false, expiredMessage = "This pairing URI has expired. Generate a new one in the Windows Flowww app.")
            }
            is PairingResult.Failure -> _uiState.update { it.copy(isSubmitting = false, failureMessage = result.reason) }
        }
    }

    private companion object {
        const val PREFIX = "flowww://pair/v1/"
    }
}
