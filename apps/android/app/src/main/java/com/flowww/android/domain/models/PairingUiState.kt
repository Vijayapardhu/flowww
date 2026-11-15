package com.flowww.android.domain.models

import com.flowww.android.pairing.PairingUriData

data class PairingUiState(
    val uriInput: String = "",
    val isValidating: Boolean = false,
    val isSubmitting: Boolean = false,
    val validationError: String? = null,
    val parsed: PairingUriData? = null,
    val successMessage: String? = null,
    val failureMessage: String? = null,
    /** Set when the URI parsed fine but its TTL has elapsed. */
    val expiredMessage: String? = null,
    /** Set when the URI is valid but no pairing session can be started locally. */
    val unavailableMessage: String? = null,
    val serviceMessage: String? = "Secure pairing requires the Rust protocol engine. URI validation works today; completion does not."
)
