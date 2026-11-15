package com.flowww.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import android.text.format.Formatter
import com.flowww.android.content.ContentType
import com.flowww.android.connection.isTerminal
import com.flowww.android.domain.models.TransferItemUi
import com.flowww.android.ui.navigation.bottomNavItems
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
    content: @Composable RowScope.() -> Unit
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
    content: @Composable RowScope.() -> Unit
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

/** Label style guaranteed not to wrap unexpectedly inside buttons. */
@Composable
fun FlowwwButtonLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
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
                Text(text = filename ?: "Untitled", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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

@Composable
fun TransferCard(
    item: TransferItemUi,
    onClick: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val active = !item.status.isTerminal()
    FlowwwCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = FlowwwOrangeAccent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.filename, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${item.sourceLabel}  →  ${item.destinationLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA1A1A1),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusIndicator(status = item.status.name.lowercase().replaceFirstChar { c -> c.uppercase() })
            }
            if (active) {
                LinearProgressIndicator(
                    progress = { item.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = FlowwwOrangeAccent,
                    trackColor = Color(0xFF303030)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    Formatter.formatShortFileSize(context, item.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA1A1A1)
                )
                if (item.error != null) {
                    Text(item.error, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD95A5A), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                if (onCancel != null && active) {
                    TextButton(onClick = onCancel) { FlowwwButtonLabel("Cancel") }
                }
            }
        }
    }
}

@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FlowwwTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1A1), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color(0xFFF5F5F5))
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF101010),
            titleContentColor = Color(0xFFF5F5F5),
            navigationIconContentColor = FlowwwOrangeAccent,
            actionIconContentColor = FlowwwOrangeAccent
        ),
        modifier = modifier
    )
}

@Composable
fun FlowwwBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = Color(0xFF141414),
        tonalElevation = 0.dp
    ) {
        bottomNavItems.forEach { item ->
            val selected = currentRoute == item.screen.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.screen.route) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label, maxLines = 1, softWrap = false) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = FlowwwOrangeAccent,
                    selectedTextColor = FlowwwOrangeAccent,
                    indicatorColor = Color(0xFF2A211A),
                    unselectedIconColor = Color(0xFF8A8A8A),
                    unselectedTextColor = Color(0xFF8A8A8A)
                )
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmTestTag: String? = null
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (destructive) Icons.Filled.Delete else Icons.Filled.Description,
                contentDescription = null,
                tint = if (destructive) Color(0xFFD95A5A) else FlowwwOrangeAccent
            )
        },
        title = { Text(title, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFA1A1A1) ) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = if (confirmTestTag != null) Modifier.testTag(confirmTestTag) else Modifier
            ) {
                Text(confirmLabel, maxLines = 1, softWrap = false, color = if (destructive) Color(0xFFD95A5A) else FlowwwOrangeAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel, maxLines = 1, softWrap = false, color = Color(0xFFA1A1A1)) }
        },
        containerColor = Color(0xFF191919),
        titleContentColor = Color(0xFFF5F5F5),
        textContentColor = Color(0xFFA1A1A1),
        iconContentColor = FlowwwOrangeAccent,
        shape = FlowwwShapes.large
    )
}

@Composable
fun SelectionToolbar(
    selectedCount: Int,
    totalCount: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRemoveSelected: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(color = Color(0xFF222222), shape = FlowwwShapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "$selectedCount/$totalCount selected",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFFF5F5F5),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { if (allSelected) onClearSelection() else onSelectAll() }) {
                Text(if (allSelected) "Clear" else "Select all", maxLines = 1, softWrap = false, color = FlowwwOrangeAccent)
            }
            TextButton(onClick = onRemoveSelected) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFD95A5A), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Remove", maxLines = 1, softWrap = false, color = Color(0xFFD95A5A))
            }
            TextButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close selection", tint = Color(0xFFA1A1A1), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun FileTypeIcon(contentType: ContentType, mimeType: String?, tint: Color = FlowwwOrangeAccent, modifier: Modifier = Modifier) {
    val icon = when {
        contentType == ContentType.IMAGE || mimeType?.startsWith("image/") == true -> Icons.Outlined.Image
        contentType == ContentType.VIDEO || mimeType?.startsWith("video/") == true -> Icons.Outlined.Movie
        contentType == ContentType.URL -> Icons.Outlined.Link
        contentType == ContentType.TEXT || mimeType?.startsWith("text/") == true -> Icons.Outlined.Description
        else -> Icons.Outlined.Description
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier)
}

/** Alias kept for the milestone component contract. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) = FlowwwButton(onClick, modifier, enabled, content)

/** Alias kept for the milestone component contract. */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) = FlowwwButtonOutlined(onClick, modifier, enabled, content)
