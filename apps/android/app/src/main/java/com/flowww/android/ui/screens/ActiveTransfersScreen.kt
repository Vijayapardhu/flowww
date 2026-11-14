package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ActiveTransfersScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(text = "Active Transfers", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)

        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(emptyList()) { transfer ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = androidx.compose.foundation.layout.fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.layout.Column(
                            modifier = androidx.compose.foundation.layout.weight(1f)
                                .padding(start = 16.dp)
                        ) {
                            Text(
                                text = transfer.filename ?: "Unknown",
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "${transfer.bytesTransferred} / ${transfer.size} bytes",
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = if (transfer.size > 0) transfer.bytesTransferred.toFloat() / transfer.size else 0f,
                            modifier = androidx.compose.ui.Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
