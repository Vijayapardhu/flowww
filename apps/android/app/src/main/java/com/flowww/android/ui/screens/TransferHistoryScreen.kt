package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TransferHistoryScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(text = "Transfer History", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)

        Text(
            text = "No transfer history yet.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
    }
}
