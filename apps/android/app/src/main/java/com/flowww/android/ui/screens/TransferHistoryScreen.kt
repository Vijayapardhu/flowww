package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowww.android.ui.components.EmptyState
import com.flowww.android.ui.components.FlowwwSectionHeader

@Composable
fun TransferHistoryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        FlowwwSectionHeader(
            title = "Transfer History",
            subtitle = "View your transfer activity."
        )
        EmptyState(
            icon = {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Color(0xFF707070)
                )
            },
            title = "No transfer history yet.",
            subtitle = "Completed transfers will appear here."
        )
    }
}