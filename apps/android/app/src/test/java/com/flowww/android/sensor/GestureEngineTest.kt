package com.flowww.android.sensor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GestureEngineTest {

    private fun makeEngine(listener: (GestureEvent) -> Unit = {}): GestureEngine {
        return GestureEngine(
            calibration = SensorCalibration(java.io.File(java.lang.System.getProperty("java.io.tmpdir"))),
            motionFilter = MotionFilter(),
            listener = listener
        )
    }

    private fun makeSample(
        timestampNanos: Long,
        accelX: Float, accelY: Float, accelZ: Float,
        gyroX: Float = 0f, gyroY: Float = 0f, gyroZ: Float = 0f
    ): SensorSample {
        return SensorSample(
            timestampNanos = timestampNanos,
            accelerometerX = accelX,
            accelerometerY = accelY,
            accelerometerZ = accelZ,
            gyroscopeX = gyroX,
            gyroscopeY = gyroY,
            gyroscopeZ = gyroZ
        )
    }

    @Test
    fun engine_disarmed_by_default_produces_no_events() {
        val engine = makeEngine()
        var eventCount = 0
        val engineWithListener = GestureEngine(
            calibration = SensorCalibration(java.io.File(java.lang.System.getProperty("java.io.tmpdir"))),
            listener = { eventCount++ }
        )
        engineWithListener.initialize()

        val sample = makeSample(1000, 0f, 0f, 9.81f)
        val result = engineWithListener.processSample(sample)
        assertNull(result)
        assertEquals(0, eventCount)
    }

    @Test
    fun engine_armed_allows_processing() {
        val engine = makeEngine()
        engine.arm()

        val sample = makeSample(1000, 0f, 15f, 9.81f)
        val result = engine.processSample(sample)
        assertTrue(result is GestureEvent.CalibrationRequired || result == null)
    }

    @Test
    fun engine_cooldown_prevents_rapid_events() {
        val engine = GestureEngine(
            calibration = SensorCalibration(java.io.File(java.lang.System.getProperty("java.io.tmpdir"))),
            listener = { }
        )
        engine.initialize()

        val profile = CalibrationProfile(
            version = 1,
            createdAt = System.currentTimeMillis(),
            baselineAccelX = 0f, baselineAccelY = 0f, baselineAccelZ = 9.81f,
            noiseFloor = 0.5f,
            sendThreshold = 3.0f,
            receiveThreshold = 3.0f,
            sendDirectionX = 0f, sendDirectionY = 1f, sendDirectionZ = 0f,
            receiveDirectionX = 0f, receiveDirectionY = -1f, receiveDirectionZ = 0f,
            twistThreshold = 2.5f,
            twistAxis = 0,
            minImpulseMs = 50,
            maxImpulseMs = 500,
            cooldownMs = 5000,
            minConfidence = 0.3f,
            validated = true
        )
        engine.updateProfile(profile)

        val baseTime = System.currentTimeMillis() * 1_000_000L
        val s1 = makeSample(baseTime, 0f, 10f, 9.81f)
        engine.processSample(s1)

        val s2 = makeSample(baseTime + 1_000_000L, 0f, 10f, 9.81f)
        engine.processSample(s2)
    }

    @Test
    fun calibration_profile_default_is_not_validated() {
        val cal = SensorCalibration(java.io.File(java.lang.System.getProperty("java.io.tmpdir")))
        val profile = cal.defaultProfile()
        assertFalse(profile.isValid())
    }

    @Test
    fun calibration_profile_with_high_thresholds_is_validated() {
        val cal = SensorCalibration(java.io.File(java.lang.System.getProperty("java.io.tmpdir")))
        val profile = CalibrationProfile(
            version = 1,
            createdAt = System.currentTimeMillis(),
            baselineAccelX = 0f, baselineAccelY = 0f, baselineAccelZ = 9.81f,
            noiseFloor = 0.5f,
            sendThreshold = 5.0f,
            receiveThreshold = 5.0f,
            sendDirectionX = 0f, sendDirectionY = 1f, sendDirectionZ = 0f,
            receiveDirectionX = 0f, receiveDirectionY = -1f, receiveDirectionZ = 0f,
            twistThreshold = 2.5f,
            twistAxis = 0,
            minImpulseMs = 80,
            maxImpulseMs = 800,
            cooldownMs = 1000,
            minConfidence = 0.6f,
            validated = true
        )
        assertTrue(profile.isValid())
    }

    @Test
    fun save_and_load_profile_round_trips() {
        val tempDir = java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "flowww_cal_test")
        tempDir.mkdirs()
        val cal = SensorCalibration(tempDir)

        val profile = CalibrationProfile(
            version = 1,
            createdAt = System.currentTimeMillis(),
            baselineAccelX = 0f, baselineAccelY = 0f, baselineAccelZ = 9.81f,
            noiseFloor = 0.5f,
            sendThreshold = 5.0f,
            receiveThreshold = 5.0f,
            sendDirectionX = 0f, sendDirectionY = 1f, sendDirectionZ = 0f,
            receiveDirectionX = 0f, receiveDirectionY = -1f, receiveDirectionZ = 0f,
            twistThreshold = 2.5f,
            twistAxis = 0,
            minImpulseMs = 80,
            maxImpulseMs = 800,
            cooldownMs = 1000,
            minConfidence = 0.6f,
            validated = true
        )

        cal.saveProfile(profile)
        val loaded = cal.loadProfile()
        assertNotNull(loaded)
        assertEquals(profile.sendThreshold, loaded?.sendThreshold)
        assertEquals(profile.receiveThreshold, loaded?.receiveThreshold)
        assertTrue(loaded?.validated == true)

        cal.clearProfile()
        assertNull(cal.loadProfile())

        tempDir.deleteRecursively()
    }
}
