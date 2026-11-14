package com.flowww.android

/**
 * Errors from the Android companion application layer.
 */
sealed class FlowwwError : Exception() {
    data class InvalidContent(val message: String) : FlowwwError()
    data class ContentNotFound(val contentId: String) : FlowwwError()
    data class ContentExpired(val contentId: String) : FlowwwError()
    data class ProviderUnavailable(val provider: String) : FlowwwError()
    data class NotTransferable(val contentId: String) : FlowwwError()
    data class DeviceNotFound(val deviceId: String) : FlowwwError()
    data class DeviceNotTrusted(val deviceId: String) : FlowwwError()
    data class ConnectionFailed(val reason: String) : FlowwwError()
    data class PairingFailed(val reason: String) : FlowwwError()
    data class TransferFailed(val transferId: String, val reason: String) : FlowwwError()
    data class InvalidUri(val uri: String) : FlowwwError()
    data class Internal(val message: String) : FlowwwError()

    override fun getMessage(): String = when (this) {
        is InvalidContent -> message
        is ContentNotFound -> "Content not found: $contentId"
        is ContentExpired -> "Content expired: $contentId"
        is ProviderUnavailable -> "Provider unavailable: $provider"
        is NotTransferable -> "Content not transferable: $contentId"
        is DeviceNotFound -> "Device not found: $deviceId"
        is DeviceNotTrusted -> "Device not trusted: $deviceId"
        is ConnectionFailed -> "Connection failed: $reason"
        is PairingFailed -> "Pairing failed: $reason"
        is TransferFailed -> "Transfer failed: $transferId - $reason"
        is InvalidUri -> "Invalid URI: $uri"
        is Internal -> "Internal error: $message"
    }
}
