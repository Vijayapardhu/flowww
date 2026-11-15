package com.flowww.android.transfer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.flowww.android.domain.models.ContentKind
import com.flowww.android.domain.models.LocalContentHandle
import com.flowww.android.domain.repository.LocalContentGateway
import com.flowww.android.domain.repository.ContentShareBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/** Result of exporting staged content into a folder the user picked. */
data class ExportOutcome(
    val savedCount: Int,
    val totalBytes: Long,
    val destination: String?,
    val cancelled: Boolean = false
)

/**
 * The local content handoff workflow.
 *
 * Everything here is real Android behaviour: content is shared to other apps
 * through the system Sharesheet, exported into a folder the user chose with
 * the Storage Access Framework, copied to the clipboard, or opened with a
 * viewer. No transfer to another device is implied anywhere.
 */
class LocalContentHandoff(
    private val gateway: LocalContentGateway,
    private val contextProvider: () -> Context
) : ContentShareBuilder {

    private val context: Context get() = contextProvider()

    override fun build(handles: List<LocalContentHandle>): Result<Pair<Intent, String>> =
        buildShareIntent(handles).map { it to shareTitle(handles) }

    /**
     * Builds the system Sharesheet intent for the given items.
     *
     * Blob items travel as content:// URIs with read permission grants; inline
     * text and links travel as text extras.
     */
    fun buildShareIntent(handles: List<LocalContentHandle>): Result<Intent> {
        if (handles.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one item to share"))
        }
        val blobs = handles.filter { it.kind == ContentKind.BLOB && it.uri != null }
        val inlineText = handles.filter { it.kind != ContentKind.BLOB }
            .mapNotNull { it.text }
            .filter { it.isNotBlank() }

        val intent = when {
            blobs.isEmpty() -> Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, inlineText.joinToString("\n\n"))
            }
            inlineText.isEmpty() && blobs.size == 1 -> Intent(Intent.ACTION_SEND).apply {
                type = blobs.first().mimeType
                putExtra(Intent.EXTRA_STREAM, blobs.first().uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(blobs.first().displayName, blobs.first().uri)
            }
            else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (blobs.size == 1) blobs.first().mimeType else "*/*"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(blobs.mapNotNull { it.uri })
                )
                if (inlineText.isNotEmpty()) {
                    putExtra(Intent.EXTRA_TEXT, inlineText.joinToString("\n\n"))
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(
                    blobs.first().displayName,
                    blobs.first().uri
                )
            }
        }
        return Result.success(intent)
    }

    /** The title shown on the system Sharesheet. */
    fun shareTitle(handles: List<LocalContentHandle>): String =
        if (handles.size == 1) "Share ${handles.first().displayName}" else "Share ${handles.size} items"

    /**
     * Writes the items into the folder the user picked with the Storage
     * Access Framework.
     *
     * Bytes are streamed in chunks, progress is reported as it is written, and
     * the copy stops when [isCancelled] returns true.
     */
    suspend fun exportToTree(
        handles: List<LocalContentHandle>,
        treeUri: Uri,
        onProgress: suspend (bytesWritten: Long) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Result<ExportOutcome> = withContext(Dispatchers.IO) {
        if (handles.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Select at least one item to export"))
        }
        val resolver = context.contentResolver
        var written = 0L
        var saved = 0
        try {
            for (handle in handles) {
                if (isCancelled()) {
                    return@withContext Result.success(
                        ExportOutcome(saved, written, destinationLabel(treeUri), cancelled = true)
                    )
                }
                val mime = handle.mimeType.ifBlank { "application/octet-stream" }
                val documentUri = DocumentsContract.createDocument(
                    resolver,
                    treeUri,
                    mime,
                    handle.displayName
                ) ?: throw IOException("The chosen folder refused to create a file")

                val output = resolver.openOutputStream(documentUri)
                    ?: throw IOException("The chosen folder is not writable")
                output.use { out ->
                    gateway.openStream(handle.contentId).getOrThrow().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (isCancelled()) break
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            written += read
                            onProgress(written)
                        }
                    }
                }
                saved++
            }
            Result.success(ExportOutcome(saved, written, destinationLabel(treeUri)))
        } catch (e: SecurityException) {
            Result.failure(IOException("Permission to write to the chosen folder was denied", e))
        } catch (e: FileNotFoundException) {
            Result.failure(IOException("The chosen folder is no longer available", e))
        } catch (e: IOException) {
            Result.failure(IOException(storageMessage(e), e))
        }
    }

    /** Copies text to the system clipboard. */
    fun copyText(text: String): Result<Unit> = runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw IllegalStateException("Clipboard is unavailable")
        manager.setPrimaryClip(ClipData.newPlainText("Flowww", text))
    }

    /** Opens a URL in the browser. */
    fun openUrl(url: String): Result<Unit> = runCatching {
        val normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw IllegalArgumentException("Only http and https links can be opened")
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("No app is available to open this link")
        }
    }

    /** Opens a staged item with whichever app can view it. */
    fun openWith(handle: LocalContentHandle): Result<Unit> = runCatching {
        val uri = handle.uri
        if (handle.kind == ContentKind.BLOB) {
            if (uri == null) throw IllegalStateException("This item cannot be opened")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, handle.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                throw IllegalStateException("No app can open ${handle.displayName}")
            }
        } else {
            val text = handle.text ?: throw IllegalStateException("This item has no content")
            openUrl(text)
        }
    }

    /** A short, human readable label for a picked folder. */
    fun destinationLabel(treeUri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val name = docId.substringAfterLast(':').substringAfterLast('/')
        return name.ifBlank { "Selected folder" }
    }

    private fun storageMessage(e: IOException): String {
        val text = e.message.orEmpty()
        return when {
            text.contains("ENOSPC", ignoreCase = true) -> "Not enough storage space to finish the export"
            else -> "The export failed: ${text.ifBlank { e::class.java.simpleName }}"
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
