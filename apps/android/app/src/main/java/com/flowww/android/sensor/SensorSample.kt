package com.flowww.android.sensor

/**
 * An immutable sensor sample captured at a specific point in time.
 *
 * Raw accelerometer and gyroscope values are in the device's sensor
 * coordinate system. No calibration or calibration-frame mapping has
 * been applied yet.
 */
data class SensorSample(
    /** Timestamp in nanoseconds (from SensorEvent.timestamp). */
    val timestampNanos: Long,
    /** Raw accelerometer X (m/s^2). */
    val accelerometerX: Float,
    /** Raw accelerometer Y (m/s^2). */
    val accelerometerY: Float,
    /** Raw accelerometer Z (m/s^2). */
    val accelerometerZ: Float,
    /** Raw gyroscope X (rad/s), or null if not available. */
    val gyroscopeX: Float?,
    /** Raw gyroscope Y (rad/s), or null if not available. */
    val gyroscopeY: Float?,
    /** Raw gyroscope Z (rad/s), or null if not available. */
    val gyroscopeZ: Float?
) {
    /** Magnitude of the raw acceleration vector. */
    fun accelerationMagnitude(): Float =
        kotlin.math.sqrt(accelerometerX * accelerometerX +
            accelerometerY * accelerometerY +
            accelerometerZ * accelerometerZ).toFloat()

    /** Magnitude of the raw gyroscope vector. */
    fun gyroscopeMagnitude(): Float {
        val gx = gyroscopeX ?: 0f
        val gy = gyroscopeY ?: 0f
        val gz = gyroscopeZ ?: 0f
        return kotlin.math.sqrt(gx * gx + gy * gy + gz * gz).toFloat()
    }
}

/**
 * A filtered, gravity-compensated sensor sample suitable for gesture
 * classification.
 *
 * Linear acceleration has had the estimated gravity component removed.
 * The calibrated Y value is expressed in the user's gesture frame.
 */
data class ProcessedSample(
    val timestampNanos: Long,
    /** Linear acceleration X (gravity removed, m/s^2). */
    val linearAccelX: Float,
    /** Linear acceleration Y (gravity removed, m/s^2). */
    val linearAccelY: Float,
    /** Linear acceleration Z (gravity removed, m/s^2). */
    val linearAccelZ: Float,
    /** Calibrated Y-axis linear acceleration (m/s^2). */
    val calibratedY: Float,
    /** Gyroscope X (rad/s). */
    val gyroscopeX: Float,
    /** Gyroscope Y (rad/s). */
    val gyroscopeY: Float,
    /** Gyroscope Z (rad/s). */
    val gyroscopeZ: Float,
    /** Rotation magnitude (rad/s). */
    val rotationMagnitude: Float
) {
    fun linearAccelMagnitude(): Float =
        kotlin.math.sqrt(linearAccelX * linearAccelX +
            linearAccelY * linearAccelY +
            linearAccelZ * linearAccelZ).toFloat()
}
