package com.flowww.android.domain.models

/**
 * Appearance theme preference.
 *
 * Lives in the domain layer because it is persisted state that the UI layer
 * resolves into a concrete palette.
 */
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT;

    val label: String
        get() = when (this) {
            SYSTEM -> "System theme"
            DARK -> "Dark theme"
            LIGHT -> "Light theme"
        }

    companion object {
        fun fromName(value: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DARK
    }
}

/**
 * Everything the user can configure. Every field is persisted in DataStore
 * and applied by the app; there are no decorative switches.
 */
data class FlowwwSettings(
    // Device
    val deviceDisplayName: String = DEFAULT_DEVICE_NAME,

    // Appearance
    val themeMode: ThemeMode = ThemeMode.DARK,

    // Content
    val transferNotifications: Boolean = true,
    val notificationProgressEnabled: Boolean = true,
    val notificationCompletionEnabled: Boolean = true,
    val defaultSaveLocationUri: String? = null,
    val maxStagedBytes: Long = DEFAULT_MAX_STAGED_BYTES,
    val autoExpireEnabled: Boolean = true,
    val expireAfterHours: Int = DEFAULT_EXPIRE_HOURS,

    // Motion
    val motionGesturesEnabled: Boolean = false,
    val gestureSensitivity: Float = 0.7f,
    val gestureFeedbackEnabled: Boolean = true,

    // Policy
    val incomingApprovalRequired: Boolean = true,
    val autoConnectLastDevice: Boolean = false
) {
    companion object {
        const val DEFAULT_DEVICE_NAME = "My Android device"

        /** 512 MB of staged content by default. */
        const val DEFAULT_MAX_STAGED_BYTES: Long = 512L * 1024L * 1024L

        /** Staged items expire after 24 hours by default. */
        const val DEFAULT_EXPIRE_HOURS: Int = 24

        /** Lower bound for the sensitivity slider. */
        const val MIN_SENSITIVITY: Float = 0.3f

        /** Upper bound for the sensitivity slider. */
        const val MAX_SENSITIVITY: Float = 1.0f

        /** Expiry window bounds, in hours. */
        const val MIN_EXPIRE_HOURS: Int = 1
        const val MAX_EXPIRE_HOURS: Int = 168

        /** Maximum staged content size bounds, in bytes (16 MB … 4 GB). */
        const val MIN_MAX_STAGED_BYTES: Long = 16L * 1024L * 1024L
        const val MAX_MAX_STAGED_BYTES: Long = 4096L * 1024L * 1024L
    }
}
