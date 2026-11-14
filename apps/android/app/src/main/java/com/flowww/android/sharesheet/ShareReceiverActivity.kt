package com.flowww.android.sharesheet

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagedContent
import com.flowww.android.content.newContentId
import com.flowww.android.di.StagedStoreProvider
import com.flowww.android.security.ContentSanitizer
import com.flowww.android.ui.theme.FlowwwTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        val intentSnapshot = intent
        setContent {
            FlowwwTheme {
                var status by remember { mutableStateOf("Staging content…") }
                var detail by remember { mutableStateOf("Preparing shared item for Flowww") }
                LaunchedEffect(action) {
                    try {
                        val count = withContext(Dispatchers.IO) { stageFromIntent(intentSnapshot) }
                        status = if (count > 0) "Content staged" else "Nothing to stage"
                        detail = if (count > 0) "Open Flowww → Staged to review, choose a trusted device, and approve transfer." else "The shared item could not be read."
                    } catch (e: Exception) {
                        status = "Could not stage content"
                        detail = e.message ?: "Please try sharing again."
                    }
                    delay(1400)
                    finish()
                }
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101010)) {
                    Column(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF101010)).padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(status, style = MaterialTheme.typography.titleLarge, color = Color(0xFFF5F5F5))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFA1A1A1))
                    }
                }
            }
        }
    }

    private fun stageFromIntent(intent: Intent?): Int {
        if (intent == null) return 0
        return when (intent.action) {
            Intent.ACTION_SEND -> if (stageSingle(intent)) 1 else 0
            Intent.ACTION_SEND_MULTIPLE -> stageMultiple(intent)
            else -> 0
        }
    }

    private fun stageSingle(intent: Intent): Boolean {
        val uri = intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val mime = intent.type
        return when {
            uri != null -> stageUri(uri, mime)
            !text.isNullOrBlank() -> stageText(text)
            else -> false
        }
    }

    private fun stageMultiple(intent: Intent): Int {
        val uris = intent.parcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM) ?: return 0
        val mime = intent.type
        var count = 0
        for (uri in uris) {
            if (stageUri(uri, mime)) count++
        }
        return count
    }

    private fun stageText(text: String): Boolean {
        val contentType = if (text.startsWith("http://") || text.startsWith("https://")) ContentType.URL else ContentType.TEXT
        val displayName = if (contentType == ContentType.URL) "Shared link" else "Shared text"
        val now = System.currentTimeMillis()
        val item = StagedContent(
            contentId = newContentId(),
            contentType = contentType,
            lifecycleState = ContentLifecycleState.STAGED,
            displayName = ContentSanitizer.sanitizeDisplayName(displayName),
            mimeType = "text/plain",
            size = text.toByteArray().size.toLong(),
            sourceApplication = callingPackage,
            createdAt = now,
            expiresAt = now + 30 * 60 * 1000L,
            contentReference = text,
            authorizationContext = null
        )
        kotlinx.coroutines.runBlocking { StagedStoreProvider.store.insert(item) }
        return true
    }

    private fun stageUri(uri: Uri, hintMime: String?): Boolean {
        if (!ContentSanitizer.isContentUri(uri.toString()) && uri.scheme != "content") {
            // allow content scheme only in this flow
        }
        val resolver = contentResolver
        val mime = hintMime ?: resolver.getType(uri) ?: "application/octet-stream"
        if (!ContentSanitizer.isAllowedMimeType(mime)) return false
        val display = queryDisplayName(uri) ?: deriveName(uri, mime)
        val size = querySize(uri)
        if (size != null && !ContentSanitizer.isWithinSizeLimit(size)) return false

        val safeName = ContentSanitizer.sanitizeFilename(display)
        val stagingDir = File(filesDir, "staged_blobs").apply { mkdirs() }
        val outFile = File(stagingDir, "flowww_${System.currentTimeMillis()}_$safeName")
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            }
        } ?: return false

        val actualSize = outFile.length()
        if (!ContentSanitizer.isWithinSizeLimit(actualSize)) {
            outFile.delete()
            return false
        }

        val contentType = when {
            mime.startsWith("image/") -> ContentType.IMAGE
            mime.startsWith("video/") -> ContentType.VIDEO
            mime == "application/pdf" || mime.startsWith("application/ms") || mime.startsWith("application/vnd") -> ContentType.DOCUMENT
            mime.startsWith("text/") -> ContentType.TEXT
            else -> ContentType.FILE
        }
        val now = System.currentTimeMillis()
        val item = StagedContent(
            contentId = newContentId(),
            contentType = contentType,
            lifecycleState = ContentLifecycleState.STAGED,
            displayName = safeName,
            mimeType = mime,
            size = actualSize,
            sourceApplication = callingPackage,
            createdAt = now,
            expiresAt = now + 30 * 60 * 1000L,
            contentReference = outFile.absolutePath,
            authorizationContext = null
        )
        kotlinx.coroutines.runBlocking { StagedStoreProvider.store.insert(item) }
        return true
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun querySize(uri: Uri): Long? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    } catch (_: Exception) { null }

    private fun deriveName(uri: Uri, mime: String): String {
        val base = uri.lastPathSegment?.substringAfterLast('/') ?: "shared"
        val ext = when (mime) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "video/mp4" -> ".mp4"
            "application/pdf" -> ".pdf"
            "text/plain" -> ".txt"
            else -> ""
        }
        return if (ext.isNotEmpty() && !base.endsWith(ext, true)) base + ext else base
    }

    private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(name: String): T? {
        return if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java) else @Suppress("DEPRECATION") getParcelableExtra(name)
    }

    private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtraCompat(name: String): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(name, T::class.java) else @Suppress("DEPRECATION") getParcelableArrayListExtra(name)
    }
}
