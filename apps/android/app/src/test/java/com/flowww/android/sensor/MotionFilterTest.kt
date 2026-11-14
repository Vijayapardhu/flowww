package com.flowww.android.sensor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotionFilterTest {

    @Test
    fun filter_initializes_gravity_from_first_sample() {
        val filter = MotionFilter(alpha = 0.1f)
        val sample = SensorSample(
            timestampNanos = 1000,
            accelerometerX = 0f,
            accelerometerY = 0f,
            accelerometerZ = 9.81f,
            gyroscopeX = 0f,
            gyroscopeY = 0f,
            gyroscopeZ = 0f
        )

        val processed = filter.process(sample)

        assertEquals(0f, processed.linearAccelX, 0.001f)
        assertEquals(0f, processed.linearAccelY, 0.001f)
        assertEquals(0f, processed.linearAccelZ, 0.001f)
    }

    @Test
    fun filter_subtracts_gravity_from_linear_acceleration() {
        val filter = MotionFilter(alpha = 0.5f)
        // First sample initializes gravity
        val s1 = SensorSample(1000, 0f, 0f, 9.81f, 0f, 0f, 0f)
        filter.process(s1)

        // Second sample with upward acceleration (positive Y)
        val s2 = SensorSample(2000, 0f, 5.0f, 9.81f, 0f, 0f, 0f)
        val processed = filter.process(s2)

        // gravityY = 0.5 * 0 + 0.5 * 9.81 = 4.905
        // linearY = 5.0 - 4.905 = 0.095
        assertTrue(processed.linearAccelY > 0f)
        assertTrue(processed.linearAccelY < 1.0f)
    }

    @Test
    fun filter_rotation_magnitude_from_gyroscope() {
        val filter = MotionFilter()
        val sample = SensorSample(
            timestampNanos = 1000,
            accelerometerX = 0f, accelerometerY = 0f, accelerometerZ = 9.81f,
            gyroscopeX = 1.0f, gyroscopeY = 0f, gyroscopeZ = 0f
        )
        val processed = filter.process(sample)
        assertEquals(1.0f, processed.rotationMagnitude, 0.001f)
    }

    @Test
    fun filter_reset_reinitializes_gravity() {
        val filter = MotionFilter(alpha = 0.5f)
        val s1 = SensorSample(1000, 0f, 0f, 9.81f, 0f, 0f, 0f)
        filter.process(s1)
        filter.reset()

        val s2 = SensorSample(2000, 1.0f, 2.0f, 3.0f, 0f, 0f, 0f)
        val processed = filter.process(s2)
        assertEquals(0f, processed.linearAccelX, 0.001f)
        assertEquals(0f, processed.linearAccelY, 0.001f)
        assertEquals(0f, processed.linearAccelZ, 0.001f)
    }
}
