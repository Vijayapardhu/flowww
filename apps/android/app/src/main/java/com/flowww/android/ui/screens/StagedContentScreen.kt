package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader

@Composable
fun StagedContentScreen(
    onContentTap: (String) -> Unit = {},
    onStageContent: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        FlowwwSectionHeader(
            title = "Ready to send",
            subtitle = "Stage files, links, or shared content before transferring."
        )

        FlowwwCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Quick tip", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F5F5))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Use Android Share to send content to Flowww, then pick a trusted device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFA1A1A1)
                )
                Spacer(modifier = Modifier.height(16.dp))
                FlowwwButton(onClick = onStageContent, modifier = Modifier.width(170.dp)) {
                    Text(text = "Stage content")
                }
            }
        }

        FlowwwCard(modifier = Modifier.fillMaxWidth()) {
            EmptyState(
                icon = {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = Color(0xFF707070)
                    )
                },
                title = "Nothing staged yet",
                subtitle = "Shared items and prepared transfers will show up here."
            )
        }
    }
}