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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flowww.android.sensor.CalibrationStatus
import com.flowww.android.sensor.GestureDiagnosticsViewModel
import com.flowww.android.sensor.GestureType
import com.flowww.android.sensor.GestureEvent
import com.flowww.android.ui.components.FlowwwButton
import com.flowww.android.ui.components.FlowwwButtonLabel
import com.flowww.android.ui.components.FlowwwButtonOutlined
import com.flowww.android.ui.components.FlowwwCard
import com.flowww.android.ui.components.FlowwwInfoBanner
import com.flowww.android.ui.components.FlowwwTopBar
import com.flowww.android.ui.theme.flowwwColors
import com.flowww.android.ui.viewmodel.AppViewModelFactory

/**
 * Explicit, visible gesture session.
 *
 * Sensors run ONLY between Start and Stop; [DisposableEffect] stops the
 * session when the screen is left, and the ViewModel unregisters the listener
 * again in onCleared().
 *
 * A recognised gesture acts on the local workflow: SEND opens the staged
 * content so the content can be shared or exported, RECEIVE opens the local
 * content area, and CANCEL stops the session. Nothing is ever sent to another
 * device.
 */
@Composable
fun GestureSessionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onGestureSend: () -> Unit = {},
    onGestureReceive: () -> Unit = {},
    onGestureCancel: () -> Unit = {},
    onGestureEvent: (GestureEvent) -> Unit = {},
    onSessionActiveChanged: (Boolean) -> Unit = {},
    viewModel: GestureDiagnosticsViewModel = gestureSessionViewModel()
) {
    val colors = flowwwColors
    val calibrationStatus by viewModel.calibrationStatus.collectAsStateWithLifecycle()
    val calibratedY by viewModel.calibratedY.collectAsStateWithLifecycle()
    val motionDirection by viewModel.motionDirection.collectAsStateWithLifecycle()
    val confidence by viewModel.confidence.collectAsStateWithLifecycle()
    val lastGesture by viewModel.lastGesture.collectAsStateWithLifecycle()
    val lastGestureType by viewModel.lastGestureType.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val isArmed by viewModel.isArmed.collectAsStateWithLifecycle()
    val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.gestureEvents.collect { onGestureEvent(it) }
    }
    LaunchedEffect(isListening) { onSessionActiveChanged(isListening) }

    // Each recognised gesture is handled once; the screen remembers the last
    // type it acted on so recomposition does not repeat the action.
    var handledType by remember { mutableStateOf<GestureType?>(null) }
    LaunchedEffect(lastGestureType) {
        val type = lastGestureType ?: return@LaunchedEffect
        if (type == handledType) return@LaunchedEffect
        handledType = type
        when (type) {
            GestureType.SEND -> onGestureSend()
            GestureType.RECEIVE -> onGestureReceive()
            GestureType.CANCEL -> onGestureCancel()
            GestureType.NONE -> Unit
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopSession() }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
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
                    Icon(Icons.Outlined.Sensors, contentDescription = null, tint = colors.accent)
                    Text(
                        if (isListening) "Session active" else "Session not started",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isListening) colors.success else colors.textPrimary
                    )
                    Text(
                        "Mappings: +Y = SEND, -Y = RECEIVE, twist = CANCEL. Gestures act on the content " +
                         "SEND transfers the latest staged item to the configured trusted target. CANCEL " +
                             "stops that transfer. RECEIVE requires a supported Windows content provider and " +
                             "will be rejected until one is available.",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    FlowwwButton(
                        onClick = { if (isListening) viewModel.stopSession() else viewModel.startSession() },
                        enabled = calibrationStatus == CalibrationStatus.CALIBRATED,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FlowwwButtonLabel(if (isListening) "Stop session" else "Start session")
                    }
                    if (!isArmed && calibrationStatus != CalibrationStatus.CALIBRATED) {
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
                    Text("Live readout", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    Text(
                        "Calibrated Y: ${"%.2f".format(calibratedY)} m/s²",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary
                    )
                    Text("Direction: $motionDirection", style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Confidence", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        LinearProgressIndicator(
                            progress = { confidence },
                            modifier = Modifier.padding(horizontal = 8.dp).weight(1f),
                            color = colors.accent,
                            trackColor = colors.surfaceElevated
                        )
                    }
                }
            }
        }
        item {
            FlowwwCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Last gesture", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    Text(lastGesture, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
                    Text(
                        "Sensors: accelerometer ${if (diagnostics.accelerometerAvailable) "available" else "unavailable"}, " +
                            "gyroscope ${if (diagnostics.gyroscopeAvailable) "available" else "unavailable"} · " +
                            "sensitivity ${"%.2f".format(sensitivity)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted
                    )
                    Text(
                        "Sensors stop automatically when you leave this screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted
                    )
                    FlowwwInfoBanner(
                        "Sensors only run while this visible session is started, and are unregistered when you leave.",
                        com.flowww.android.ui.components.BannerTone.INFO
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowwwButtonOutlined(
                            onClick = onGestureSend,
                            modifier = Modifier.weight(1f)
                        ) { FlowwwButtonLabel("Staged content") }
                        FlowwwButtonOutlined(
                            onClick = onGestureReceive,
                            modifier = Modifier.weight(1f)
                        ) { FlowwwButtonLabel("Local content") }
                    }
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
