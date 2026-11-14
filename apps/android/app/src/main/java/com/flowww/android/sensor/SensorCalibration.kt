package com.flowww.android.sensor

import java.io.File

/**
 * CalibrationStatus reflects where the user is in the calibration workflow.
 */
enum class CalibrationStatus {
    NOT_CALIBRATED, CALIBRATING, CALIBRATED, FAILED
}

/**
 * A saved calibration profile that maps raw sensor readings to the user's
 * gesture coordinate system.
 *
 * The convention Positive Y = SEND and Negative Y = RECEIVE is preserved
 * regardless of phone orientation; calibration adapts thresholds and
 * directional signatures to the user's holding position.
 */
data class CalibrationProfile(
    /** Version of the calibration format. */
    val version: Int = 1,
    /** Timestamp when this profile was created (epoch ms). */
    val createdAt: Long,
    /** Neutral acceleration vector (raw, m/s^2). */
    val baselineAccelX: Float,
    val baselineAccelY: Float,
    val baselineAccelZ: Float,
    /** Noise floor (standard deviation of neutral readings, m/s^2). */
    val noiseFloor: Float,
    /** Minimum calibrated Y impulse for SEND (m/s^2). */
    val sendThreshold: Float,
    /** Minimum calibrated Y impulse for RECEIVE (m/s^2, stored as positive magnitude). */
    val receiveThreshold: Float,
    /** Calibrated direction signature for SEND (unit vector X,Y,Z). */
    val sendDirectionX: Float,
    val sendDirectionY: Float,
    val sendDirectionZ: Float,
    /** Calibrated direction signature for RECEIVE (unit vector X,Y,Z). */
    val receiveDirectionX: Float,
    val receiveDirectionY: Float,
    val receiveDirectionZ: Float,
    /** Minimum integrated rotation for CANCEL (rad). */
    val twistThreshold: Float,
    /** Detected primary rotation axis during calibration (0=X,1=Y,2=Z). */
    val twistAxis: Int,
    /** Minimum impulse duration in milliseconds. */
    val minImpulseMs: Long = 80,
    /** Maximum impulse duration in milliseconds. */
    val maxImpulseMs: Long = 800,
    /** Cooldown between gestures in milliseconds. */
    val cooldownMs: Long = 1000,
    /** Minimum confidence score (0.0-1.0) to accept a gesture. */
    val minConfidence: Float = 0.6f,
    /** Whether this profile has been validated. */
    val validated: Boolean = false
) {
    fun isValid(): Boolean = validated && sendThreshold > 0 && receiveThreshold > 0 && twistThreshold > 0
}

/**
 * Result of a calibration run.
 */
data class CalibrationResult(
    val status: CalibrationStatus,
    val profile: CalibrationProfile?,
    val message: String
)

/**
 * SensorCalibration manages the calibration workflow and profile storage.
 */
class SensorCalibration(private val storageDir: File) {

    private val profileFile = File(storageDir, "calibration.profile")

    /**
     * Load the saved calibration profile, or null if none exists.
     */
    fun loadProfile(): CalibrationProfile? {
        return try {
            java.io.ObjectInputStream(profileFile.inputStream()).use { ois ->
                ois.readObject() as? CalibrationProfile
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Save a calibration profile to disk.
     */
    fun saveProfile(profile: CalibrationProfile) {
        profileFile.parentFile.mkdirs()
        java.io.ObjectOutputStream(profileFile.outputStream()).use { oos ->
            oos.writeObject(profile)
        }
    }

    /**
     * Clear the saved calibration profile.
     */
    fun clearProfile() {
        profileFile.delete()
    }

    /**
     * Build a default starting profile. This is NOT a universal guarantee
     * and should be replaced by user calibration as soon as possible.
     */
    fun defaultProfile(): CalibrationProfile {
        return CalibrationProfile(
            createdAt = System.currentTimeMillis(),
            baselineAccelX = 0f,
            baselineAccelY = 0f,
            baselineAccelZ = 9.81f,
            noiseFloor = 0.5f,
            sendThreshold = 8.0f,
            receiveThreshold = 8.0f,
            sendDirectionX = 0f,
            sendDirectionY = 1f,
            sendDirectionZ = 0f,
            receiveDirectionX = 0f,
            receiveDirectionY = -1f,
            receiveDirectionZ = 0f,
            twistThreshold = 2.5f,
            twistAxis = 0,
            minImpulseMs = 80,
            maxImpulseMs = 800,
            cooldownMs = 1000,
            minConfidence = 0.6f,
            validated = false
        )
    }
}
