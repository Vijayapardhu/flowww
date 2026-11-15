package com.flowww.android.data.repository

import android.content.Context
import androidx.core.content.FileProvider
import com.flowww.android.connection.ConnectionService
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagedContent
import com.flowww.android.domain.repository.StagedContentRepository
import com.flowww.android.domain.repository.StagedPreview
import com.flowww.android.storage.StagedStore
import com.flowww.android.FlowwwError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

class DefaultStagedContentRepository(
    private val store: StagedStore,
    private val context: Context? = null,
    private val connectionService: ConnectionService? = null
) : StagedContentRepository {

    override val items: StateFlow<List<StagedContent>> = store.itemsFlow

    override suspend fun refresh(): Result<Unit> = runCatching {
        store.expire()
        store.cleanupOrphans()
    }

    override suspend fun remove(contentId: String): Result<Unit> = runCatching {
        store.remove(contentId)
        Unit
    }

    override suspend fun removeMany(contentIds: Set<String>): Result<Int> = runCatching {
        var count = 0
        for (id in contentIds) {
            if (store.remove(id) != null) count++
        }
        count
    }

    override suspend fun clearExpired(): Result<Int> = runCatching { store.expire() }

    override suspend fun cleanupOrphans(): Result<Int> = runCatching { store.cleanupOrphans() }

    override suspend fun storageUsage(): Result<Long> = withContext(Dispatchers.IO) {
        runCatching { store.usageBytes() }
    }

    override suspend fun preview(contentId: String): Result<StagedPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val item = store.get(contentId)
                ?: throw IllegalStateException("Item not found or expired")
            if (item.lifecycleState == ContentLifecycleState.EXPIRED || item.isExpired()) {
                throw IllegalStateException("Item has expired")
            }
            when {
                item.contentType == ContentType.TEXT || item.contentType == ContentType.URL -> {
                    val payload = item.contentReference
                    if (payload.length > MAX_INLINE_PREVIEW_BYTES) {
                        throw IllegalStateException("Preview too large")
                    }
                    StagedPreview.Text(payload, item.mimeType ?: "text/plain")
                }
                else -> {
                    val file = File(item.contentReference)
                    if (!file.isFile || !file.exists()) {
                        throw IllegalStateException("Staged file is no longer available")
                    }
                    val ctx = context ?: throw IllegalStateException("Preview unavailable: no context")
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                    StagedPreview.Blob(uri, item.mimeType)
                }
            }
        }
    }

    private companion object {
        const val MAX_INLINE_PREVIEW_BYTES = 1_000_000
    }

    override suspend fun send(contentIds: Set<String>, destinationDeviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (contentIds.isEmpty()) throw IllegalArgumentException("No items selected")
            for (id in contentIds) {
                val item = store.get(id) ?: throw FlowwwError.ContentNotFound(id)
                if (!item.canTransfer()) throw FlowwwError.NotTransferable(id)
            }
            val service = connectionService ?: throw FlowwwError.ProviderUnavailable("Rust transfer node")
            // Reserve happens only inside the service; nothing is rewritten here on failure.
            for (id in contentIds) {
                service.initiateSend(destinationDeviceId, id)
            }
        }
    }
}
