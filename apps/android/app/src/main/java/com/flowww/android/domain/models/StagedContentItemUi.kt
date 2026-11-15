package com.flowww.android.domain.models

import android.net.Uri
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType

/**
 * UI-safe projection of a staged content item.
 *
 * Deliberately omits `contentReference` (an absolute filesystem path or,
 * for TEXT/URL items, the raw payload). UI code must never see those values.
 */
data class StagedContentItemUi(
    val id: String,
    val name: String,
    val mimeType: String?,
    val contentType: ContentType,
    val sizeBytes: Long?,
    val createdAt: Long,
    val lifecycleState: ContentLifecycleState,
    val isExpired: Boolean,
    val blobMissing: Boolean,
    val canPreview: Boolean
)

/** Sort order for the staged list. */
enum class StagedSortOrder(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("Name"),
    SIZE("Size")
}

/** Content type filter for the staged list. */
enum class StagedTypeFilter(val label: String) {
    ALL("All"),
    TEXT("Text"),
    URL("Links"),
    IMAGE("Images"),
    VIDEO("Video"),
    DOCUMENT("Documents"),
    FILE("Files")
}

/** The preview currently shown to the user. */
sealed interface StagedPreviewUi {
    /** The id of the item being previewed. */
    val contentId: String

    /** Inline text or link payload. */
    data class Text(
        override val contentId: String,
        val title: String,
        val body: String,
        val isLink: Boolean
    ) : StagedPreviewUi

    /** A file previewed through its content:// URI. */
    data class Blob(
        override val contentId: String,
        val title: String,
        val mimeType: String?,
        val uri: Uri
    ) : StagedPreviewUi
}

/** Progress of a running export. */
data class ExportProgress(
    val operationId: String,
    val writtenBytes: Long,
    val totalBytes: Long
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (writtenBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** Everything the staged-content screen needs in one immutable snapshot. */
data class StagedContentUiState(
    val isLoading: Boolean = true,
    /** The items after search, filter and sorting are applied. */
    val items: List<StagedContentItemUi> = emptyList(),
    /** Every staged item, regardless of the current search and filter. */
    val allItems: List<StagedContentItemUi> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val storageUsageBytes: Long = 0L,
    val totalCount: Int = 0,
    val query: String = "",
    val sortOrder: StagedSortOrder = StagedSortOrder.NEWEST,
    val typeFilter: StagedTypeFilter = StagedTypeFilter.ALL,
    val preview: StagedPreviewUi? = null,
    val isPreparingPreview: Boolean = false,
    val exportProgress: ExportProgress? = null
) {
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val allSelected: Boolean get() = items.isNotEmpty() && selectedIds.size == items.size
    val isFiltered: Boolean get() = query.isNotBlank() || typeFilter != StagedTypeFilter.ALL
}
