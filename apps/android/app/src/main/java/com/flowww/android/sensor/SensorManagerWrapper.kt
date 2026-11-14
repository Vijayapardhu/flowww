package com.flowww.android.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sensor availability status.
 */
enum class SensorAvailability {
    AVAILABLE, UNAVAILABLE, UNKNOWN
}

/**
 * Sensor diagnostic information.
 */
data class SensorDiagnostics(
    val accelerometerAvailable: Boolean,
    val gyroscopeAvailable: Boolean,
    val accelerometerName: String?,
    val gyroscopeName: String?,
    val samplingRateHz: Float?,
    val noiseFloor: Float?,
    val calibrationStatus: CalibrationStatus
)

/**
 * Calibration status of sensors.
 */

/**
 * Raw sensor reading.
 */
data class SensorReading(
    val timestampNanos: Long,
    val accelerometerX: Float,
    val accelerometerY: Float,
    val accelerometerZ: Float,
    val gyroscopeX: Float?,
    val gyroscopeY: Float?,
    val gyroscopeZ: Float?
)

/**
 * Callback for sensor readings.
 */
typealias SensorListener = (SensorReading) -> Unit

/**
 * Sensor abstraction for motion detection (v4.3 foundation).
 *
 * This module does NOT implement gesture detection - that is v4.3.
 * It only provides sensor availability, sampling, and calibration primitives.
 *
 * No continuous background sensor polling is performed. If Android background
 * execution restrictions prevent continuous sensing, a visible foreground
 * service session with clear user consent is used.
 */
class SensorManagerWrapper(
    private val context: Context,
    private val listener: SensorListener?
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val latestReading = MutableStateFlow(SensorReading(
        timestampNanos = 0,
        accelerometerX = 0f, accelerometerY = 0f, accelerometerZ = 0f,
        gyroscopeX = null, gyroscopeY = null, gyroscopeZ = null
    ))

    private var isListening = false

    /**
     * Whether accelerometer is available on this device.
     */
    val isAccelerometerAvailable: Boolean
        get() = accelerometer != null

    /**
     * Whether gyroscope is available on this device.
     */
    val isGyroscopeAvailable: Boolean
        get() = gyroscope != null

    /**
     * Latest sensor reading as a StateFlow.
     */
    val readingFlow: StateFlow<SensorReading> = latestReading

    /**
     * Get sensor diagnostics.
     */
    fun getDiagnostics(): SensorDiagnostics {
        return SensorDiagnostics(
            accelerometerAvailable = isAccelerometerAvailable,
            gyroscopeAvailable = isGyroscopeAvailable,
            accelerometerName = accelerometer?.name,
            gyroscopeName = gyroscope?.name,
            samplingRateHz = null,
            noiseFloor = null,
            calibrationStatus = CalibrationStatus.NOT_CALIBRATED
        )
    }

    /**
     * Start listening to sensor events.
     * Call this only when the user has explicitly enabled motion features.
     */
    fun startListening() {
        if (isListening) return
        isListening = true

        if (accelerometer != null) {
            sensorManager.registerListener(
                this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        if (gyroscope != null) {
            sensorManager.registerListener(
                this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    /**
     * Stop listening to sensor events.
     */
    fun stopListening() {
        if (!isListening) return
        isListening = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val reading = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestReading.value = SensorReading(
                    timestampNanos = event.timestamp,
                    accelerometerX = event.values[0],
                    accelerometerY = event.values[1],
                    accelerometerZ = event.values[2],
                    gyroscopeX = latestReading.value.gyroscopeX,
                    gyroscopeY = latestReading.value.gyroscopeY,
                    gyroscopeZ = latestReading.value.gyroscopeZ
                )
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestReading.value = SensorReading(
                    timestampNanos = event.timestamp,
                    accelerometerX = latestReading.value.accelerometerX,
                    accelerometerY = latestReading.value.accelerometerY,
                    accelerometerZ = latestReading.value.accelerometerZ,
                    gyroscopeX = event.values[0],
                    gyroscopeY = event.values[1],
                    gyroscopeZ = event.values[2]
                )
            }
            else -> return
        }
        listener?.invoke(latestReading.value)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Not used
    }

    /**
     * Perform initial calibration. Returns calibration parameters.
     * In production, this would collect samples over a short period and
     * compute bias/gravity compensation values.
     */
    suspend fun calibrate(): CalibrationResult {
        return withContext(Dispatchers.Default) {
            // Collect samples for calibration
            val samples = mutableListOf<SensorReading>()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 2000) { // 2 seconds
                samples.add(latestReading.value)
                Thread.sleep(50)
            }

            val accelX = if (samples.isNotEmpty()) samples.fold(0f) { acc, it -> acc + it.accelerometerX } / samples.size else 0f
            val accelY = if (samples.isNotEmpty()) samples.fold(0f) { acc, it -> acc + it.accelerometerY } / samples.size else 0f
            val accelZ = if (samples.isNotEmpty()) samples.fold(0f) { acc, it -> acc + it.accelerometerZ } / samples.size else 0f

            CalibrationResult(
                status = CalibrationStatus.CALIBRATED,
                profile = null,
                message = "Calibration complete: biasX=$accelX, biasY=$accelY, biasZ=$accelZ, gravityZ=$accelZ"
            )
        }
    }

    fun release() {
        stopListening()
    }
}
