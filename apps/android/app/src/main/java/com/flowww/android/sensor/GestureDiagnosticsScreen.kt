package com.flowww.android.sensor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwSectionHeader
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.theme.FlowwwOrangeAccent
import com.flowww.android.ui.theme.FlowwwShapes

private val TextPrimary = Color(0xFFF5F5F5)
private val TextSecondary = Color(0xFFA1A1A1)
private val TextMuted = Color(0xFF707070)
private val Positive = Color(0xFF4CAF50)
private val Negative = Color(0xFFD95A5A)

/**
 * Gesture diagnostics screen.
 *
 * Shows live sensor availability, calibrated readout, calibration state and
 * the last recognized gesture. Recognized gestures are reported as
 * diagnostics only; this screen never starts a transfer.
 */
@Composable
fun GestureDiagnosticsScreen(
    viewModel: GestureDiagnosticsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSession: () -> Unit = {}
) {
    val calibrationStatus by viewModel.calibrationStatus.collectAsStateWithLifecycle()
    val calibratedY by viewModel.calibratedY.collectAsStateWithLifecycle()
    val motionDirection by viewModel.motionDirection.collectAsStateWithLifecycle()
    val confidence by viewModel.confidence.collectAsStateWithLifecycle()
    val lastGesture by viewModel.lastGesture.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val isArmed by viewModel.isArmed.collectAsStateWithLifecycle()
    val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FlowwwSectionHeader(
            title = "Gesture diagnostics",
            subtitle = "Live sensor readout. Diagnostics never execute transfers."
        )

        FlowwwCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Session", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(
                        if (isListening) "Active" else "Inactive",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isListening) Positive else TextSecondary
                    )
                }
                Text(
                    if (isArmed) "Engine armed; gestures would be reported here." else "Engine idle. Start the visible session to sample motion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                FlowwwButtonOutlined(
                    onClick = { if (isListening) viewModel.stopSession() else viewModel.startSession() },
                    enabled = calibrationStatus == CalibrationStatus.CALIBRATED,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FlowwwButtonLabel(if (isListening) "Stop session" else "Start session")
                }
                Text(
                    "Gestures only run while this screen is visible and the session is started; listeners are unregistered on stop or leave.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                FlowwwButtonOutlined(
                    onClick = onOpenSession,
                    modifier = Modifier.fillMaxWidth()
                ) { FlowwwButtonLabel("Open dedicated gesture session") }
            }
        }

        FlowwwCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sensors", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                SensorRow("Accelerometer", diagnostics.accelerometerAvailable, diagnostics.accelerometerName)
                SensorRow("Gyroscope", diagnostics.gyroscopeAvailable, diagnostics.gyroscopeName)
            }
        }

        FlowwwCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live readout", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Calibrated Y", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text(
                            "${"%.2f".format(calibratedY)} m/s²",
                            style = MaterialTheme.typography.bodyLarge,
                            color = when {
                                calibratedY > 1.0f -> Positive
                                calibratedY < -1.0f -> Negative
                                else -> TextMuted
                            }
                        )
                    }
                    Column {
                        Text("Direction", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text(motionDirection, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Confidence", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { confidence },
                        modifier = Modifier.padding(horizontal = 8.dp).weight(1f),
                        color = FlowwwOrangeAccent,
                        trackColor = Color(0xFF303030)
                    )
                    Text("${"%.2f".format(confidence)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }

        FlowwwCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Calibration", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(
                    calibrationStatus.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (calibrationStatus == CalibrationStatus.CALIBRATED) Positive else Color(0xFFF9A825)
                )
                errorMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Negative
                    )
                }
                FlowwwButtonOutlined(
                    onClick = { viewModel.recalibrate() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) { FlowwwButtonLabel("Calibrate (2 s capture)") }
            }
        }

        FlowwwCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Last gesture", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(
                    lastGesture,
                    style = MaterialTheme.typography.bodyLarge,
                    color = when {
                        lastGesture.contains("SEND") -> Positive
                        lastGesture.contains("RECEIVE") -> Positive
                        lastGesture.contains("CANCEL") -> Negative
                        else -> TextMuted
                    }
                )
                Text("Mappings: +Y = SEND, -Y = RECEIVE, twist = CANCEL.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }

        FlowwwCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sensitivity", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                var localSensitivity by remember { mutableStateOf(sensitivity) }
                LaunchedEffect(sensitivity) { localSensitivity = sensitivity }
                Slider(
                    value = localSensitivity,
                    onValueChange = { localSensitivity = it },
                    onValueChangeFinished = { viewModel.updateSensitivity(localSensitivity) },
                    valueRange = 0.1f..1.0f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("${"%.2f".format(localSensitivity)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }

        Spacer(Modifier.height(24.dp))

        FlowwwButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { FlowwwButtonLabel("Back") }
    }
}

@Composable
private fun SensorRow(label: String, available: Boolean, name: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(
                name ?: (if (available) "Available" else "Unavailable"),
                style = MaterialTheme.typography.bodySmall,
                color = if (available) Positive else Negative,
                maxLines = 1,
                softWrap = false
            )
        }
        Text(
            if (available) "Available" else "Unavailable",
            style = MaterialTheme.typography.bodySmall,
            color = if (available) Positive else Negative
        )
    }
}
