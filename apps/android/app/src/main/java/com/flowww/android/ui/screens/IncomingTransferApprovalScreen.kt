package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader

@Composable
fun IncomingTransferApprovalScreen(transferId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color(0xFFF5F5F5)) }
                FlowwwSectionHeader(title = "Incoming transfer", subtitle = "Approval required before receive")
            }
        }
        item {
            FlowwwCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Download, contentDescription = null, tint = Color(0xFFFF7A1D))
                    Text("No incoming offer (ID: $transferId)", style = MaterialTheme.typography.titleSmall, color = Color(0xFFF5F5F5))
                    Text("Incoming offers, sender identity, file details, accept/reject, controlled storage receive, integrity verification, and open actions require Rust integration.", color = Color(0xFFA1A1A1), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowwwButtonOutlined(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Approve", maxLines = 1) }
                        FlowwwButtonOutlined(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Reject", maxLines = 1) }
                    }
                }
            }
        }
    }
}
