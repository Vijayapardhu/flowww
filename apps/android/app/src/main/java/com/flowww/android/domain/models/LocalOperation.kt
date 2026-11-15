package com.flowww.android.domain.models

import java.io.Serializable

/**
 * The kind of operation that was performed on staged content.
 *
 * Flowww in this build performs local operations only: there is no Flowww
 * peer to transfer to, so [NETWORK_TRANSFER] is declared for completeness and
 * always reports that it is unavailable.
 */
enum class LocalOperationType(val label: String, val description: String) : Serializable {
    LOCAL_EXPORT("Local export", "Copied staged content into a folder you chose"),
    ANDROID_SHARE("Android share", "Handed staged content to another Android app"),
    SAVED_LOCALLY("Saved locally", "Wrote staged content into app storage"),
    EXTERNAL_HANDOFF("External app handoff", "Opened staged content in another app"),
    NETWORK_TRANSFER("Network transfer", "Unavailable: no Flowww peer in this build");

    val isAvailable: Boolean get() = this != NETWORK_TRANSFER
}

/** Lifecycle of a local operation. */
enum class LocalOperationStatus(val label: String) : Serializable {
    RUNNING("Running"),
    SUCCESS("Success"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    val isTerminal: Boolean get() = this != RUNNING
}

/**
 * A real, locally performed operation on staged content.
 *
 * Records are only created when an operation actually starts, and their
 * status reflects what happened; nothing is fabricated.
 */
data class LocalOperation(
    val id: String,
    val type: LocalOperationType,
    val status: LocalOperationStatus,
    /** Human readable summary, e.g. "3 items · 12.4 MB". */
    val title: String,
    /** Staged content ids this operation touched. */
    val contentIds: List<String> = emptyList(),
    /** Display names of the affected items. */
    val fileNames: List<String> = emptyList(),
    val totalBytes: Long = 0L,
    val writtenBytes: Long = 0L,
    /** Where the content went, when it is known ("Downloads", app label, …). */
    val destination: String? = null,
    val createdAt: Long = 0L,
    val finishedAt: Long? = null,
    val errorMessage: String? = null
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
