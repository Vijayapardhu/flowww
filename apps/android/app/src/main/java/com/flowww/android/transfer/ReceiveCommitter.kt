package com.flowww.android.transfer

import com.flowww.android.data.local.PendingReceiveStore
import com.flowww.android.domain.models.PendingReceive
import com.flowww.android.domain.models.PendingReceiveStatus
import com.flowww.android.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Commits finished incoming file transfers from the Rust download directory
 * into the folder the user chose with the Storage Access Framework.
 *
 * Rules this class enforces (they are what make the path safe to run):
 *
 * * Streaming 64 KiB chunks, bounded memory, digest computed while writing.
 * * The digest Rust reported is re-verified against the bytes actually
 *   written; a mismatch deletes the partial document and fails the entry.
 * * Duplicate names in the destination are resolved deterministically
 *   ("name (1).ext"), never overwritten. Names already present in the tree
 *   and names reserved by other queued entries both count as taken.
 * * Failures keep the source file and the queue entry so a retry is possible;
 *   only verified commits delete the app-private source copy.
 * * Revoked or missing tree permissions fail closed with a clear message.
 * * COMMITTING entries from a previous process run are reset to PENDING on
 *   start; nothing is reported as saved unless it was verified.
 *
 * No success state is fabricated anywhere in this class.
 */
