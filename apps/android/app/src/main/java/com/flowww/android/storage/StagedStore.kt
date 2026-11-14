package com.flowww.android.storage

import com.flowww.android.FlowwwError
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.StagedContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Persistent staged-content store with lifecycle tracking and TTL-based expiry.
 * Uses a simple file-backed serialization store in app files dir (not cache) so
 * staged metadata survives restarts better than cache-only storage.
 */
class StagedStore(private val storageDir: File) {
    private val lock = Any()
    private val items = linkedMapOf<String, StagedContent>()
    private val _itemsFlow = MutableStateFlow<List<StagedContent>>(emptyList())
    val itemsFlow: StateFlow<List<StagedContent>> = _itemsFlow.asStateFlow()

    init {
        storageDir.mkdirs()
        loadFromDisk()
        publish()
    }

    suspend fun insert(item: StagedContent): StagedContent = withContext(Dispatchers.IO) {
        synchronized(lock) {
            items[item.contentId] = item
            saveToDisk(item)
            publishLocked()
            item
        }
    }

    suspend fun get(contentId: String): StagedContent? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val item = items[contentId] ?: return@withContext null
            if (item.isExpired()) {
                removeLocked(contentId)
                publishLocked()
                return@withContext null
            }
            item
        }
    }

    suspend fun remove(contentId: String): StagedContent? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val removed = removeLocked(contentId)
            publishLocked()
            removed
        }
    }

    suspend fun transitionState(contentId: String, newState: ContentLifecycleState): StagedContent =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val item = items[contentId] ?: throw FlowwwError.ContentNotFound(contentId)
                if (item.isExpired()) {
                    removeLocked(contentId)
                    publishLocked()
                    throw FlowwwError.ContentExpired(contentId)
                }
                val updated = item.copy(lifecycleState = newState)
                items[contentId] = updated
                saveToDisk(updated)
                publishLocked()
                updated
            }
        }

    suspend fun expire(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val expired = items.values.filter { it.isExpired(now) }.toList()
            for (item in expired) {
                val updated = item.copy(lifecycleState = ContentLifecycleState.EXPIRED)
                items[item.contentId] = updated
                saveToDisk(updated)
            }
            if (expired.isNotEmpty()) publishLocked()
            expired.size
        }
    }

    suspend fun all(includeExpired: Boolean = false): List<StagedContent> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            items.values
                .filter { includeExpired || !it.isExpired(now) }
                .sortedByDescending { it.createdAt }
        }
    }

    suspend fun staged(): List<StagedContent> = all().filter { it.lifecycleState == ContentLifecycleState.STAGED || it.lifecycleState == ContentLifecycleState.RESERVED }

    suspend fun size(): Int = withContext(Dispatchers.IO) { synchronized(lock) { items.size } }

    private fun removeLocked(contentId: String): StagedContent? {
        val removed = items.remove(contentId)
        File(storageDir, "$contentId.staged").delete()
        removed?.contentReference?.let { ref ->
            // best-effort cleanup for temp files created by sharesheet
            try {
                val f = File(ref)
                if (f.exists() && f.parentFile?.name?.contains("flowww", ignoreCase = true) == true || f.name.startsWith("flowww_stage_")) {
                    f.delete()
                }
            } catch (_: Exception) {}
        }
        return removed
    }

    private fun saveToDisk(item: StagedContent) {
        val file = File(storageDir, "${item.contentId}.staged")
        try {
            ObjectOutputStream(FileOutputStream(file)).use { it.writeObject(item) }
        } catch (_: Exception) {
            // best-effort persistence; keep in-memory item
        }
    }

    private fun loadFromDisk() {
        storageDir.listFiles()?.filter { it.extension == "staged" }?.forEach { file ->
            try {
                ObjectInputStream(file.inputStream()).use { ois ->
                    val item = ois.readObject() as? StagedContent ?: return@forEach
                    if (item.isExpired()) {
                        file.delete()
                    } else {
                        items[item.contentId] = item
                    }
                }
            } catch (_: Exception) {
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun publish() {
        synchronized(lock) { publishLocked() }
    }

    private fun publishLocked() {
        val now = System.currentTimeMillis()
        _itemsFlow.value = items.values.filter { !it.isExpired(now) }.sortedByDescending { it.createdAt }
    }
}