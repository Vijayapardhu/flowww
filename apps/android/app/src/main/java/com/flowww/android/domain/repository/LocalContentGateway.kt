package com.flowww.android.domain.repository

import com.flowww.android.domain.models.LocalContentHandle
import java.io.InputStream

/**
 * Resolves staged content into shareable handles and byte streams.
 *
 * The gateway is the only place that knows where staged blobs live; callers
 * receive content:// URIs and streams, never filesystem paths.
 */
interface LocalContentGateway {
    /** Resolves one staged item, or fails with a user-facing reason. */
    suspend fun describe(contentId: String): Result<LocalContentHandle>

    /** Resolves several staged items, failing on the first problem. */
    suspend fun describeAll(contentIds: List<String>): Result<List<LocalContentHandle>>

    /**
     * Opens a stream over the item's bytes. The caller owns the stream.
     * Inline text and URL items yield their payload as bytes.
     */
    suspend fun openStream(contentId: String): Result<InputStream>
}