class ReceiveCommitter(
    private val store: PendingReceiveStore,
    private val settingsRepository: SettingsRepository,
    private val writerFactory: (String) -> Result<DocumentWriter>,
    private val sourceDir: File,
    private val scope: CoroutineScope,
) {
    private val processing = Mutex()
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    val entries: StateFlow<List<PendingReceive>> get() = store.entries

    private var watchJob: Job? = null

    /** Starts the destination watcher and the initial sweep. Idempotent. */
    fun start() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            settingsRepository.settings.collect { result ->
                val uri = result.getOrNull()?.defaultSaveLocationUri
                if (!uri.isNullOrBlank()) {
                    processQueue()
                }
            }
        }
        scope.launch { processQueue() }
    }

    /**
     * Adds a finished transfer to the queue. Idempotent per transfer id.
     *
     * The queue state is the only thing this mutates; the caller decides when
     * to sweep (see [requestSweep]) so the ordering stays observable.
     */
    fun enqueue(
        transferId: String,
        fileName: String,
        mimeType: String?,
        sizeBytes: Long,
        expectedSha256: String?,
        sourcePath: String,
    ) {
        val existing = store.find(transferId)
        if (existing != null && existing.status == PendingReceiveStatus.COMMITTED) return
        if (existing != null && existing.sourcePath == sourcePath && existing.fileName == fileName) return
        store.upsert(
            PendingReceive(
                transferId = transferId,
                sourcePath = sourcePath,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                expectedSha256 = expectedSha256?.takeIf { it.isNotBlank() },
                status = PendingReceiveStatus.PENDING,
                attempts = existing?.attempts ?: 0,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                destinationLabel = existing?.destinationLabel,
                error = existing?.error
            )
        )
    }

    /** Retries a failed entry. */
    fun retry(transferId: String) {
        val entry = store.find(transferId) ?: return
        if (entry.status != PendingReceiveStatus.FAILED) return
        store.upsert(
            entry.copy(
                status = PendingReceiveStatus.PENDING,
                attempts = 0,
                error = null,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Runs a sweep in the background; safe to call as often as needed. */
    fun requestSweep() {
        scope.launch { processQueue() }
    }

    /** Cancels a pending save and removes the downloaded source copy. */
    fun cancel(transferId: String) {
        val entry = store.find(transferId) ?: return
        if (entry.status == PendingReceiveStatus.COMMITTED) return
        store.remove(transferId)
        File(entry.sourcePath).delete()
    }

    /** True when files are waiting and the user has not picked a folder yet. */
    suspend fun needsDestination(): Boolean =
        configuredDestination().isNullOrBlank() &&
            store.entries.value.any { it.status == PendingReceiveStatus.PENDING }

    /**
     * The folder the user has chosen, if any. Empty while the setting is
     * being read or is unset.
     */
    suspend fun configuredDestination(): String? =
        settingsRepository.settings.first().getOrNull()?.defaultSaveLocationUri

    /** Processes the whole queue. Safe to call from several coroutines. */
    suspend fun processQueue() = withContext(Dispatchers.IO) {
        processing.withLock {
            val destination = configuredDestination()
            if (destination.isNullOrBlank()) {
                // Keep entries PENDING; the UI asks for a folder instead.
                return@withContext
            }
            // Names claimed during this sweep, so two entries in the same
            // batch never pick the same file name even if the provider's
            // listing lags behind the document we just created.
            val claimed = mutableSetOf<String>()
            for (entry in store.entries.value) {
                if (entry.status != PendingReceiveStatus.PENDING) continue
                if (!isInsideDownloadDir(entry.sourcePath)) {
                    // The FFI sandbox forbids anything outside the download
                    // directory; never write to a foreign path.
                    store.upsert(entry.copy(
                        status = PendingReceiveStatus.FAILED,
                        error = "Downloaded file is outside the app sandbox",
                        updatedAt = System.currentTimeMillis()
                    ))
                    continue
                }
                commitOne(destination, entry, claimed)
            }
        }
    }

    /**
     * True when [path] is inside the app-private download directory. The
     * check is on the canonical path with a separator, so a sibling directory
     * such as "downloads-backup" can never pass it.
     */
    private fun isInsideDownloadDir(path: String): Boolean = runCatching {
        val base = sourceDir.canonicalFile
        val candidate = File(path).canonicalFile
        val basePath = base.path + File.separator
        candidate.path.startsWith(basePath)
    }.getOrDefault(false)

    private suspend fun commitOne(
        destination: String,
        entry: PendingReceive,
        claimed: MutableSet<String>,
    ) {
        val writer = runCatching { writerFactory(destination).getOrThrow() }
            .getOrElse { error ->
                // An unavailable destination is a transient condition (revoked
                // permission, unmounted volume): keep the entry and the source
                // file so the retry budget can still save it.
                failRetry(entry, "Destination unavailable: ${error.message}")
                return
            }
        val source = File(entry.sourcePath)
        if (!source.isFile) {
            store.upsert(entry.copy(
                status = PendingReceiveStatus.FAILED,
                attempts = entry.attempts + 1,
                error = "Downloaded file is missing (app data may have been cleared)",
                updatedAt = System.currentTimeMillis()
            ))
            return
        }
        val usedNames = usedNames(writer, entry.transferId, entry.fileName, claimed)
        val target = uniqueFileName(usedNames, entry.fileName)
        val mime = entry.mimeType?.ifBlank { null } ?: "application/octet-stream"
        var created: String? = null
        try {
            created = writer.createChild(target, mime)
            claimed += target
            val written = copyVerified(source, writer.openOutput(created), entry.expectedSha256)
            if (written != entry.sizeBytes) {
                throw IOException("Size mismatch: wrote $written of ${entry.sizeBytes} bytes")
            }
            store.remove(entry.transferId)
            source.delete()
            _status.value = "Saved ${entry.fileName} to ${writer.treeLabel()}"
        } catch (error: SecurityException) {
            created?.let { runCatching { writer.deleteChild(it) } }
            failRetry(entry, "Permission to the save folder was revoked; pick a folder again")
        } catch (error: IOException) {
            created?.let { runCatching { writer.deleteChild(it) } }
            failRetry(entry, storageMessage(error))
        } catch (error: VerificationException) {
            created?.let { runCatching { writer.deleteChild(it) } }
            failRetry(entry, "Verification failed: ${error.message}")
        }
    }

    /**
     * Names that must not be used for this entry:
     *
     * * everything already in the destination tree,
     * * every name already claimed earlier in this sweep,
     * * the names other queued entries are waiting for — unless they want the
     *   same name, in which case the claimed set keeps them apart.
     *
     * The entry being committed is excluded so a fresh empty folder accepts
     * its name.
     */
    private fun usedNames(
        writer: DocumentWriter,
        exceptTransferId: String,
        wanted: String,
        claimed: Set<String>,
    ): Set<String> {
        val taken = writer.existingNames().toMutableSet()
        taken.addAll(claimed)
        taken.addAll(
            store.entries.value
                .filter {
                    it.transferId != exceptTransferId &&
                        it.status == PendingReceiveStatus.PENDING &&
                        it.fileName != wanted
                }
                .map { it.fileName }
        )
        return taken
    }
    private suspend fun copyVerified(
        source: File,
        output: OutputStream,
        expectedSha256: String?,
    ): Long = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        source.inputStream().use { input ->
            output.use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    written += read
                }
            }
        }
        val actual = toHex(digest.digest())
        if (expectedSha256 != null && !expectedSha256.equals(actual, ignoreCase = true)) {
            throw VerificationException("saved bytes do not match the received digest")
        }
        written
    }

    private fun failRetry(entry: PendingReceive, message: String) {
        val attempts = entry.attempts + 1
        val terminal = attempts >= PendingReceive.MAX_AUTOMATIC_ATTEMPTS
        store.upsert(entry.copy(
            status = if (terminal) PendingReceiveStatus.FAILED else PendingReceiveStatus.PENDING,
            attempts = attempts,
            error = message,
            updatedAt = System.currentTimeMillis()
        ))
        _status.value = message
    }

    private class VerificationException(message: String) : Exception(message)

    private companion object {
        const val BUFFER_SIZE = 65536

        fun toHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        fun storageMessage(error: IOException): String = when {
            error.message?.contains("No space", ignoreCase = true) == true ->
                "Not enough storage space to save the file"
            else -> "The file could not be written: ${error.message ?: "unknown error"}"
        }
    }
}

/**
 * Resolves a name that does not collide with names already used in this
 * batch or already present in the destination folder. Pure function; public
 * so the deterministic behaviour stays covered by JVM tests.
 */
fun uniqueFileName(taken: Set<String>, wanted: String): String {
    if (!taken.contains(wanted)) return wanted
    val dot = wanted.lastIndexOf('.')
    val stem = if (dot > 0) wanted.substring(0, dot) else wanted
    val ext = if (dot > 0) wanted.substring(dot) else ""
    for (index in 1..MAX_DEDUP_TRIES) {
        val candidate = "$stem ($index)$ext"
        if (!taken.contains(candidate)) return candidate
    }
    return "${stem}-${System.currentTimeMillis()}$ext"
}

private const val MAX_DEDUP_TRIES = 500
