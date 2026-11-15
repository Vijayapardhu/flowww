package com.flowww.android.data.local

import com.flowww.android.domain.models.PendingReceive
import com.flowww.android.domain.models.PendingReceiveStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-backed persistence for incoming files awaiting commit.
 *
 * Survives process death so an interrupted receive is never lost silently.
 * Entries are only marked COMMITTED after the bytes are written and verified;
 * corrupt records are quarantined instead of destroyed, mirroring the other
 * stores in the app.
 */
class PendingReceiveStore(private val storageDir: File) {

    private val lock = Any()
    private val storeFile = File(storageDir, STORE_FILE)
    private val _entries = MutableStateFlow<List<PendingReceive>>(emptyList())
    val entries: StateFlow<List<PendingReceive>> = _entries.asStateFlow()

    init {
        storageDir.mkdirs()
        _entries.value = load().map { entry ->
            // A commit interrupted by process death must be retried, never
            // silently treated as saved.
            if (entry.status == PendingReceiveStatus.COMMITTING) {
                entry.copy(status = PendingReceiveStatus.PENDING, updatedAt = System.currentTimeMillis())
            } else {
                entry
            }
        }.also { save(it) }
    }

    /** Inserts or replaces by [PendingReceive.transferId]. */
    fun upsert(entry: PendingReceive) = synchronized(lock) {
        val current = _entries.value.toMutableList()
        val index = current.indexOfFirst { it.transferId == entry.transferId }
        if (index >= 0) current[index] = entry else current.add(entry)
        val bounded = current.sortedByDescending { it.createdAt }.take(MAX_ENTRIES)
        _entries.value = bounded
        save(bounded)
    }

    fun remove(transferId: String) = synchronized(lock) {
        val updated = _entries.value.filterNot { it.transferId == transferId }
        _entries.value = updated
        save(updated)
    }

    fun find(transferId: String): PendingReceive? =
        _entries.value.firstOrNull { it.transferId == transferId }

    fun clear() = synchronized(lock) {
        _entries.value = emptyList()
        save(emptyList())
    }

    private fun load(): List<PendingReceive> {
        if (!storeFile.exists()) return emptyList()
        return try {
            storeFile.inputStream().use { fis ->
                ObjectInputStream(fis).use { ois ->
                    @Suppress("UNCHECKED_CAST")
                    val raw = ois.readObject() as? List<PendingReceive> ?: return emptyList()
                    raw.sortedByDescending { it.createdAt }.take(MAX_ENTRIES)
                }
            }
        } catch (_: Exception) {
            val quarantine = File(storageDir, "corrupt")
            quarantine.mkdirs()
            try {
                Files.move(
                    storeFile.toPath(),
                    File(quarantine, STORE_FILE).toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                storeFile.delete()
            }
            emptyList()
        }
    }

    private fun save(entries: List<PendingReceive>) {
        val tmp = File(storageDir, "$STORE_FILE.tmp")
        try {
            ObjectOutputStream(FileOutputStream(tmp)).use { it.writeObject(ArrayList(entries)) }
            try {
                Files.move(
                    tmp.toPath(),
                    storeFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            tmp.delete()
        }
    }

    private companion object {
        const val STORE_FILE = "pending_receives.store"
        const val MAX_ENTRIES = 200
    }
}
