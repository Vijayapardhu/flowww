package com.flowww.android.domain.models

data class FlowwwSettings(
    val deviceDisplayName: String = DEFAULT_DEVICE_NAME,
    val transferNotifications: Boolean = true,
    val motionGesturesEnabled: Boolean = false,
    val gestureSensitivity: Float = 0.7f,
    val incomingApprovalRequired: Boolean = true,
    val defaultSaveLocationUri: String? = null,
    val autoConnectLastDevice: Boolean = false
) {
    companion object {
        const val DEFAULT_DEVICE_NAME = "My Android device"
    }
}
