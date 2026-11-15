package com.flowww.android.domain.models

import java.io.Serializable

/** Lifecycle of an incoming file that still needs to be committed to storage. */
enum class PendingReceiveStatus(val label: String) : Serializable {
    /** Queued; waiting for a destination or for the writer. */
    PENDING("Waiting to save"),

    /** A commit is actively streaming the file. */
    COMMITTING("Saving"),

    /** Saved to the chosen destination and verified. */
    COMMITTED("Saved"),

    /** The commit failed; see [PendingReceive.error]. Retry keeps the source file. */
    FAILED("Save failed")
}

/**
 * An incoming file that Rust has finished downloading into the app-private
 * downloads directory but that still has not been committed to the user's
 * chosen Storage Access Framework destination.
 *
 * [sourcePath] is an absolute path inside the app-private download directory;
 * [expectedSha256] is the digest Rust verified during receive (when known),
 * re-verified here against the bytes actually written through the SAF.
 */
data class PendingReceive(
    val transferId: String,
    val sourcePath: String,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val expectedSha256: String?,
    val status: PendingReceiveStatus,
    val attempts: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /** Display label of the destination the user last chose, when known. */
    val destinationLabel: String? = null,
    val error: String? = null
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        /** Bounded retry count before an entry requires manual user attention. */
        const val MAX_AUTOMATIC_ATTEMPTS = 3
    }
}
