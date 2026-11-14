package com.flowww.android.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/**
 * GestureSessionActivity manages an active gesture sensing session.
 *
 * Sensors are only active during an explicit user-visible session.
 * On pause/stop/background, sensors are unregistered immediately.
 * No unrestricted background sensor polling is performed.
 */
class GestureSessionActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor
    private lateinit var gyroscope: Sensor
    private lateinit var vibrator: Vibrator

    private lateinit var engine: GestureEngine
    private lateinit var controller: GestureController

    private var isListening = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!!
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        engine = GestureEngine(
            calibration = SensorCalibration(cacheDir),
            listener = { event ->
                onGestureEvent(event)
            }
        )
        engine.initialize()
        engine.arm()

        // Controller with stub ConnectionService (mock for v4.3)
        val connectionService = object : com.flowww.android.connection.ConnectionService {
            override suspend fun getLocalDeviceInfo(): com.flowww.android.connection.DeviceInfo =
                com.flowww.android.connection.DeviceInfo(
                    deviceId = "fw_mock",
                    deviceName = "Mock Device",
                    platform = com.flowww.android.connection.Platform.Windows,
                    protocolVersion = "FLW/1.0"
                )
            override suspend fun getTrustedDevices(): List<com.flowww.android.connection.Device> =
                emptyList()
            override suspend fun discoverPeers(): List<com.flowww.android.connection.DiscoveredPeer> =
                emptyList()
            override suspend fun connectToDevice(deviceId: String): Boolean = true
            override suspend fun disconnectDevice(deviceId: String) {}
            override suspend fun isDeviceConnected(deviceId: String): Boolean = false
            override suspend fun initiateSend(deviceId: String, contentId: String):
                com.flowww.android.connection.TransferInitiationResult =
                com.flowww.android.connection.TransferInitiationResult(
                    transferId = "mock_transfer",
                    com.flowww.android.connection.TransferState.CONNECTING
                )
            override suspend fun cancelTransfer(transferId: String): Boolean = true
            override suspend fun getTransferStatus(transferId: String):
                com.flowww.android.connection.TransferRecord? = null
            override suspend fun getActiveTransfers(): List<com.flowww.android.connection.TransferRecord> =
                emptyList()
            override suspend fun shutdown() {}
        }

        controller = GestureController(
            connectionService = connectionService,
            stagedStore = com.flowww.android.storage.StagedStore(cacheDir),
            scope = kotlinx.coroutines.MainScope(),
            onResult = { result ->
                onControllerResult(result)
            }
        )
        controller.arm()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    com.flowww.android.sensor.GestureDiagnosticsScreen(
                        viewModel = com.flowww.android.sensor.GestureDiagnosticsViewModel().apply {
                            initialize(engine)
                        },
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startListening()
    }

    override fun onPause() {
        stopListening()
        super.onPause()
    }

    override fun onStop() {
        stopListening()
        super.onStop()
    }

    override fun onDestroy() {
        engine.disarm()
        controller.disarm()
        super.onDestroy()
    }

    private fun startListening() {
        if (isListening) return
        isListening = true
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopListening() {
        if (!isListening) return
        isListening = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        val sample = SensorSample(
            timestampNanos = event.timestamp,
            accelerometerX = event.values[0],
            accelerometerY = event.values[1],
            accelerometerZ = event.values[2],
            gyroscopeX = if (event.values.size > 3) event.values[3] else null,
            gyroscopeY = if (event.values.size > 4) event.values[4] else null,
            gyroscopeZ = if (event.values.size > 5) event.values[5] else null
        )
        val processed = engine.processSample(sample)
        if (processed != null) {
            // Update diagnostics view model if available
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor, accuracy: Int) {
        // Not used
    }

    private fun onGestureEvent(event: GestureEvent) {
        controller.handleEvent(event)

        // Haptic feedback for recognized gestures
        when (event) {
            is GestureEvent.Send -> {
                if (event.confidence >= 0.6f) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
            is GestureEvent.Receive -> {
                if (event.confidence >= 0.6f) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
            is GestureEvent.Cancel -> {
                if (event.confidence >= 0.6f) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
            else -> {}
        }
    }

    private fun onControllerResult(result: GestureResult) {
        when (result) {
            is GestureResult.Accepted -> {
                // Haptic feedback already provided in onGestureEvent
            }
            is GestureResult.Rejected -> {
                // Subtle haptic for rejected gestures
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            is GestureResult.Ignored -> {
                // No feedback
            }
        }
    }
}
