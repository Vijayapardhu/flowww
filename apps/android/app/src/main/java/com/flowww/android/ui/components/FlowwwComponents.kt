package com.flowww.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowww.android.ui.theme.FlowwwOrangeAccent
import com.flowww.android.ui.theme.FlowwwShapes

@Composable
fun FlowwwCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF191919)),
        shape = FlowwwShapes.medium
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun FlowwwSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(bottom = 12.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge, color = Color(0xFFA1A1A1))
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFF5F5F5),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5))
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFA1A1A1))
        }
        if (action != null) {
            Spacer(modifier = Modifier.height(24.dp))
            action()
        }
    }
}

@Composable
fun FlowwwButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = FlowwwOrangeAccent,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF303030),
            disabledContentColor = Color(0xFF707070)
        ),
        shape = FlowwwShapes.medium
    ) { content() }
}

@Composable
fun FlowwwButtonOutlined(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        border = BorderStroke(1.dp, FlowwwOrangeAccent),
        shape = FlowwwShapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = FlowwwOrangeAccent)
    ) { content() }
}

@Composable
fun FlowwwIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = FlowwwOrangeAccent,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = contentColor,
            disabledContentColor = Color(0xFF707070)
        )
    ) { content() }
}

@Composable
fun StatusIndicator(status: String, modifier: Modifier = Modifier) {
    val (color, label) = when (status.lowercase()) {
        "connected", "completed", "paired", "online", "available" -> Color(0xFF4A9E4A) to status.replaceFirstChar { it.uppercase() }
        "transferring" -> FlowwwOrangeAccent to "Transferring"
        "failed", "error", "unavailable" -> Color(0xFFD95A5A) to status.replaceFirstChar { it.uppercase() }
        else -> Color(0xFF707070) to status.replaceFirstChar { it.uppercase() }
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(8.dp), shape = FlowwwShapes.small, color = color) {}
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFA1A1A1))
    }
}

@Composable
fun FlowwwDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = Color(0xFF303030))
}

@Immutable
data class DeviceUiModel(
    val id: String,
    val name: String,
    val platform: String,
    val isConnected: Boolean,
    val isTrusted: Boolean,
    val lastSeenMs: Long? = null,
    val icon: @Composable () -> Unit = {}
)

@Composable
fun DeviceCard(
    device: DeviceUiModel,
    onClick: () -> Unit,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    FlowwwCard(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            device.icon()
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = device.name, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5))
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = device.platform, style = MaterialTheme.typography.bodySmall, color = Color(0xFF707070))
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusIndicator(status = if (device.isConnected) "connected" else "disconnected")
                }
            }
            if (onAction != null) {
                FlowwwIconButton(onClick = onAction, contentColor = FlowwwOrangeAccent) {
                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More options")
                }
            }
        }
    }
}

@Composable
fun ContentCard(
    contentName: String,
    contentType: String,
    size: String?,
    status: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlowwwCard(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.Description, contentDescription = null, tint = Color(0xFF707070))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = contentName, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5))
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(contentType, size).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF707070)
                )
            }
            StatusIndicator(status = status)
            Spacer(modifier = Modifier.width(8.dp))
            FlowwwIconButton(onClick = onAction) {
                Icon(imageVector = Icons.Default.Send, contentDescription = "Transfer")
            }
        }
    }
}

@Composable
fun TransferCard(
    filename: String?,
    sourceDevice: String?,
    destinationDevice: String?,
    size: Long,
    bytesTransferred: Long,
    status: String,
    progress: Float,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    FlowwwCard(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF707070))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = filename ?: "Untitled", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5))
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$bytesTransferred / $size bytes",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF707070)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFF5F5F5))
                StatusIndicator(status = status)
            }
            if (onCancel != null) {
                Spacer(modifier = Modifier.width(8.dp))
                FlowwwIconButton(onClick = onCancel, contentColor = Color(0xFFD95A5A)) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel")
                }
            }
        }
    }
}
