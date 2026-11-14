package com.flowww.android.sensor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowww.android.R
import com.flowww.android.ui.components.DeviceRow

/**
 * Gesture diagnostics screen.
 *
 * Displays live sensor readout, calibration status, gesture state, and
 * provides a testing mode that recognizes gestures without initiating
 * transfers.
 */
@Composable
fun GestureDiagnosticsScreen(
    viewModel: GestureDiagnosticsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val calibrationStatus by viewModel.calibrationStatus.collectAsState()
    val calibratedY by viewModel.calibratedY.collectAsState()
    val motionDirection by viewModel.motionDirection.collectAsState()
    val confidence by viewModel.confidence.collectAsState()
    val lastGesture by viewModel.lastGesture.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isArmed by viewModel.isArmed.collectAsState()
    val sensitivity by viewModel.sensitivity.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Gesture Diagnostics",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Session controls
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Session",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = {
                            if (isListening) viewModel.stopSession() else viewModel.startSession()
                        },
                        enabled = calibrationStatus == CalibrationStatus.CALIBRATED
                    ) {
                        Text(if (isListening) "Stop Session" else "Start Session")
                    }
                    Text(
                        text = if (isListening) "Active" else "Inactive",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Sensor status
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Sensors",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Accelerometer",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = if (diagnostics.accelerometerAvailable) "Available" else "Unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (diagnostics.accelerometerAvailable)
                                MaterialTheme.colorScheme.success
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    Column {
                        Text(
                            text = "Gyroscope",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = if (diagnostics.gyroscopeAvailable) "Available" else "Unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (diagnostics.gyroscopeAvailable)
                                MaterialTheme.colorScheme.success
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Live readout
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Live Readout",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "Calibrated Y", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "${"%.2f".format(calibratedY)} m/s²",
                            style = MaterialTheme.typography.bodyLarge,
                            color = when {
                                calibratedY > 1.0f -> MaterialTheme.colorScheme.success
                                calibratedY < -1.0f -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    Column {
                        Text(text = "Direction", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = motionDirection,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Confidence", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = confidence,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                }
            }
        }

        // Calibration status
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Calibration",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = calibrationStatus.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (calibrationStatus == CalibrationStatus.CALIBRATED)
                        MaterialTheme.colorScheme.success
                    else MaterialTheme.colorScheme.warning
                )
                Button(
                    onClick = { viewModel.recalibrate() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(text = "Calibrate / Recalibrate")
                }
            }
        }

        // Last gesture
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Last Gesture",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = lastGesture,
                    style = MaterialTheme.typography.bodyLarge,
                    color = when {
                        lastGesture.contains("SEND") -> MaterialTheme.colorScheme.success
                        lastGesture.contains("RECEIVE") -> MaterialTheme.colorScheme.success
                        lastGesture.contains("CANCEL") -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // Sensitivity
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Sensitivity",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Slider(
                    value = sensitivity,
                    onValueChange = { viewModel.updateSensitivity(it) },
                    valueRange = 0.1f..1.0f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${"%.2f".format(sensitivity)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Back button
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Back")
        }
    }
}

/**
 * Gesture testing activity.
 *
 * Provides a full-screen testing mode where gestures are recognized but
 * no actual network transfers are initiated. Haptic feedback is provided
 * for recognized gestures.
 */
class GestureTestingActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var viewModel: GestureDiagnosticsViewModel

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = GestureDiagnosticsViewModel()

        val engine = GestureEngine(
            calibration = SensorCalibration(cacheDir),
            listener = { event ->
                viewModel.onEvent(event)
            }
        )
        engine.initialize()
        viewModel.initialize(engine)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GestureDiagnosticsScreen(
                        viewModel = viewModel,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.onCleared()
    }
}
