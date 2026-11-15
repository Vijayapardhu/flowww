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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import android.text.format.Formatter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.flowww.android.FlowwwApplication
import com.flowww.android.content.ContentType
import com.flowww.android.security.ContentSanitizer
import com.flowww.android.ui.MainActivity
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.theme.FlowwwTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives shares from other apps and stages them locally.
 *
 * Rules enforced here:
 * - Only `content://` URIs are accepted (never `file://`).
 * - MIME type and size are validated before and during staging.
 * - Each item is staged independently; one failure never aborts the batch.
 * - Identical content already staged is reused instead of staged twice.
 * - No network transfer is initiated; the user reviews the Staged tab.
 * - Large files are copied as streams, never loaded fully into memory.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intentSnapshot = intent
        setContent {
            FlowwwTheme {
                var done by remember { mutableStateOf(false) }
                var outcome by remember { mutableStateOf<StageOutcome?>(null) }
                var progressText by remember { mutableStateOf<String?>(null) }
                var progressFraction by remember { mutableStateOf<Float?>(null) }

                LaunchedEffect(Unit) {
                    lifecycleScope.launch {
                        outcome = stageFromIntent(intentSnapshot) { text, fraction ->
                            progressText = text
                            progressFraction = fraction
                        }
                        done = true
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101010)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF101010))
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val current = outcome
                        if (!done) {
                            CircularProgressIndicator(color = Color(0xFFFF7A1D))
                            Spacer(Modifier.height(12.dp))
                            Text("Staging content…", style = MaterialTheme.typography.titleLarge, color = Color(0xFFF5F5F5))
                            progressText?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1), maxLines = 2)
                            }
                            progressFraction?.let { fraction ->
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { fraction.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(0.8f),
                                    color = Color(0xFFFF7A1D),
                                    trackColor = Color(0xFF303030)
                                )
                            }
                        } else {
                            Text(
                                when {
                                    current == null -> "Nothing to stage"
                                    current.staged > 0 -> "Content staged"
                                    current.duplicates > 0 && current.staged == 0 -> "Already staged"
                                    else -> "Could not stage content"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                color = Color(0xFFF5F5F5)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                buildDetail(current),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFA1A1A1)
                            )
                            Spacer(Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FlowwwButtonOutlined(onClick = { finish() }) { FlowwwButtonLabel("Close") }
                                if (current != null && current.staged > 0) {
                                    FlowwwButton(onClick = {
                                        startActivity(Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                            putExtra(MainActivity.EXTRA_OPEN_ROUTE, "staged")
                                        })
                                        finish()
                                    }) { FlowwwButtonLabel("Open staged") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun buildDetail(outcome: StageOutcome?): String {
        if (outcome == null) {
            return "The shared item could not be read or is not allowed. Share files as content:// items."
        }
        val parts = mutableListOf<String>()
        if (outcome.staged > 0) parts += "${outcome.staged} item(s) staged locally"
        if (outcome.duplicates > 0) parts += "${outcome.duplicates} already staged"
        if (outcome.failed > 0) parts += "${outcome.failed} could not be staged"
        return parts.joinToString(" · ") + ". Review in Staged, choose a trusted device, and approve transfer when available."
    }

    private suspend fun stageFromIntent(
        intent: Intent?,
        onProgress: (text: String?, fraction: Float?) -> Unit
    ): StageOutcome {
        if (intent == null) return StageOutcome(0, 0, 0)
        return withContext(Dispatchers.IO) {
            when (intent.action) {
                Intent.ACTION_SEND -> stageSingle(intent, onProgress)
                Intent.ACTION_SEND_MULTIPLE -> stageMultiple(intent, onProgress)
                else -> StageOutcome(0, 0, 0)
            }
        }
    }

    private suspend fun stageSingle(intent: Intent, onProgress: (String?, Float?) -> Unit): StageOutcome {
        var staged = 0
        var duplicates = 0
        var failed = 0
        val uri = intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val mime = intent.type
        if (uri != null) {
            when (stageUri(uri, mime) { copied, total ->
                onProgress(copyProgressText(copied, total), total?.let { if (it > 0) (copied.toFloat() / it) else null })
            }) {
                StageResultKind.STAGED -> staged = 1
                StageResultKind.DUPLICATE -> duplicates = 1
                StageResultKind.FAILED -> failed = 1
            }
        }
        if (!text.isNullOrBlank()) {
            when (stageText(text)) {
                StageResultKind.STAGED -> staged += 1
                StageResultKind.DUPLICATE -> duplicates += 1
                StageResultKind.FAILED -> failed += 1
            }
        }
        if (uri == null && text.isNullOrBlank()) failed = 1
        onProgress(null, null)
        return StageOutcome(staged, duplicates, failed)
    }

    private suspend fun stageMultiple(intent: Intent, onProgress: (String?, Float?) -> Unit): StageOutcome {
        val uris = intent.parcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM) ?: return StageOutcome(0, 0, 1)
        val mime = intent.type
        var staged = 0
        var duplicates = 0
        var failed = 0
        uris.forEachIndexed { index, uri ->
            when (stageUri(uri, mime) { copied, total ->
                onProgress("Copying item ${index + 1}/${uris.size} · ${copyProgressText(copied, total)}", total?.let { if (it > 0) (copied.toFloat() / it) else null })
            }) {
                StageResultKind.STAGED -> staged++
                StageResultKind.DUPLICATE -> duplicates++
                StageResultKind.FAILED -> failed++
            }
        }
        onProgress(null, null)
        return StageOutcome(staged, duplicates, failed)
    }

    private fun copyProgressText(copied: Long, total: Long?): String {
        return if (total != null && total > 0) {
            "${Formatter.formatShortFileSize(this, copied)} / ${Formatter.formatShortFileSize(this, total)}"
        } else {
            "${Formatter.formatShortFileSize(this, copied)} copied"
        }
    }

    private suspend fun stageText(text: String): StageResultKind {
        return try {
            val store = (application as FlowwwApplication).container.stagedStore
            val type = if (text.startsWith("http://") || text.startsWith("https://")) ContentType.URL else ContentType.TEXT
            store.stageText(text, type, callingPackage)?.let { result ->
                if (result.duplicate) StageResultKind.DUPLICATE else StageResultKind.STAGED
            } ?: StageResultKind.FAILED
        } catch (e: Exception) {
            StageResultKind.FAILED
        }
    }

    private enum class StageResultKind { STAGED, DUPLICATE, FAILED }

    private suspend fun stageUri(
        uri: Uri,
        hintMime: String?,
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): StageResultKind {
        if (uri.scheme != "content") return StageResultKind.FAILED
        val resolver = contentResolver
        val mime = hintMime ?: try {
            resolver.getType(uri)
        } catch (_: Exception) {
            null
        } ?: "application/octet-stream"
        if (!ContentSanitizer.isAllowedMimeType(mime)) return StageResultKind.FAILED

        val display = queryDisplayName(uri) ?: deriveName(uri, mime)
        val sizeHint = querySize(uri)
        if (sizeHint != null && !ContentSanitizer.isWithinSizeLimit(sizeHint)) return StageResultKind.FAILED

        val contentType = contentTypeFor(mime)
        return try {
            val store = (application as FlowwwApplication).container.stagedStore
            resolver.openInputStream(uri)?.use { input ->
                val result = store.stageBlob(
                    displayName = display,
                    mimeType = mime,
                    sourceApplication = callingPackage,
                    contentType = contentType,
                    content = input,
                    onProgress = { copied -> onProgress(copied, sizeHint) }
                )
                when {
                    result == null -> StageResultKind.FAILED
                    result.duplicate -> StageResultKind.DUPLICATE
                    else -> StageResultKind.STAGED
                }
            } ?: StageResultKind.FAILED
        } catch (_: Exception) {
            // Revoked or unreadable content:// URI, or I/O failure mid-stream.
            StageResultKind.FAILED
        }
    }

    private fun contentTypeFor(mime: String): ContentType = when {
        mime.startsWith("image/") -> ContentType.IMAGE
        mime.startsWith("video/") -> ContentType.VIDEO
        mime == "application/pdf" || mime.startsWith("application/ms") || mime.startsWith("application/vnd") -> ContentType.DOCUMENT
        mime.startsWith("text/") -> ContentType.TEXT
        else -> ContentType.FILE
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
        val base = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "shared"
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

    private data class StageOutcome(val staged: Int, val duplicates: Int, val failed: Int)
}
