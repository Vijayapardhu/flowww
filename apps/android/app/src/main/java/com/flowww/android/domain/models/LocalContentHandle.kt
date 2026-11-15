package com.flowww.android.domain.models

import android.net.Uri

/** What kind of payload a staged item holds. */
enum class ContentKind {
    /** Inline text payload (shared text). */
    TEXT,

    /** Inline URL payload (shared link). */
    URL,

    /** A file staged on disk. */
    BLOB
}

/**
 * A shareable handle for staged content.
 *
 * The handle never exposes a filesystem path: blob items are addressed by a
 * content:// URI granted through the app's FileProvider, and inline items
 * carry their payload.
 */
data class LocalContentHandle(
    val contentId: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val kind: ContentKind,
    /** Inline payload for TEXT/URL items. */
    val text: String? = null,
    /** content:// URI for blob items. */
    val uri: Uri? = null
) {
    val isInline: Boolean get() = kind != ContentKind.BLOB
}
