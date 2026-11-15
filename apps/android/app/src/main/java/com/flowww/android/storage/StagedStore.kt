package com.flowww.android.storage

import com.flowww.android.FlowwwError
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagedContent
import com.flowww.android.content.newContentId
import com.flowww.android.security.ContentSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Result of a staging operation: the stored item and whether it was already staged (dedupe hit). */
data class StageResult(
    val item: StagedContent,
    val duplicate: Boolean
)

/**
 * Persistent staged-content store with lifecycle tracking and TTL-based expiry.
 *
 * Metadata is written atomically (temp file + rename) so an interrupted write
 * can never leave a half-written `.staged` file behind. Unreadable/corrupt
 * metadata is quarantined to the `corrupt/` subdirectory instead of being
 * silently destroyed, and blob files that no item references are cleaned up
 * on demand via [cleanupOrphans].
 *
 * Items whose blob has gone missing are still published; the UI layer decides
 * how to present the MISSING state. TEXT/URL items keep their payload inline
 * in [StagedContent.contentReference] and therefore never have a missing blob.
 */
class StagedStore(private val storageDir: File) {

    private val lock = Any()
    private val items = linkedMapOf<String, StagedContent>()
    private val _itemsFlow = MutableStateFlow<List<StagedContent>>(emptyList())
    val itemsFlow: StateFlow<List<StagedContent>> = _itemsFlow.asStateFlow()

    val blobDir: File = File(storageDir, "blobs")

    /** SHA-256 -> contentId index used to avoid duplicate staging. */
    private val indexFile = File(storageDir, "blob_index.properties")
    private val index = Properties().apply {
        try {
            if (indexFile.isFile) FileInputStream(indexFile).use { load(it) }
        } catch (_: Exception) { }
    }

    init {
        storageDir.mkdirs()
        blobDir.mkdirs()
        loadFromDisk()
        publish()
    }

