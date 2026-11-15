package com.flowww.android.data.local

import com.flowww.android.domain.models.LocalTrustedDevice
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
 * File-backed persistence for the device records kept on this phone.
 *
 * Same guarantees as the other stores: atomic writes, quarantined corrupt
 * metadata and a bounded number of records.
 */
class TrustedDeviceStore(private val storageDir: File) {

    private val lock = Any()
    private val storeFile = File(storageDir, STORE_FILE)
    private val _devices = MutableStateFlow<List<LocalTrustedDevice>>(emptyList())
    val devices: StateFlow<List<LocalTrustedDevice>> = _devices.asStateFlow()

    init {
        storageDir.mkdirs()
        _devices.value = load()
    }

    /** Inserts or replaces a record, newest first. */
    fun put(device: LocalTrustedDevice) = synchronized(lock) {
        val current = _devices.value.toMutableList()
        val index = current.indexOfFirst { it.deviceId == device.deviceId }
        if (index >= 0) current[index] = device else current.add(0, device)
        val bounded = current.take(MAX_ENTRIES)
        _devices.value = bounded
        save(bounded)
    }

    fun remove(deviceId: String): Boolean = synchronized(lock) {
        val current = _devices.value
        val remaining = current.filterNot { it.deviceId == deviceId }
        if (remaining.size == current.size) return@synchronized false
        _devices.value = remaining
        save(remaining)
        true
    }

    fun find(deviceId: String): LocalTrustedDevice? =
        _devices.value.firstOrNull { it.deviceId == deviceId }

    private fun load(): List<LocalTrustedDevice> {
        if (!storeFile.exists()) return emptyList()
        return try {
            storeFile.inputStream().use { fis ->
                ObjectInputStream(fis).use { ois ->
                    @Suppress("UNCHECKED_CAST")
                    val raw = ois.readObject() as? List<LocalTrustedDevice> ?: return emptyList()
                    raw.take(MAX_ENTRIES)
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

    private fun save(devices: List<LocalTrustedDevice>) {
        val tmp = File(storageDir, "$STORE_FILE.tmp")
        try {
            ObjectOutputStream(FileOutputStream(tmp)).use { it.writeObject(ArrayList(devices)) }
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
        const val STORE_FILE = "trusted_devices.store"
        const val MAX_ENTRIES = 100
    }
}
