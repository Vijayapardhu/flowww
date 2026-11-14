package com.flowww.android.sensor

/**
 * MotionFilter applies gravity estimation, linear acceleration calculation,
 * and low-pass filtering to raw sensor samples.
 *
 * Gravity is estimated using a low-pass filter on the raw accelerometer
 * readings. The linear acceleration is the raw reading minus the estimated
 * gravity component.
 */
class MotionFilter(
    private val alpha: Float = 0.1f,
    private val gyroAlpha: Float = 0.1f
) {

    private var gravityX: Float = 0f
    private var gravityY: Float = 0f
    private var gravityZ: Float = 0f
    private var initialized: Boolean = false

    /**
     * Process a raw [SensorSample] into a [ProcessedSample].
     */
    fun process(sample: SensorSample): ProcessedSample {
        if (!initialized) {
            gravityX = sample.accelerometerX
            gravityY = sample.accelerometerY
            gravityZ = sample.accelerometerZ
            initialized = true
        }

        // Low-pass filter for gravity estimation
        gravityX = alpha * sample.accelerometerX + (1 - alpha) * gravityX
        gravityY = alpha * sample.accelerometerY + (1 - alpha) * gravityY
        gravityZ = alpha * sample.accelerometerZ + (1 - alpha) * gravityZ

        // Linear acceleration = raw - gravity
        val linearX = sample.accelerometerX - gravityX
        val linearY = sample.accelerometerY - gravityY
        val linearZ = sample.accelerometerZ - gravityZ

        // Calibrated Y is just the raw linear Y at this stage;
        // calibration mapping is applied in GestureClassifier.
        val calibratedY = linearY

        val gx = sample.gyroscopeX ?: 0f
        val gy = sample.gyroscopeY ?: 0f
        val gz = sample.gyroscopeZ ?: 0f
        val rotationMag = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz).toFloat()

        return ProcessedSample(
            timestampNanos = sample.timestampNanos,
            linearAccelX = linearX,
            linearAccelY = linearY,
            linearAccelZ = linearZ,
            calibratedY = calibratedY,
            gyroscopeX = gx,
            gyroscopeY = gy,
            gyroscopeZ = gz,
            rotationMagnitude = rotationMag
        )
    }

    /** Reset the gravity estimator (call when starting a new calibration). */
    fun reset() {
        initialized = false
    }
}