    /**
     * Streams a blob into the store without holding it in memory.
     *
     * The copy is written to a `.part` temp file first; only on success is it
     * atomically renamed into place and recorded in the dedupe index. The
     * stream is bounded by [MAX_BLOB_BYTES] mid-copy. Returns null when the
     * content could not be staged (oversize, unreadable, I/O failure).
     */
    suspend fun stageBlob(
        displayName: String,
        mimeType: String?,
        sourceApplication: String?,
        contentType: ContentType,
        authorizationContext: String? = null,
        content: InputStream,
        onProgress: (copiedBytes: Long) -> Unit = {}
    ): StageResult? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val safeName = ContentSanitizer.sanitizeFilename(displayName).ifBlank { "blob" }
        val part = File(blobDir, "flowww_${now}_${nonce()}_$safeName.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        var oversize = false
        try {
            BufferedInputStream(content).use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read.toLong()
                        if (copied > MAX_BLOB_BYTES) {
                            oversize = true
                            break
                        }
                        onProgress(copied)
                    }
                }
            }
        } catch (_: Exception) {
            part.delete()
            return@withContext null
        }
        if (oversize) {
            part.delete()
            return@withContext null
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        synchronized(lock) {
            val existingId = index.getProperty(hash)
            val existing = existingId?.let { items[it] }
            if (existing != null && !existing.isExpired() && blobState(existing) == BlobState.AVAILABLE) {
                // Identical content is already staged; reuse it instead of duplicating bytes.
                part.delete()
                return@withContext StageResult(existing, duplicate = true)
            }
            var final = File(blobDir, "flowww_${now}_${nonce()}_$safeName")
            var suffix = 1
            while (final.exists()) {
                final = File(blobDir, "flowww_${now}_${nonce()}_${suffix}_$safeName")
                suffix++
            }
            if (!part.renameTo(final)) {
                part.delete()
                return@withContext null
            }
            val item = StagedContent(
                contentId = newContentId(),
                contentType = contentType,
                lifecycleState = ContentLifecycleState.STAGED,
                displayName = ContentSanitizer.sanitizeDisplayName(displayName),
                mimeType = mimeType,
                size = copied,
                sourceApplication = sourceApplication,
                createdAt = now,
                expiresAt = now + DEFAULT_TTL_MS,
                contentReference = final.absolutePath,
                authorizationContext = authorizationContext
            )
            items[item.contentId] = item
            saveToDisk(item)
            index.setProperty(hash, item.contentId)
            saveIndex()
            publishLocked()
            StageResult(item, duplicate = false)
        }
    }

    /**
     * Stages inline TEXT/URL content. Duplicate payloads resolve to the
     * existing item instead of creating a second entry.
     */
    suspend fun stageText(
        text: String,
        contentType: ContentType,
        sourceApplication: String?
    ): StageResult? = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray()
        if (bytes.size > MAX_BLOB_BYTES) return@withContext null
        val now = System.currentTimeMillis()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        synchronized(lock) {
            val existingId = index.getProperty(hash)
            val existing = existingId?.let { items[it] }
            if (existing != null && !existing.isExpired()) {
                return@withContext StageResult(existing, duplicate = true)
            }
            val item = StagedContent(
                contentId = newContentId(),
                contentType = contentType,
                lifecycleState = ContentLifecycleState.STAGED,
                displayName = ContentSanitizer.sanitizeDisplayName(
                    if (contentType == ContentType.URL) "Shared link" else "Shared text"
                ),
                mimeType = "text/plain",
                size = bytes.size.toLong(),
                sourceApplication = sourceApplication,
                createdAt = now,
                expiresAt = now + DEFAULT_TTL_MS,
                contentReference = text,
                authorizationContext = null
            )
            items[item.contentId] = item
            saveToDisk(item)
            index.setProperty(hash, item.contentId)
            saveIndex()
            publishLocked()
            StageResult(item, duplicate = false)
        }
    }

    private fun saveIndex() {
        val tmp = File(storageDir, "blob_index.properties.tmp")
        try {
            FileOutputStream(tmp).use { index.store(it, "flowww blob dedupe index") }
            moveOver(tmp, indexFile)
        } catch (_: Exception) {
            tmp.delete()
        }
    }

    /**
     * Atomically replaces [target] with [source]. `File.renameTo` cannot replace
     * an existing target on Windows, so prefer `Files.move` with REPLACE_EXISTING
     * (atomic where the platform supports it) and degrade gracefully otherwise.
     */
    private fun moveOver(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                source.delete()
            }
        }
    }

    private fun nonce(): String = UUID.randomUUID().toString().take(8)

    private companion object {
        const val DEFAULT_TTL_MS = 30L * 60L * 1000L
        const val MAX_BLOB_BYTES = 100L * 1024L * 1024L
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
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

    /**
     * Marks expired items expired and removes them from the store and disk.
     * Returns the number of items actually cleaned up.
     */
    suspend fun expire(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val expired = items.values.filter { it.isExpired(now) }.toList()
            for (item in expired) {
                removeLocked(item.contentId)
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

    /**
     * Whether the item's blob is present on disk. TEXT/URL items are always
     * available because their payload is stored inline in the metadata.
     */
    fun blobState(item: StagedContent): BlobState = when (item.contentType) {
        ContentType.TEXT, ContentType.URL -> BlobState.AVAILABLE
        else -> {
            val ref = item.contentReference
            val file = try { File(ref) } catch (_: Exception) { null }
            if (file == null || !file.isFile || !file.exists()) BlobState.MISSING else BlobState.AVAILABLE
        }
    }

    /**
     * Total bytes occupied by staged blobs whose payload is on disk.
     * TEXT/URL items contribute their inline payload size instead.
     */
    fun usageBytes(): Long = items.values.sumOf { item ->
        if (item.size != null && item.size >= 0) item.size else blobLength(item)
    }

    private fun blobLength(item: StagedContent): Long = try {
        File(item.contentReference).takeIf { it.isFile }?.length() ?: 0L
    } catch (_: Exception) { 0L }

    /**
     * Best-effort removal of blob files that no live item references, plus
     * stale metadata files that fail to deserialize. Returns files removed.
     */
    suspend fun cleanupOrphans(): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val referenced = items.values
                .mapNotNull { it.contentReference }
                .toHashSet()
            var removed = 0
            blobDir.listFiles()?.forEach { file ->
                if (file.isFile && file.absolutePath !in referenced) {
                    if (file.delete()) removed++
                }
            }
            removed
        }
    }

    private fun removeLocked(contentId: String): StagedContent? {
        val removed = items.remove(contentId)
        File(storageDir, "$contentId.staged").delete()
        removed?.contentReference?.let { ref ->
            // best-effort cleanup for temp files created by sharesheet
            try {
                val f = File(ref)
                if (f.exists() && (f.parentFile?.name?.contains("flowww", ignoreCase = true) == true || f.name.startsWith("flowww_"))) {
                    f.delete()
                }
            } catch (_: Exception) {}
        }
        return removed
    }

    private fun saveToDisk(item: StagedContent) {
        val file = File(storageDir, "${item.contentId}.staged")
        val tmp = File(storageDir, "${item.contentId}.staged.tmp")
        try {
            ObjectOutputStream(FileOutputStream(tmp)).use { it.writeObject(item) }
            // Atomic replace (renameTo cannot overwrite on Windows).
            moveOver(tmp, file)
        } catch (_: Exception) {
            tmp.delete()
            // best-effort persistence; keep in-memory item
        }
    }

    private fun loadFromDisk() {
        storageDir.listFiles()?.filter { it.extension == "staged" }?.forEach { file ->
            try {
                // Close the stream even if deserialization fails; a leaked handle
                // would block the quarantine move below on Windows.
                file.inputStream().use { fis ->
                    ObjectInputStream(fis).use { ois ->
                        @Suppress("UNCHECKED_CAST")
                        val item = ois.readObject() as? StagedContent ?: return@use
                        if (item.isExpired()) {
                            file.delete()
                        } else {
                            items[item.contentId] = item
                        }
                    }
                }
            } catch (_: Exception) {
                // Quarantine corrupt metadata instead of destroying it.
                val quarantine = File(storageDir, "corrupt")
                quarantine.mkdirs()
                val target = File(quarantine, file.name)
                try {
                    Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    file.delete()
                }
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

enum class BlobState { AVAILABLE, MISSING }
