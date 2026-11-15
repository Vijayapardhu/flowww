package com.flowww.android.transfer

import java.io.File
import java.security.MessageDigest

/**
 * Finds the file the Rust core downloaded for a finished incoming transfer.
 *
 * The Rust core writes accepted files into the app-private download directory
 * and dedupes name collisions there ("report (1).pdf"), so the offered name
 * alone is ambiguous when the same filename arrives twice. Candidates are
 * therefore resolved by the digest the transfer reported: the file whose
 * SHA-256 matches is the one this transfer downloaded. Only when no digest is
 * known does the newest candidate win.
 *
 * The locator never leaves the download directory and never treats a missing
 * file as success.
 */
class IncomingFileLocator(private val downloadDirectory: File) {

    /** The downloaded file for [fileName], or null when it is not there. */
    fun resolve(fileName: String, expectedSha256: String?): File? {
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val candidates = downloadDirectory.listFiles()
            ?.filter { it.isFile && matchesDedupName(it.name, stem, ext) }
            ?: return null
        if (candidates.isEmpty()) return null
        if (expectedSha256 != null) {
            candidates.firstOrNull { sha256Of(it).equals(expectedSha256, ignoreCase = true) }
                ?.let { return it }
        }
        return candidates.maxByOrNull { it.lastModified() }
    }

    /** True for the offered name and the core's "(n)" dedup variants. */
    private fun matchesDedupName(name: String, stem: String, ext: String): Boolean {
        if (name == "$stem$ext") return true
        if (!name.startsWith(stem) || !name.endsWith(ext)) return false
        val middle = name.substring(stem.length, name.length - ext.length)
        return middle.matches(Regex("^ \\(\\d+\\)$"))
    }

    private fun sha256Of(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private companion object {
        const val BUFFER_SIZE = 65536
    }
}
