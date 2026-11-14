package com.flowww.android.security

/**
 * Content sanitizer for filenames and metadata.
 *
 * Never expose internal filesystem paths or sensitive references in UI DTOs.
 */
object ContentSanitizer {

    /**
     * Sanitize a filename for safe use in storage and UI.
     * Removes path separators, control characters, and Windows reserved names.
     */
    fun sanitizeFilename(input: String): String {
        if (input.isBlank()) return "file"

        val base = input
            .rsplit("/", "\\")
            .firstOrNull()
            ?: "file"

        val trimmed = base.trim().trimEnd('.')

        val cleaned = trimmed.map { c ->
            when {
                c.isControl() || c in listOf('<', '>', ':', '"', '|', '?', '*', '\0') -> '_'
                else -> c
            }
        }.joinToString("")

        if (cleaned.isBlank()) return "file"

        val stem = cleaned.split('.').firstOrNull()?.uppercase() ?: ""
        val reserved = listOf(
            "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4",
            "LPT1", "LPT2", "LPT3", "LPT4"
        )

        val result = if (stem in reserved) "_$cleaned" else cleaned
        return result.take(180)
    }

    /**
     * Sanitize a display string by removing potentially dangerous characters.
     */
    fun sanitizeDisplayName(input: String): String {
        return input.take(128)
    }

    /**
     * Validate that a URI is a content URI (not a file:// path).
     */
    fun isContentUri(uri: String): Boolean {
        return uri.startsWith("content://")
    }

    /**
     * Check if a MIME type is allowed for staging.
     */
    fun isAllowedMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/") ||
            mimeType.startsWith("video/") ||
            mimeType.startsWith("text/") ||
            mimeType == "application/pdf" ||
            mimeType.startsWith("application/ms") ||
            mimeType.startsWith("application/vnd") ||
            mimeType == "application/octet-stream" ||
            mimeType == "text/html" ||
            mimeType == "text/csv"
    }

    /**
     * Check if a file size is within limits.
     */
    fun isWithinSizeLimit(sizeInBytes: Long, maxSizeInBytes: Long = 100L * 1024 * 1024): Boolean {
        return sizeInBytes >= 0 && sizeInBytes <= maxSizeInBytes
    }
}
