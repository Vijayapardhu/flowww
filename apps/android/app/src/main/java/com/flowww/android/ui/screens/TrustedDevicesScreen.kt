package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flowww.android.ui.components.DeviceRow

@Composable
fun TrustedDevicesScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(text = "Trusted Devices", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)

        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(emptyList()) { device ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    DeviceRow(
                        deviceName = device.deviceName,
                        devicePlatform = device.platform.toString(),
                        isConnected = false,
                        onClick = {}
                    )
                }
            }
        }
    }
}
