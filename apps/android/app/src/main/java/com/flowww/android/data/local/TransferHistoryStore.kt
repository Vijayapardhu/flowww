package com.flowww.android.data.local

import com.flowww.android.connection.TransferRecord
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
 * File-backed persistence for completed/failed transfer records.
 *
 * The Android layer keeps this store so transfer history survives restarts and
 * can be populated the moment the Rust event bridge exists (via
 * [record]). It never fabricates records: everything in the store came from a
 * real transfer event. Writes are atomic (temp file + rename) and corrupt
 * metadata is quarantined to the `corrupt/` subdirectory.
 *
 * The format is Java-serialized [TransferRecord] with a fixed serialVersionUID
 * on the record itself, consistent with the rest of the staging layer. If the
 * data model gains fields, existing files keep deserializing (new fields get
 * defaults) as long as serialVersionUID stays 1.
 */
class TransferHistoryStore(private val storageDir: File) {

    private val lock = Any()
    private val historyFile = File(storageDir, HISTORY_FILE)
    private val _records = MutableStateFlow<List<TransferRecord>>(emptyList())
    val records: StateFlow<List<TransferRecord>> = _records.asStateFlow()

    init {
        storageDir.mkdirs()
        _records.value = load()
    }

    fun record(record: TransferRecord) = synchronized(lock) {
        val current = _records.value.toMutableList()
        val index = current.indexOfFirst { it.id == record.id }
        if (index >= 0) {
            current[index] = record
        } else {
            current.add(record)
        }
        // Keep most recent entries, bounded to avoid unbounded growth.
        val bounded = current.sortedByDescending { it.createdAt }.take(MAX_ENTRIES)
        _records.value = bounded
        save(bounded)
    }

    fun recordAll(records: List<TransferRecord>) = synchronized(lock) {
        for (r in records) {
            _records.value = merge(_records.value, r)
        }
        save(_records.value)
    }

    private fun merge(current: List<TransferRecord>, r: TransferRecord): List<TransferRecord> {
        val list = current.toMutableList()
        val index = list.indexOfFirst { it.id == r.id }
        if (index >= 0) list[index] = r else list.add(r)
        return list.sortedByDescending { it.createdAt }.take(MAX_ENTRIES)
    }

    private fun load(): List<TransferRecord> {
        if (!historyFile.exists()) return emptyList()
        return try {
            // Close the stream even if deserialization fails; a leaked handle
            // would block the quarantine move below on Windows.
            historyFile.inputStream().use { fis ->
                ObjectInputStream(fis).use { ois ->
                    @Suppress("UNCHECKED_CAST")
                    val raw = ois.readObject() as? List<TransferRecord> ?: return emptyList()
                    raw.sortedByDescending { it.createdAt }.take(MAX_ENTRIES)
                }
            }
        } catch (_: Exception) {
            val quarantine = File(storageDir, "corrupt")
            quarantine.mkdirs()
            try {
                Files.move(historyFile.toPath(), File(quarantine, HISTORY_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                historyFile.delete()
            }
            emptyList()
        }
    }

    private fun save(records: List<TransferRecord>) {
        val tmp = File(storageDir, "$HISTORY_FILE.tmp")
        try {
            ObjectOutputStream(FileOutputStream(tmp)).use { it.writeObject(ArrayList(records)) }
            // Atomic replace: renameTo cannot overwrite an existing target on Windows.
            try {
                Files.move(tmp.toPath(), historyFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), historyFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            tmp.delete()
        }
    }

    private companion object {
        const val HISTORY_FILE = "transfer_history.store"
        const val MAX_ENTRIES = 200
    }
}
