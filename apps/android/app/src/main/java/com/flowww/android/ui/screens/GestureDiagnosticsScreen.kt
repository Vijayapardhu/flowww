package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowww.android.sensor.GestureDiagnosticsViewModel
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader

@Composable
fun GestureDiagnosticsScreen(
    viewModel: GestureDiagnosticsViewModel,
    modifier: Modifier = Modifier
) {
    val diagnostics = viewModel.diagnostics.value
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        FlowwwSectionHeader(
            title = "Gesture Diagnostics",
            subtitle = "Calibrate and test motion gestures."
        )
        SensorRow(label = "Accelerometer", available = diagnostics.accelerometerAvailable)
        SensorRow(label = "Gyroscope", available = diagnostics.gyroscopeAvailable)
    }
}

@Composable
private fun SensorRow(label: String, available: Boolean) {
    FlowwwCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFF5F5F5))
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (available) "Available" else "Unavailable",
                style = MaterialTheme.typography.labelSmall,
                color = if (available) Color(0xFF4A9E4A) else Color(0xFFD95A5A)
            )
        }
    }
}