package com.flowww.android.sensor

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GestureEngine is the core motion recognition engine.
 *
 * It processes raw sensor samples through filtering and classification,
 * applies false-positive protection, and emits semantic gesture events.
 *
 * The engine must not directly implement network transfers.
 */
class GestureEngine(
    private val calibration: SensorCalibration,
    private val motionFilter: MotionFilter = MotionFilter(),
    private val listener: (GestureEvent) -> Unit = {}
) {

    private var profile: CalibrationProfile = calibration.defaultProfile()
    private var state: GestureState = GestureState.IDLE
    private var currentGestureType: GestureType = GestureType.NONE
    private var gestureStartTime: Long = 0
    private var gestureSamples: MutableList<ProcessedSample> = mutableListOf()
    private var lastEventTime: Long = 0
    private var lastGestureType: GestureType? = null
    private var eventQueue = ConcurrentLinkedQueue<GestureEvent>()

    private var cooldownMs: Long = profile.cooldownMs
    private var refractoryMs: Long = 500
    private var minConfidence: Float = profile.minConfidence

    /**
     * Current calibration status.
     */
    val calibrationStatus: CalibrationStatus
        get() = if (profile.validated) CalibrationStatus.CALIBRATED else CalibrationStatus.NOT_CALIBRATED

    /**
     * Whether the engine is currently armed (ready to detect gestures).
     */
    var isArmed: Boolean = false
        private set

    /**
     * Initialize the engine by loading any saved calibration profile.
     */
    fun initialize() {
        val saved = calibration.loadProfile()
        if (saved != null && saved.isValid()) {
            profile = saved
            cooldownMs = profile.cooldownMs
            minConfidence = profile.minConfidence
        } else {
            profile = calibration.defaultProfile()
            cooldownMs = profile.cooldownMs
            minConfidence = profile.minConfidence
        }
        resetState()
    }

    /**
     * Arm the engine for gesture detection. Must be called before
     * processSample() will produce events.
     */
    fun arm() {
        isArmed = true
        resetState()
    }

    /**
     * Disarm the engine. No events will be emitted until re-armed.
     */
    fun disarm() {
        isArmed = false
        resetState()
    }

    /**
     * Process a raw sensor sample. Returns any detected gesture event.
     */
    fun processSample(sample: SensorSample): GestureEvent? {
        if (!isArmed) return null

        val processed = motionFilter.process(sample)
        val now = System.currentTimeMillis()

        // Cooldown check
        if (now - lastEventTime < cooldownMs) {
            return null
        }

        // Refractory period after a gesture
        if (lastGestureType != null && now - lastEventTime < refractoryMs) {
            return null
        }

        // Collect samples during a potential gesture
        gestureSamples.add(processed)

        // Detect gesture start: significant deviation from baseline
        if (state == GestureState.IDLE) {
            val deviation = kotlin.math.abs(processed.calibratedY) +
                processed.rotationMagnitude * 2.0f
            if (deviation > profile.noiseFloor * 3.0f) {
                state = GestureState.GESTURE_DETECTED
                gestureStartTime = now
                currentGestureType = GestureType.NONE
                gestureSamples.clear()
                gestureSamples.add(processed)
            }
            return null
        }

        // We're in a gesture window
        if (state == GestureState.GESTURE_DETECTED) {
            // Check max duration
            if (now - gestureStartTime > profile.maxImpulseMs) {
                // Gesture too long, reject
                state = GestureState.REJECTED
                resetState()
                return null
            }

            // Check return-to-neutral (gesture complete)
            if (isReturnToNeutral(processed)) {
                state = GestureState.CONFIRMED
                return emitClassifiedGesture(now)
            }

            return null
        }

        return null
    }

    private fun emitClassifiedGesture(now: Long): GestureEvent? {
        val classifier = GestureClassifier(profile)
        val event = classifier.classify(gestureSamples, now)

        when (event) {
            is GestureEvent.Send, is GestureEvent.Receive, is GestureEvent.Cancel -> {
                lastEventTime = now
                lastGestureType = when (event) {
                    is GestureEvent.Send -> GestureType.SEND
                    is GestureEvent.Receive -> GestureType.RECEIVE
                    is GestureEvent.Cancel -> GestureType.CANCEL
                    else -> null
                }
                state = GestureState.IDLE
                gestureSamples.clear()
                listener.invoke(event)
                return event
            }
            is GestureEvent.LowConfidence -> {
                state = GestureState.IDLE
                gestureSamples.clear()
                listener.invoke(event)
                return event
            }
            is GestureEvent.CalibrationRequired -> {
                state = GestureState.IDLE
                gestureSamples.clear()
                listener.invoke(event)
                return event
            }
            else -> {
                state = GestureState.IDLE
                gestureSamples.clear()
                return null
            }
        }
    }

    private fun isReturnToNeutral(sample: ProcessedSample): Boolean {
        return kotlin.math.abs(sample.calibratedY) < profile.noiseFloor * 2.0f
    }

    private fun resetState() {
        state = GestureState.IDLE
        currentGestureType = GestureType.NONE
        gestureStartTime = 0
        gestureSamples.clear()
    }

    /**
     * Update the calibration profile (e.g., after user calibration).
     */
    fun updateProfile(newProfile: CalibrationProfile) {
        profile = newProfile
        cooldownMs = newProfile.cooldownMs
        minConfidence = newProfile.minConfidence
        calibration.saveProfile(newProfile)
        resetState()
    }

    /**
     * Get the current calibration profile.
     */
    fun getProfile(): CalibrationProfile = profile

    /**
     * Manually trigger an idle event (for diagnostics).
     */
    fun emitIdle() {
        val now = System.currentTimeMillis()
        listener.invoke(GestureEvent.Idle(now))
    }

    /**
     * Get the event queue for batch processing.
     */
    fun drainEvents(): List<GestureEvent> {
        val events = mutableListOf<GestureEvent>()
        var event: GestureEvent?
        while (eventQueue.isNotEmpty()) {
            event = eventQueue.poll()
            if (event != null) events.add(event)
        }
        return events
    }
}
