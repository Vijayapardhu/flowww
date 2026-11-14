package com.flowww.android.sensor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the gesture diagnostics screen.
 *
 * Exposes live sensor readout, calibration status, and gesture state
 * without storing continuous sensor histories.
 */
class GestureDiagnosticsViewModel : ViewModel() {

    private val _calibratedY = MutableStateFlow(0f)
    val calibratedY: StateFlow<Float> = _calibratedY

    private val _motionDirection = MutableStateFlow("Neutral")
    val motionDirection: StateFlow<String> = _motionDirection

    private val _confidence = MutableStateFlow(0f)
    val confidence: StateFlow<Float> = _confidence

    private val _lastGesture = MutableStateFlow("None")
    val lastGesture: StateFlow<String> = _lastGesture

    private val _calibrationStatus = MutableStateFlow(CalibrationStatus.NOT_CALIBRATED)
    val calibrationStatus: StateFlow<CalibrationStatus> = _calibrationStatus

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _isArmed = MutableStateFlow(false)
    val isArmed: StateFlow<Boolean> = _isArmed

    private val _sensitivity = MutableStateFlow(1.0f)
    val sensitivity: StateFlow<Float> = _sensitivity

    private val _diagnostics = MutableStateFlow(SensorDiagnostics(
        accelerometerAvailable = false,
        gyroscopeAvailable = false,
        accelerometerName = null,
        gyroscopeName = null,
        samplingRateHz = null,
        noiseFloor = null,
        calibrationStatus = CalibrationStatus.NOT_CALIBRATED
    ))
    val diagnostics: StateFlow<SensorDiagnostics> = _diagnostics

    private var engine: GestureEngine? = null

    fun initialize(engine: GestureEngine) {
        this.engine = engine
        _calibrationStatus.value = engine.calibrationStatus
        _sensitivity.value = engine.getProfile().minConfidence
    }

    fun startSession() {
        _isListening.value = true
        _isArmed.value = true
        engine?.arm()
    }

    fun stopSession() {
        _isListening.value = false
        _isArmed.value = false
        engine?.disarm()
    }

    fun onSample(processed: ProcessedSample) {
        _calibratedY.value = processed.calibratedY

        val direction = when {
            processed.calibratedY > 1.0f -> "Positive Y (SEND direction)"
            processed.calibratedY < -1.0f -> "Negative Y (RECEIVE direction)"
            processed.rotationMagnitude > 1.0f -> "Rotating"
            else -> "Neutral"
        }
        _motionDirection.value = direction
    }

    fun onEvent(event: GestureEvent) {
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
            is GestureEvent.CalibrationRequired -> {
                _lastGesture.value = "Calibration required"
            }
            is GestureEvent.Idle -> {
                _confidence.value = 0f
            }
        }
    }

    fun updateSensitivity(value: Float) {
        _sensitivity.value = value
        val eng = engine
        if (eng != null) {
            eng.updateProfile(eng.getProfile().copy(minConfidence = value))
        }
    }

    fun recalibrate() {
        // Recalibration triggers a new calibration workflow
        _calibrationStatus.value = CalibrationStatus.CALIBRATING
    }

    fun calibrationComplete(profile: CalibrationProfile) {
        _calibrationStatus.value = CalibrationStatus.CALIBRATED
        _sensitivity.value = profile.minConfidence
        engine?.updateProfile(profile)
    }

    override fun onCleared() {
        super.onCleared()
        engine?.disarm()
    }
}
