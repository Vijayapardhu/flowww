package com.flowww.android.content

import java.util.UUID

/**
 * Content types supported by the staging layer.
 */
enum class ContentType {
    FILE, IMAGE, VIDEO, TEXT, URL, DOCUMENT, MULTIPLE_FILES
}

/**
 * Content staging lifecycle states.
 */
enum class ContentLifecycleState {
    CREATED, STAGED, RESERVED, TRANSFERRED, EXPIRED, CANCELLED, FAILED
}

/**
 * A staged content item, ready for transfer to a trusted device.
 *
 * Large file contents are never placed in this data class; only an opaque
 * [contentReference] is stored. File bytes are resolved at transfer time.
 */
data class StagedContent(
    val contentId: String,
    val contentType: ContentType,
    val lifecycleState: ContentLifecycleState,
    val displayName: String,
    val mimeType: String?,
    val size: Long?,
    val sourceApplication: String?,
    val createdAt: Long,
    val expiresAt: Long,
    /** Opaque reference used by the storage layer to resolve the actual bytes. Never a filesystem path. */
    val contentReference: String,
    val authorizationContext: String?,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAt

    fun isTerminal(): Boolean =
        lifecycleState == ContentLifecycleState.TRANSFERRED ||
            lifecycleState == ContentLifecycleState.EXPIRED ||
            lifecycleState == ContentLifecycleState.CANCELLED ||
            lifecycleState == ContentLifecycleState.FAILED

    fun canTransfer(): Boolean =
        lifecycleState == ContentLifecycleState.STAGED ||
            lifecycleState == ContentLifecycleState.RESERVED
}

/**
 * Result of staging content.
 */
data class StageResult(
    val contentId: String,
    val expiresAt: Long
)

/**
 * Payload for staging content from a sharesheet or other source.
 */
data class StagePayload(
    val contentType: ContentType,
    val displayName: String,
    val mimeType: String?,
    val size: Long?,
    val sourceApplication: String?,
    val contentReference: String,
    val authorizationContext: String?,
    val ttlMs: Long? = null
)

/**
 * Generates a new unique content ID.
 */
fun newContentId(): String = "content_${UUID.randomUUID().toString().remove("-")}"

/**
 * Generates a new unique device ID placeholder for local staging context.
 */
fun newDeviceId(): String = "fw_${UUID.randomUUID().toString().remove("-")}"
