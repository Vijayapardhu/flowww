package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flowww.android.content.ContentLifecycleState

@Composable
fun StagedContentScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(text = "Staged Content", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)

        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(emptyList()) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = androidx.compose.foundation.layout.fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material3.Icons.Default.Description,
                            contentDescription = "Content",
                            modifier = androidx.compose.ui.Modifier.size(24.dp)
                        )
                        androidx.compose.foundation.layout.Column(
                            modifier = androidx.compose.foundation.layout.weight(1f)
                                .padding(start = 16.dp)
                        ) {
                            Text(
                                text = item.displayName,
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = item.contentType.toString(),
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = item.lifecycleState.toString(),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
