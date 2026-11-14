package com.flowww.android.sharesheet

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.flowww.android.R
import com.flowww.android.content.ContentModel
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagePayload
import com.flowww.android.content.StagedContent
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.newContentId
import com.flowww.android.di.StagedStoreProvider
import com.flowww.android.security.ContentSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * ShareReceiverActivity handles content shared via Android's native Sharesheet.
 * It supports ACTION_SEND and ACTION_SEND_MULTIPLE for text, URLs, images,
 * videos, and documents.
 *
 * Content is read via ContentResolver and content URIs. Absolute filesystem
 * paths are never assumed.
 */
class ShareReceiverActivity : ComponentActivity() {

    private val logger = Log

    private val registerResult = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val action = intent.action

        when (action) {
            Intent.ACTION_SEND -> handleSend(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple(intent)
            else -> {
                logger.w("Flowww", "Unknown intent action: $action")
                finish()
            }
        }
    }

    private fun handleSend(intent: Intent) {
        val type = intent.type
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

        when {
            uri != null -> handleUri(uri, type)
            text != null -> handleText(text)
            else -> {
                logger.w("Flowww", "No usable content in share intent")
                finish()
            }
        }
    }

    private fun handleSendMultiple(intent: Intent) {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        val type = intent.type
        if (uris.isNullOrEmpty()) {
            logger.w("Flowww", "No URIs in SEND_MULTIPLE intent")
            finish()
            return
        }
        uris.forEach { handleUri(it, type) }
    }

    private fun handleUri(uri: Uri, mimeType: String? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val contentRef = copyUriToTemporaryStorage(uri, mimeType)
                val metadata = resolveContentMetadata(uri, mimeType, contentRef)

                val stagePayload = StagePayload(
                    contentType = metadata.contentType,
                    displayName = metadata.displayName,
                    mimeType = metadata.mimeType,
                    size = metadata.size,
                    sourceApplication = "sharesheet",
                    contentReference = contentRef,
                    authorizationContext = null,
                    ttlMs = 30 * 60 * 1000L // 30 minutes
                )

                withContext(Dispatchers.Main) {
                    StagedStoreProvider.store.insert(
                        StagedContent(
                            contentId = ContentModel.newContentId(),
                            contentType = stagePayload.contentType,
                            lifecycleState = ContentLifecycleState.CREATED,
                            displayName = stagePayload.displayName,
                            mimeType = stagePayload.mimeType,
                            size = stagePayload.size,
                            sourceApplication = stagePayload.sourceApplication,
                            createdAt = System.currentTimeMillis(),
                            expiresAt = System.currentTimeMillis() + stagePayload.ttlMs!!,
                            contentReference = stagePayload.contentReference,
                            authorizationContext = stagePayload.authorizationContext
                        )
                    )
                }

                logger.i("Flowww", "Content staged: ${stagePayload.displayName}")
            } catch (e: Exception) {
                logger.e("Flowww", "Failed to stage shared content", e)
            } finally {
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }
    }

    private fun handleText(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val contentType = if (text.startsWith("http://") || text.startsWith("https://")) {
                    ContentType.URL
                } else {
                    ContentType.TEXT
                }

                val stagePayload = StagePayload(
                    contentType = contentType,
                    displayName = if (contentType == ContentType.URL) {
                        "Shared URL"
                    } else {
                        "Shared Text"
                    },
                    mimeType = "text/plain",
                    size = text.length.toLong(),
                    sourceApplication = "sharesheet",
                    contentReference = text,
                    authorizationContext = null,
                    ttlMs = 30 * 60 * 1000L
                )

                withContext(Dispatchers.Main) {
                    StagedStoreProvider.store.insert(
                        StagedContent(
                            contentId = ContentModel.newContentId(),
                            contentType = stagePayload.contentType,
                            lifecycleState = ContentLifecycleState.CREATED,
                            displayName = stagePayload.displayName,
                            mimeType = stagePayload.mimeType,
                            size = stagePayload.size,
                            sourceApplication = stagePayload.sourceApplication,
                            createdAt = System.currentTimeMillis(),
                            expiresAt = System.currentTimeMillis() + stagePayload.ttlMs!!,
                            contentReference = stagePayload.contentReference,
                            authorizationContext = stagePayload.authorizationContext
                        )
                    )
                }

                logger.i("Flowww", "Text staged: ${stagePayload.displayName}")
            } catch (e: Exception) {
                logger.e("Flowww", "Failed to stage shared text", e)
            } finally {
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }
    }

    private suspend fun copyUriToTemporaryStorage(uri: Uri, mimeType: String?): String {
        return withContext(Dispatchers.IO) {
            val contentResolver = contentResolver
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                throw FlowwwError.InvalidUri(uri.toString())
            }

            val fileName = ContentSanitizer.sanitizeFilename(
                resolveFileName(contentResolver, uri, mimeType)
            )
            val tempFile = java.io.File(cacheDir, "flowww_stage_${System.currentTimeMillis()}_$fileName")

            inputStream.use { input ->
                java.io.FileOutputStream(tempFile).use { output ->
                    copyStream(input, output)
                }
            }

            tempFile.absolutePath
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
        }
    }

    private data class ContentMetadata(
        val contentType: ContentType,
        val displayName: String,
        val mimeType: String?,
        val size: Long?
    )

    private suspend fun resolveContentMetadata(
        uri: Uri,
        mimeType: String?,
        contentReference: String
    ): ContentMetadata = withContext(Dispatchers.IO) {
        val contentResolver = contentResolver
        val resolvedMimeType = mimeType ?: contentResolver.getType(uri) ?: "application/octet-stream"

        val contentType = when {
            resolvedMimeType.startsWith("image/") -> ContentType.IMAGE
            resolvedMimeType.startsWith("video/") -> ContentType.VIDEO
            resolvedMimeType == "application/pdf" -> ContentType.DOCUMENT
            resolvedMimeType.startsWith("text/") -> ContentType.TEXT
            resolvedMimeType.startsWith("application/") -> ContentType.DOCUMENT
            else -> ContentType.FILE
        }

        val displayName = resolveFileName(contentResolver, uri, resolvedMimeType)
        val size = try {
            contentResolver.openFileDescriptor(uri, "r")?.size ?: -1L
        } catch (_: Exception) {
            -1L
        }.takeIf { it >= 0 }

        ContentMetadata(contentType, displayName, resolvedMimeType, size)
    }

    private fun resolveFileName(
        contentResolver: android.content.ContentResolver,
        uri: Uri,
        mimeType: String?
    ): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndexOrThrow("_display_name")
                    cursor.getString(nameIndex)
                } else {
                    deriveFileNameFromUri(uri, mimeType)
                }
            } ?: deriveFileNameFromUri(uri, mimeType)
        } catch (_: Exception) {
            deriveFileNameFromUri(uri, mimeType)
        }
    }

    private fun deriveFileNameFromUri(uri: Uri, mimeType: String?): String {
        val lastPathSegment = uri.lastPathSegment ?: "file"
        val extension = when (mimeType) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "video/mp4" -> ".mp4"
            "video/quicktime" -> ".mov"
            "application/pdf" -> ".pdf"
            "text/plain" -> ".txt"
            else -> null
        }
        return if (extension != null && !lastPathSegment.endsWith(extension)) {
            "$lastPathSegment$extension"
        } else {
            lastPathSegment
        }
    }
}
