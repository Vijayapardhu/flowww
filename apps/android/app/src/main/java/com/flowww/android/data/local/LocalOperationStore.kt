package com.flowww.android.data.local

import com.flowww.android.domain.models.LocalOperation
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
 * File-backed persistence for local operations (exports, shares, saves,
 * handoffs).
 *
 * The store mirrors the behaviour of the other stores in the app: writes are
 * atomic (temp file + replace), corrupt metadata is quarantined instead of
 * being destroyed, and the number of records is bounded.
 */
class LocalOperationStore(private val storageDir: File) {

    private val lock = Any()
    private val storeFile = File(storageDir, STORE_FILE)
    private val _operations = MutableStateFlow<List<LocalOperation>>(emptyList())
    val operations: StateFlow<List<LocalOperation>> = _operations.asStateFlow()

    init {
        storageDir.mkdirs()
        _operations.value = load()
    }

    /** Inserts or replaces an operation, newest first, bounded to [MAX_ENTRIES]. */
    fun put(operation: LocalOperation) = synchronized(lock) {
        val current = _operations.value.toMutableList()
        val index = current.indexOfFirst { it.id == operation.id }
        if (index >= 0) current[index] = operation else current.add(operation)
        val bounded = current.sortedByDescending { it.createdAt }.take(MAX_ENTRIES)
        _operations.value = bounded
        save(bounded)
    }

    /** Removes every operation. Returns how many were removed. */
    fun clear(): Int = synchronized(lock) {
        val removed = _operations.value.size
        _operations.value = emptyList()
        save(emptyList())
        removed
    }

    fun find(id: String): LocalOperation? =
        _operations.value.firstOrNull { it.id == id }

    private fun load(): List<LocalOperation> {
        if (!storeFile.exists()) return emptyList()
        return try {
            // Close the stream even if deserialization fails; a leaked handle
            // would block the quarantine move below on Windows.
            storeFile.inputStream().use { fis ->
                ObjectInputStream(fis).use { ois ->
                    @Suppress("UNCHECKED_CAST")
                    val raw = ois.readObject() as? List<LocalOperation> ?: return emptyList()
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

    private fun save(operations: List<LocalOperation>) {
        val tmp = File(storageDir, "$STORE_FILE.tmp")
        try {
            ObjectOutputStream(FileOutputStream(tmp)).use { it.writeObject(ArrayList(operations)) }
            // Atomic replace: renameTo cannot overwrite an existing target on Windows.
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
        const val STORE_FILE = "local_operations.store"
        const val MAX_ENTRIES = 200
    }
}
