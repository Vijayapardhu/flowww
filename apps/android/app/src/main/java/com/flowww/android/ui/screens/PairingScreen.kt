package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader

@Composable
fun PairingScreen(
    onPairingUriEntered: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var pairingUri by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        FlowwwSectionHeader(title = "Pairing", subtitle = "Pair with a Windows desktop.")
        FlowwwCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Pairing URI", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFA1A1A1))
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = pairingUri,
                    onValueChange = { pairingUri = it },
                    label = { Text(text = "Enter pairing URI") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                FlowwwButton(onClick = { onPairingUriEntered(pairingUri) }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "Pair Device")
                }
            }
        }
    }
}