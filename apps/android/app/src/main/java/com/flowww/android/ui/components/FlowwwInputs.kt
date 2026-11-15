package com.flowww.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowww.android.ui.theme.flowwwColors

/** A single-line search input styled for the Flowww surfaces. */
@Composable
fun FlowwwSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    val colors = flowwwColors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = colors.textMuted
            )
        },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    )
}

/** A horizontally scrolling row of filter chips. */
@Composable
fun FlowwwFilterChips(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = flowwwColors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val label = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                containerColor = colors.surfaceElevated,
                labelColor = colors.textSecondary,
                selectedContainerColor = colors.accentContainer,
                selectedLabelColor = colors.accent
            )
            androidx.compose.material3.FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = {
                    Text(
                        option,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false
                    )
                },
                colors = label,
                border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = colors.border,
                    selectedBorderColor = colors.accent
                )
            )
        }
    }
}

/** Tone for [FlowwwInfoBanner]. */
enum class BannerTone { INFO, SUCCESS, WARNING, ERROR }

/**
 * An inline message. Errors and warnings use the semantic colours so the
 * message is readable without relying on colour alone.
 */
@Composable
fun FlowwwInfoBanner(
    message: String,
    tone: BannerTone = BannerTone.INFO,
    modifier: Modifier = Modifier
) {
    val colors = flowwwColors
    val accent = when (tone) {
        BannerTone.INFO -> colors.accent
        BannerTone.SUCCESS -> colors.success
        BannerTone.WARNING -> colors.warning
        BannerTone.ERROR -> colors.error
    }
    val icon = when (tone) {
        BannerTone.ERROR, BannerTone.WARNING -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.Info
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

/** A tappable settings row that shows a label and the current value. */
@Composable
fun FlowwwSettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = flowwwColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        if (value.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A read-only label/value pair used on detail screens. */
@Composable
fun FlowwwDetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = flowwwColors
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A selectable option, used for the theme picker and other single choices. */
@Composable
fun FlowwwOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    val colors = flowwwColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted
                )
            }
        }
        if (selected) {
            Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = colors.accent)
        }
    }
}
