package com.flowww.android.sensor

/**
 * Semantic gesture events emitted by the GestureEngine.
 *
 * The engine must not directly implement network transfers; it only emits
 * these events for a controller to consume.
 */
sealed class GestureEvent {
    data class Idle(
        val timestamp: Long
    ) : GestureEvent()

    data class Send(
        val timestamp: Long,
        val confidence: Float,
        val calibratedY: Float,
        val metrics: GestureMetrics
    ) : GestureEvent()

    data class Receive(
        val timestamp: Long,
        val confidence: Float,
        val calibratedY: Float,
        val metrics: GestureMetrics
    ) : GestureEvent()

    data class Cancel(
        val timestamp: Long,
        val confidence: Float,
        val rotationMagnitude: Float,
        val metrics: GestureMetrics
    ) : GestureEvent()

    data class CalibrationRequired(
        val timestamp: Long
    ) : GestureEvent()

    data class LowConfidence(
        val timestamp: Long,
        val gestureType: String,
        val confidence: Float
    ) : GestureEvent()
}

/**
 * Metrics captured during gesture detection for diagnostics and logging.
 */
data class GestureMetrics(
    val impulseDurationMs: Long,
    val peakCalibratedY: Float,
    val noiseLevel: Float,
    val directionAlignment: Float,
    val rotationDuringImpulse: Float,
    val returnToNeutral: Boolean
)

/**
 * Internal state of the gesture recognition state machine.
 */
enum class GestureState {
    IDLE,
    GESTURE_DETECTED,
    CONFIRMED,
    REJECTED
}

/**
 * Type of gesture being detected.
 */
enum class GestureType {
    SEND, RECEIVE, CANCEL, NONE
}
