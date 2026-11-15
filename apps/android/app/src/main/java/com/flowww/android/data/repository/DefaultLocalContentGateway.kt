package com.flowww.android.data.repository

import android.content.Context
import androidx.core.content.FileProvider
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagedContent
import com.flowww.android.domain.models.ContentKind
import com.flowww.android.domain.models.LocalContentHandle
import com.flowww.android.domain.repository.LocalContentGateway
import com.flowww.android.storage.StagedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Resolves staged content for the local handoff workflow.
 *
 * Blob items are exposed through the app's FileProvider as content:// URIs so
 * other apps can read them without ever seeing a filesystem path.
 */
class DefaultLocalContentGateway(
    private val store: StagedStore,
    private val context: Context
) : LocalContentGateway {

    override suspend fun describe(contentId: String): Result<LocalContentHandle> =
        withContext(Dispatchers.IO) { runCatching { handleFor(itemFor(contentId)) } }

    override suspend fun describeAll(contentIds: List<String>): Result<List<LocalContentHandle>> =
        withContext(Dispatchers.IO) {
            runCatching { contentIds.map { handleFor(itemFor(it)) } }
        }
    override suspend fun openStream(contentId: String): Result<InputStream> {
        val item = store.get(contentId)
            ?: return Result.failure(IllegalStateException("Staged item is no longer available"))
        return when (item.contentType) {
            ContentType.TEXT, ContentType.URL -> Result.success(
                item.contentReference.byteInputStream()
            )
            else -> {
                val file = File(item.contentReference)
                if (!file.isFile) {
                    Result.failure(IllegalStateException("The staged file is missing"))
                } else {
                    Result.success(FileInputStream(file) as InputStream)
                }
            }
        }
    }

    private suspend fun itemFor(contentId: String): StagedContent {
        val item = store.get(contentId)
            ?: throw IllegalStateException("Staged item is no longer available")
        if (item.lifecycleState == ContentLifecycleState.EXPIRED || item.isExpired()) {
            throw IllegalStateException("Staged item has expired")
        }
        return item
    }

    private fun handleFor(item: StagedContent): LocalContentHandle {
        val mime = item.mimeType ?: defaultMime(item.contentType)
        return when (item.contentType) {
            ContentType.TEXT -> LocalContentHandle(
                contentId = item.contentId,
                displayName = item.displayName.ifBlank { "shared text" },
                mimeType = mime,
                size = item.size ?: item.contentReference.toByteArray().size.toLong(),
                kind = ContentKind.TEXT,
                text = item.contentReference
            )
            ContentType.URL -> LocalContentHandle(
                contentId = item.contentId,
                displayName = item.displayName.ifBlank { item.contentReference },
                mimeType = mime,
                size = item.size ?: item.contentReference.toByteArray().size.toLong(),
                kind = ContentKind.URL,
                text = item.contentReference
            )
            else -> {
                val file = File(item.contentReference)
                if (!file.isFile) {
                    throw IllegalStateException("The staged file is missing")
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                LocalContentHandle(
                    contentId = item.contentId,
                    displayName = item.displayName.ifBlank { file.name },
                    mimeType = mime,
                    size = item.size ?: file.length(),
                    kind = ContentKind.BLOB,
                    uri = uri
                )
            }
        }
    }

    private fun defaultMime(type: ContentType): String = when (type) {
        ContentType.TEXT -> "text/plain"
        ContentType.URL -> "text/plain"
        ContentType.IMAGE -> "image/*"
        ContentType.VIDEO -> "video/*"
        ContentType.DOCUMENT -> "application/octet-stream"
        ContentType.MULTIPLE_FILES -> "application/octet-stream"
        ContentType.FILE -> "application/octet-stream"
    }
}
