package com.flowww.android.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Calibration profile persistence.
 *
 * The profile is written with Java serialization, so the profile type itself
 * must be serializable and the write must be atomic and non-fatal: a failed
 * write is reported, never thrown, because it happens on the main thread of
 * the diagnostics screen.
 */
class SensorCalibrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun profile(validated: Boolean = true) = CalibrationProfile(
        createdAt = 1_700_000_000_000L,
        baselineAccelX = 0.1f,
        baselineAccelY = 0.2f,
        baselineAccelZ = 9.7f,
        noiseFloor = 0.35f,
        sendThreshold = 7.5f,
        receiveThreshold = 6.5f,
        sendDirectionX = 0f,
        sendDirectionY = 1f,
        sendDirectionZ = 0f,
        receiveDirectionX = 0f,
        receiveDirectionY = -1f,
        receiveDirectionZ = 0f,
        twistThreshold = 2.2f,
        twistAxis = 0,
        minConfidence = 0.72f,
        validated = validated
    )

    @Test
    fun savedProfileIsReloadedFromDisk() {
        val calibration = SensorCalibration(temp.newFolder("calibration"))
        val saved = profile()

        val result = calibration.saveProfile(saved)

        assertTrue("saveProfile should succeed: $result", result.isSuccess)
        val loaded = calibration.loadProfile()
        assertNotNull("profile should be reloaded from disk", loaded)
        assertEquals(saved, loaded)
        assertTrue(loaded!!.isValid())
    }

    @Test
    fun savingTwiceReplacesThePreviousProfile() {
        val calibration = SensorCalibration(temp.newFolder("calibration"))

        calibration.saveProfile(profile().copy(minConfidence = 0.3f))
        calibration.saveProfile(profile().copy(minConfidence = 0.9f))

        assertEquals(0.9f, calibration.loadProfile()?.minConfidence ?: 0f, 0.0001f)
    }

    @Test
    fun loadProfileReturnsNullWhenNothingWasSaved() {
        val calibration = SensorCalibration(temp.newFolder("calibration"))
        assertNull(calibration.loadProfile())
    }

    @Test
    fun unreadableProfileFallsBackToNullInsteadOfThrowing() {
        val dir = temp.newFolder("calibration")
        val calibration = SensorCalibration(dir)
        File(dir, "calibration.profile").writeText("not a serialized profile")

        assertNull(calibration.loadProfile())
    }

    @Test
    fun saveProfileReportsFailureInsteadOfThrowing() {
        // A file where a directory is expected: the write cannot succeed.
        val blocker = temp.newFile("blocker")
        val calibration = SensorCalibration(File(blocker, "nested"))

        val result = calibration.saveProfile(profile())

        assertTrue("saveProfile must not throw and must report a failure", result.isFailure)
    }

    @Test
    fun defaultProfileIsNotValidUntilCalibrationRuns() {
        val calibration = SensorCalibration(temp.newFolder("calibration"))
        assertTrue(!calibration.defaultProfile().isValid())
        assertTrue(profile().isValid())
    }
}
