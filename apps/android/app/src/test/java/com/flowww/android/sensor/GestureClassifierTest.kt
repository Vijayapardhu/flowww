package com.flowww.android.sensor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GestureClassifierTest {

    private fun makeProfile(
        sendThreshold: Float = 5.0f,
        receiveThreshold: Float = 5.0f,
        noiseFloor: Float = 0.5f,
        minImpulseMs: Long = 80,
        maxImpulseMs: Long = 800,
        minConfidence: Float = 0.6f,
        validated: Boolean = true,
        twistThreshold: Float = 2.5f
    ): CalibrationProfile {
        return CalibrationProfile(
            version = 1,
            createdAt = System.currentTimeMillis(),
            baselineAccelX = 0f,
            baselineAccelY = 0f,
            baselineAccelZ = 9.81f,
            noiseFloor = noiseFloor,
            sendThreshold = sendThreshold,
            receiveThreshold = receiveThreshold,
            sendDirectionX = 0f,
            sendDirectionY = 1f,
            sendDirectionZ = 0f,
            receiveDirectionX = 0f,
            receiveDirectionY = -1f,
            receiveDirectionZ = 0f,
            twistThreshold = twistThreshold,
            twistAxis = 0,
            minImpulseMs = minImpulseMs,
            maxImpulseMs = maxImpulseMs,
            cooldownMs = 1000,
            minConfidence = minConfidence,
            validated = validated
        )
    }

    private fun makeProcessedSample(
        timestampNanos: Long,
        calibratedY: Float,
        rotationMagnitude: Float = 0f
    ): ProcessedSample {
        return ProcessedSample(
            timestampNanos = timestampNanos,
            linearAccelX = 0f,
            linearAccelY = calibratedY,
            linearAccelZ = 0f,
            calibratedY = calibratedY,
            gyroscopeX = 0f,
            gyroscopeY = 0f,
            gyroscopeZ = 0f,
            rotationMagnitude = rotationMagnitude
        )
    }

    private fun makeSampleList(
        startTimeNanos: Long,
        durationMs: Long,
        peakY: Float,
        rotationMagnitude: Float = 0f
    ): List<ProcessedSample> {
        val samples = mutableListOf<ProcessedSample>()
        val numSamples = 10
        val stepNs = (durationMs * 1_000_000L) / numSamples
        for (i in 0 until numSamples) {
            val t = startTimeNanos + i * stepNs
            val progress = if (i < numSamples / 2) i.toFloat() / (numSamples / 2f) else (numSamples - i).toFloat() / (numSamples / 2f)
            val y = peakY * progress
            samples.add(makeProcessedSample(t, y, rotationMagnitude))
        }
        return samples
    }

    @Test
    fun classify_positive_y_impulse_detects_send() {
        val profile = makeProfile(sendThreshold = 5.0f, receiveThreshold = 5.0f)
        val classifier = GestureClassifier(profile)
        val now = System.currentTimeMillis()

        val samples = makeSampleList(startTimeNanos = now * 1_000_000L, durationMs = 200, peakY = 10.0f)
        val event = classifier.classify(samples, now)

        assertNotNull(event)
        assertTrue(event is GestureEvent.Send)
        val sendEvent = event as GestureEvent.Send
        assertTrue(sendEvent.confidence >= 0.6f)
        assertTrue(sendEvent.calibratedY > 0f)
    }

    @Test
    fun classify_negative_y_impulse_detects_receive() {
        val profile = makeProfile(sendThreshold = 5.0f, receiveThreshold = 5.0f)
        val classifier = GestureClassifier(profile)
        val now = System.currentTimeMillis()

        val samples = makeSampleList(startTimeNanos = now * 1_000_000L, durationMs = 200, peakY = -10.0f)
        val event = classifier.classify(samples, now)

        assertNotNull(event)
        assertTrue(event is GestureEvent.Receive)
        val receiveEvent = event as GestureEvent.Receive
        assertTrue(receiveEvent.confidence >= 0.6f)
        assertTrue(receiveEvent.calibratedY < 0f)
    }

    @Test
    fun classify_weak_impulse_below_threshold_returns_null() {
        val profile = makeProfile(sendThreshold = 5.0f, receiveThreshold = 5.0f)
        val classifier = GestureClassifier(profile)
        val now = System.currentTimeMillis()

        val samples = makeSampleList(startTimeNanos = now * 1_000_000L, durationMs = 200, peakY = 2.0f)
        val event = classifier.classify(samples, now)

        assertFalse(event is GestureEvent.Send)
        assertFalse(event is GestureEvent.Receive)
    }

    @Test
    fun classify_rotation_twist_detects_cancel() {
        val profile = makeProfile(twistThreshold = 1.0f)
        val classifier = GestureClassifier(profile)
        val now = System.currentTimeMillis()

        val samples = makeSampleList(startTimeNanos = now * 1_000_000L, durationMs = 200, peakY = 1.0f, rotationMagnitude = 5.0f)
        val event = classifier.classify(samples, now)

        assertNotNull(event)
        assertTrue(event is GestureEvent.Cancel)
        val cancelEvent = event as GestureEvent.Cancel
        assertTrue(cancelEvent.rotationMagnitude > 0f)
    }

    @Test
    fun classify_rotation_on_unrelated_axis_does_not_trigger_cancel() {
        val profile = makeProfile(twistThreshold = 5.0f)
        val classifier = GestureClassifier(profile)
        val now = System.currentTimeMillis()

        val samples = makeSampleList(startTimeNanos = now * 1_000_000L, durationMs = 200, peakY = 1.0f, rotationMagnitude = 1.0f)
        val event = classifier.classify(samples, now)

        assertFalse(event is GestureEvent.Cancel)
    }

    @Test
    fun classify_too_short_impulse_is_rejected() {
        val profile = makeProfile(sendThreshold = 5.0f, minImpulseMs = 200)
        val classifier = GestureClassifier(profile)
        val now = System.currentTimeMillis()

        val samples = makeSampleList(startTimeNanos = now * 1_000_000L, durationMs = 50, peakY = 10.0f)
        val event = classifier.classify(samples, now)

        assertFalse(event is GestureEvent.Send)
    }

    @Test
    fun classify_too_long_impulse_is_rejected() {
        val profile = makeProfile(sendThreshold = 5.0f, maxImpulseMs = 100)
        val classifier = GestureClassifier(profile)
        val now = System.currentTimeMillis()

        val samples = makeSampleList(startTimeNanos = now * 1_000_000L, durationMs = 200, peakY = 10.0f)
        val event = classifier.classify(samples, now)

        assertFalse(event is GestureEvent.Send)
    }

    @Test
    fun classify_no_calibration_returns_calibration_required() {
        val profile = makeProfile(validated = false)
        val classifier = GestureClassifier(profile)
        val now = System.currentTimeMillis()

        val samples = makeSampleList(startTimeNanos = now * 1_000_000L, durationMs = 200, peakY = 10.0f)
        val event = classifier.classify(samples, now)

        assertTrue(event is GestureEvent.CalibrationRequired)
    }

    @Test
    fun classify_empty_samples_returns_null() {
        val profile = makeProfile()
        val classifier = GestureClassifier(profile)
        val now = System.currentTimeMillis()

        val event = classifier.classify(emptyList(), now)
        assertNull(event)
    }

    @Test
    fun classify_combined_movement_and_rotation_prefers_linear() {
        val profile = makeProfile(sendThreshold = 5.0f, twistThreshold = 5.0f)
        val classifier = GestureClassifier(profile)
        val now = System.currentTimeMillis()

        val samples = makeSampleList(startTimeNanos = now * 1_000_000L, durationMs = 200, peakY = 10.0f, rotationMagnitude = 2.0f)
        val event = classifier.classify(samples, now)

        assertTrue(event is GestureEvent.Send)
        assertFalse(event is GestureEvent.Cancel)
    }

    @Test
    fun classify_low_confidence_returns_low_confidence_event() {
        val profile = makeProfile(sendThreshold = 5.0f, minConfidence = 0.9f)
        val classifier = GestureClassifier(profile)
        val now = System.currentTimeMillis()

        // Impulse above noise floor but below threshold
        val samples = makeSampleList(startTimeNanos = now * 1_000_000L, durationMs = 200, peakY = 3.0f)
        val event = classifier.classify(samples, now)

        assertTrue(event is GestureEvent.LowConfidence)
    }
}
