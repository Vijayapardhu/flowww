package com.flowww.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flowww.android.sensor.GestureDiagnosticsViewModel
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwTopBar
import com.flowww.android.ui.theme.FlowwwOrangeAccent
import com.flowww.android.ui.viewmodel.AppViewModelFactory

private val TextPrimary = Color(0xFFF5F5F5)
private val TextSecondary = Color(0xFFA1A1A1)
private val TextMuted = Color(0xFF707070)
private val Positive = Color(0xFF4CAF50)

/**
 * Explicit, visible gesture session.
 *
 * Sensors run ONLY between Start and Stop; [DisposableEffect] stops the
 * session when the screen is left, and the ViewModel unregisters the
 * listener again in onCleared(). Recognized gestures are surfaced here as
 * diagnostics; this screen never executes a transfer.
 */
@Composable
fun GestureSessionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GestureDiagnosticsViewModel = gestureSessionViewModel()
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

    DisposableEffect(Unit) {
        onDispose { viewModel.stopSession() }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FlowwwTopBar(
                title = "Gesture session",
                subtitle = "Explicit, visible session required for sensor use",
                onBack = onBack
            )
        }
        item {
            FlowwwCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Sensors, contentDescription = null, tint = FlowwwOrangeAccent)
                    Text(
                        if (isListening) "Session active" else "Session not started",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isListening) Positive else TextPrimary
                    )
                    Text(
                        "Gesture mappings: +Y = SEND, -Y = RECEIVE, twist = CANCEL. No transfers are executed in this milestone (Rust integration pending). Sensors run only while this visible session is started.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    FlowwwButton(
                        onClick = { if (isListening) viewModel.stopSession() else viewModel.startSession() },
                        enabled = calibrationStatus == com.flowww.android.sensor.CalibrationStatus.CALIBRATED,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FlowwwButtonLabel(if (isListening) "Stop session" else "Start session")
                    }
                    if (!isArmed && calibrationStatus != com.flowww.android.sensor.CalibrationStatus.CALIBRATED) {
                        FlowwwButtonOutlined(
                            onClick = { viewModel.recalibrate() },
                            modifier = Modifier.fillMaxWidth()
                        ) { FlowwwButtonLabel("Calibrate first (2 s capture)") }
                    }
                }
            }
        }
        item {
            FlowwwCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Live readout", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    Text("Calibrated Y: ${"%.2f".format(calibratedY)} m/s²", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("Direction: $motionDirection", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Confidence", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        LinearProgressIndicator(
                            progress = { confidence },
                            modifier = Modifier.padding(horizontal = 8.dp).weight(1f),
                            color = FlowwwOrangeAccent,
                            trackColor = Color(0xFF303030)
                        )
                    }
                }
            }
        }
        item {
            FlowwwCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Last gesture", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    Text(lastGesture, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                    Text(
                        "Sensors: accelerometer ${if (diagnostics.accelerometerAvailable) "available" else "unavailable"}, gyroscope ${if (diagnostics.gyroscopeAvailable) "available" else "unavailable"} · sensitivity ${"%.2f".format(sensitivity)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Text("Sensors stop automatically when you leave this screen.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }
    }
}

@Composable
private fun gestureSessionViewModel(): GestureDiagnosticsViewModel {
    val context = LocalContext.current
    return viewModel(factory = AppViewModelFactory { GestureDiagnosticsViewModel(context) })
}

