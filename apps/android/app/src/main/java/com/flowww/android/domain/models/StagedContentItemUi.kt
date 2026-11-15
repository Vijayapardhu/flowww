package com.flowww.android.domain.models

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

/** Everything the staged-content screen needs in one immutable snapshot. */
data class StagedContentUiState(
    val isLoading: Boolean = true,
    val items: List<StagedContentItemUi> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val storageUsageBytes: Long = 0L,
    val totalCount: Int = 0
) {
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val allSelected: Boolean get() = items.isNotEmpty() && selectedIds.size == items.size
}
