package com.flowww.android.domain.repository

import com.flowww.android.content.StagedContent
import kotlinx.coroutines.flow.StateFlow

/**
 * Coordinates staged content visibility and lifecycle.
 *
 * The store is the single source of truth; this repository adds no second
 * database and never exposes absolute filesystem paths to callers.
 */
interface StagedContentRepository {
    /** Live stream of non-expired staged items, newest first. */
    val items: StateFlow<List<StagedContent>>

    /** Runs expiry sweep and best-effort orphan cleanup. */
    suspend fun refresh(): Result<Unit>

    suspend fun remove(contentId: String): Result<Unit>

    suspend fun removeMany(contentIds: Set<String>): Result<Int>

    suspend fun clearExpired(): Result<Int>

    suspend fun cleanupOrphans(): Result<Int>

    /** Total bytes occupied by staged blobs (TEXT/URL contribute payload size). */
    suspend fun storageUsage(): Result<Long>

    /**
     * A safe preview handle for the item, or a failure explaining why the
     * item cannot be previewed (missing blob, expired, unsupported).
     */
    suspend fun preview(contentId: String): Result<StagedPreview>

    /**
     * Attempts to send the given items to a destination device through the
     * connection service. Fails honestly until the Rust transfer engine is
     * connected; items are never silently marked transferred.
     */
    suspend fun send(contentIds: Set<String>, destinationDeviceId: String): Result<Unit>
}

/**
 * Preview payloads. [Blob] carries a content:// URI granted to the viewer
 * via FileProvider; [Text] carries the inline payload for TEXT/URL items.
 * Raw filesystem paths never leave this layer.
 */
sealed class StagedPreview {
    data class Text(val text: String, val mimeType: String?) : StagedPreview()
    data class Blob(val uri: android.net.Uri, val mimeType: String?) : StagedPreview()
}
