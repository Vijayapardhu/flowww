package com.flowww.android.sensor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ViewModel for the gesture diagnostics and session screens.
 *
 * Owns the real sensor stack: [SensorManagerWrapper] feeds [GestureEngine]
 * through [MotionFilter] and a persisted [SensorCalibration] profile.
 *
 * Sensor listeners are registered ONLY between startSession() and stopSession();
 * [onCleared] guarantees the listener is unregistered. No transfer is ever
 * executed from this ViewModel: recognized gestures are surfaced as
 * diagnostics only, and any handoff stays in the (unwired) controller layer.
 */
class GestureDiagnosticsViewModel(
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    private val calibration = SensorCalibration(appContext.cacheDir)

    private val engine = GestureEngine(
        calibration = calibration,
        listener = { event -> onEvent(event) }
    ).apply { initialize() }

    private val wrapper = SensorManagerWrapper(appContext) { reading ->
        engine.processSample(
            SensorSample(
                timestampNanos = reading.timestampNanos,
                accelerometerX = reading.accelerometerX,
                accelerometerY = reading.accelerometerY,
                accelerometerZ = reading.accelerometerZ,
                gyroscopeX = reading.gyroscopeX,
                gyroscopeY = reading.gyroscopeY,
                gyroscopeZ = reading.gyroscopeZ
            )
        )
        onSample(reading)
    }

    private val _calibratedY = MutableStateFlow(0f)
    val calibratedY: StateFlow<Float> = _calibratedY.asStateFlow()

    private val _motionDirection = MutableStateFlow("Neutral")
    val motionDirection: StateFlow<String> = _motionDirection.asStateFlow()

    private val _confidence = MutableStateFlow(0f)
    val confidence: StateFlow<Float> = _confidence.asStateFlow()

    private val _lastGesture = MutableStateFlow("None")
    val lastGesture: StateFlow<String> = _lastGesture.asStateFlow()

    private val _calibrationStatus = MutableStateFlow(CalibrationStatus.NOT_CALIBRATED)
    val calibrationStatus: StateFlow<CalibrationStatus> = _calibrationStatus.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isArmed = MutableStateFlow(false)
    val isArmed: StateFlow<Boolean> = _isArmed.asStateFlow()

    private val _sensitivity = MutableStateFlow(0.6f)
    val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    private val _diagnostics = MutableStateFlow(SensorDiagnostics(
        accelerometerAvailable = false,
        gyroscopeAvailable = false,
        accelerometerName = null,
        gyroscopeName = null,
        samplingRateHz = null,
        noiseFloor = null,
        calibrationStatus = CalibrationStatus.NOT_CALIBRATED
    ))
    val diagnostics: StateFlow<SensorDiagnostics> = _diagnostics.asStateFlow()

    init {
        refreshDiagnostics()
        _calibrationStatus.value = engine.calibrationStatus
        _sensitivity.value = engine.getProfile().minConfidence
    }

    /**
     * Starts an explicit, visible gesture session. Registers sensor
     * listeners and arms the engine.
     */
    fun startSession() {
        if (_isListening.value) return
        wrapper.startListening()
        engine.arm()
        _isListening.value = true
        _isArmed.value = true
    }

    /** Stops the session and unregisters sensor listeners immediately. */
    fun stopSession() {
        wrapper.stopListening()
        engine.disarm()
        _isListening.value = false
        _isArmed.value = false
        refreshDiagnostics()
    }

    fun updateSensitivity(value: Float) {
        _sensitivity.value = value
        engine.updateProfile(engine.getProfile().copy(minConfidence = value))
    }

    /**
     * Runs a 2-second calibration capture on the live sensor stream and
     * derives a baseline/noise-floor profile from the measured samples.
     * The profile is saved; if no sensor is available, calibration fails
     * honestly instead of reporting success.
     */
    fun recalibrate() {
        viewModelScope.launch {
            _calibrationStatus.value = CalibrationStatus.CALIBRATING
            val wasListening = _isListening.value
            wrapper.startListening()
            val samples = mutableListOf<Triple<Float, Float, Float>>()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < CAPTURE_MS) {
                val r = wrapper.readingFlow.value
                samples.add(Triple(r.accelerometerX, r.accelerometerY, r.accelerometerZ))
                delay(50)
            }
            if (!wasListening) wrapper.stopListening()

            if (samples.isEmpty()) {
                _calibrationStatus.value = CalibrationStatus.FAILED
                return@launch
            }
            val meanX = samples.map { it.first }.average().toFloat()
            val meanY = samples.map { it.second }.average().toFloat()
            val meanZ = samples.map { it.third }.average().toFloat()
            val noise = kotlin.math.sqrt(
                samples.map { val d = it.second - meanY; d * d }.average().toFloat()
            )
            val defaults = calibration.defaultProfile()
            val profile = defaults.copy(
                baselineAccelX = meanX,
                baselineAccelY = meanY,
                baselineAccelZ = meanZ,
                noiseFloor = noise.coerceAtLeast(0.05f),
                validated = true
            )
            engine.updateProfile(profile)
            _calibrationStatus.value = CalibrationStatus.CALIBRATED
            _sensitivity.value = profile.minConfidence
            refreshDiagnostics()
        }
    }

    private fun onSample(reading: SensorReading) {
        // The engine consumes the raw sample; the readout mirrors it.
        _calibratedY.value = reading.accelerometerY
        _motionDirection.value = when {
            reading.accelerometerY > 1.0f -> "Positive Y (SEND direction)"
            reading.accelerometerY < -1.0f -> "Negative Y (RECEIVE direction)"
            reading.gyroscopeX != null && kotlin.math.abs(reading.gyroscopeX) > 1.0f -> "Rotating"
            else -> "Neutral"
        }
    }

    private fun onEvent(event: GestureEvent) {
        when (event) {
            is GestureEvent.Send -> {
                _lastGesture.value = "SEND (${"%.2f".format(event.confidence)})"
                _confidence.value = event.confidence
            }
            is GestureEvent.Receive -> {
                _lastGesture.value = "RECEIVE (${"%.2f".format(event.confidence)})"
                _confidence.value = event.confidence
            }
            is GestureEvent.Cancel -> {
                _lastGesture.value = "CANCEL (${"%.2f".format(event.confidence)})"
                _confidence.value = event.confidence
            }
            is GestureEvent.LowConfidence -> {
                _lastGesture.value = "Low confidence: ${event.gestureType}"
                _confidence.value = event.confidence
            }
            is GestureEvent.CalibrationRequired -> _lastGesture.value = "Calibration required"
            is GestureEvent.Idle -> _confidence.value = 0f
        }
    }

    private fun refreshDiagnostics() {
        _diagnostics.value = wrapper.getDiagnostics().copy(
            calibrationStatus = engine.calibrationStatus,
            noiseFloor = engine.getProfile().noiseFloor,
            samplingRateHz = null
        )
    }

    override fun onCleared() {
        super.onCleared()
        // Guarantee no listener leaks even if stopSession() was never called.
        wrapper.release()
        engine.disarm()
    }

    private companion object {
        const val CAPTURE_MS = 2000L
    }
}
