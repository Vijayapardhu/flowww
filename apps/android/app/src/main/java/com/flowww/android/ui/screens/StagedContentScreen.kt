package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StagedContentScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(text = "Staged Content", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)

        Text(
            text = "No staged content.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
    }
}