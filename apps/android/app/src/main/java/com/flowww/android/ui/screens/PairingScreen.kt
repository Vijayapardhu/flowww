package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun PairingScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(text = "Pairing", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)

        Text(
            text = "Enter pairing URI from your Windows desktop",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text(text = "Add Device")
        }
    }
}
