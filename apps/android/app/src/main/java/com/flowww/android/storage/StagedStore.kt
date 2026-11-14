package com.flowww.android.storage

import com.flowww.android.content.*
import com.flowww.android.FlowwwError
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * In-memory staged-content store with lifecycle tracking and TTL-based expiry.
 * Content is persisted to disk for survival across process death.
 */
class StagedStore {
    private val items = mutableMapOf<String, StagedContent>()

    private val storageDir: File

    constructor(storageDir: File) {
        this.storageDir = storageDir
        storageDir.mkdirs()
        loadFromDisk()
    }

    suspend fun insert(item: StagedContent) {
        items[item.contentId] = item
        saveToDisk(item)
    }

    suspend fun get(contentId: String): StagedContent? {
        val item = items[contentId] ?: return null
        if (item.isExpired()) {
            remove(contentId)
            return null
        }
        return item
    }

    suspend fun remove(contentId: String): StagedContent? {
        val removed = items.remove(contentId)
        removed?.let { deleteFromDisk(it.contentId) }
        return removed
    }

    suspend fun transitionState(contentId: String, newState: ContentLifecycleState): StagedContent {
        val item = items[contentId]
            ?: throw FlowwwError.ContentNotFound(contentId)
        if (item.isExpired()) {
            throw FlowwwError.ContentExpired(contentId)
        }
        val updated = item.copy(lifecycleState = newState)
        items[contentId] = updated
        saveToDisk(updated)
        return updated
    }

    suspend fun expire(now: Long = System.currentTimeMillis()): Int {
        val expired = items.values.filter { it.isExpired(now) }.toList()
        for (item in expired) {
            val updated = item.copy(lifecycleState = ContentLifecycleState.EXPIRED)
            items[item.contentId] = updated
            saveToDisk(updated)
        }
        return expired.size
    }

    suspend fun countByState(state: ContentLifecycleState): Int {
        return items.values.count { it.lifecycleState == state }
    }

    suspend fun all(): List<StagedContent> {
        val now = System.currentTimeMillis()
        return items.values.filter { !it.isExpired(now) }.toList()
    }

    suspend fun size(): Int {
        return items.size
    }

    private fun saveToDisk(item: StagedContent) {
        val file = File(storageDir, "${item.contentId}.staged")
        FileOutputStream(file).use { out ->
            ObjectOutputStream(out).use { oos ->
                oos.writeObject(item)
            }
        }
    }

    private fun deleteFromDisk(contentId: String) {
        File(storageDir, "$contentId.staged").delete()
    }

    private fun loadFromDisk() {
        storageDir.listFiles()
            ?.filter { it.extension == "staged" }
            ?.forEach { file ->
                try {
                    ObjectInputStream(file.inputStream()).use { ois ->
                        val item = ois.readObject() as StagedContent
                        if (!item.isExpired()) {
                            items[item.contentId] = item
                        }
                    }
                } catch (_: Exception) {
                    file.delete()
                }
            }
    }

    companion object {
        @Volatile private var instance: StagedStore? = null

        fun getInstance(context: android.content.Context): StagedStore {
            return instance ?: synchronized(this) {
                instance ?: StagedStore(context.cacheDir).also { instance = it }
            }
        }
    }
}