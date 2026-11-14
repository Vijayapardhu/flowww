package com.flowww.android.sensor

/**
 * GestureClassifier analyzes processed sensor samples and classifies them
 * into gesture types with confidence scores.
 *
 * It is independent of UI and network code. All classification is based on
 * calibrated thresholds and directional signatures from [CalibrationProfile].
 */
class GestureClassifier(private val profile: CalibrationProfile) {

    private val minImpulseMs = profile.minImpulseMs
    private val maxImpulseMs = profile.maxImpulseMs
    private val minConfidence = profile.minConfidence

    /**
     * Classify a sequence of processed samples into a gesture event.
     *
     * Returns null if no gesture is confidently detected.
     */
    fun classify(samples: List<ProcessedSample>, now: Long): GestureEvent? {
        if (samples.isEmpty()) return null

        // Check if calibrated
        if (!profile.isValid()) {
            return GestureEvent.CalibrationRequired(now)
        }

        // Analyze the sample window
        val analysis = analyzeWindow(samples)

        // Try to classify as SEND or RECEIVE (linear Y impulse)
        val linearResult = classifyLinearImpulse(analysis, now)
        if (linearResult != null) {
            when (linearResult) {
                is GestureEvent.Send -> {
                    if (linearResult.confidence >= minConfidence) {
                        return linearResult
                    }
                }
                is GestureEvent.Receive -> {
                    if (linearResult.confidence >= minConfidence) {
                        return linearResult
                    }
                }
                else -> {}
            }
        }

        // Try to classify as CANCEL (rotation/twist)
        val twistResult = classifyTwist(analysis, now)
        if (twistResult is GestureEvent.Cancel && twistResult.confidence >= minConfidence) {
            return twistResult
        }

        // Low confidence detection
        val lowConf = classifyLowConfidence(analysis, now)
        if (lowConf != null) return lowConf

        return GestureEvent.Idle(now)
    }

    private data class WindowAnalysis(
        val startTime: Long,
        val endTime: Long,
        val durationMs: Long,
        val maxCalibratedY: Float,
        val minCalibratedY: Float,
        val meanCalibratedY: Float,
        val peakRotationMagnitude: Float,
        val integratedRotation: Float,
        val noiseLevel: Float,
        val samples: List<ProcessedSample>
    )

    private fun analyzeWindow(samples: List<ProcessedSample>): WindowAnalysis {
        val startTime = samples.first().timestampNanos / 1_000_000
        val endTime = samples.last().timestampNanos / 1_000_000
        val durationMs = endTime - startTime

        val maxY = samples.maxByOrNull { it.calibratedY }?.calibratedY ?: 0f
        val minY = samples.minByOrNull { it.calibratedY }?.calibratedY ?: 0f
        val meanY = if (samples.isNotEmpty()) samples.fold(0f) { acc, it -> acc + it.calibratedY } / samples.size else 0f

        val peakRot = samples.maxByOrNull { it.rotationMagnitude }?.rotationMagnitude ?: 0f
        var integratedRot = 0f
        for (i in 1 until samples.size) {
            val dt = (samples[i].timestampNanos - samples[i - 1].timestampNanos) / 1_000_000f / 1000f
            integratedRot += samples[i].rotationMagnitude * dt
        }

        val meanYVal = if (samples.isNotEmpty()) samples.fold(0f) { acc, it -> acc + it.calibratedY } / samples.size else 0f
        val variance = if (samples.isNotEmpty()) samples.fold(0f) { acc, it -> acc + (it.calibratedY - meanYVal) * (it.calibratedY - meanYVal) } / samples.size else 0f
        val noiseLevel = kotlin.math.sqrt(kotlin.math.max(0.0f, variance)).toFloat()

        return WindowAnalysis(
            startTime = startTime,
            endTime = endTime,
            durationMs = durationMs,
            maxCalibratedY = maxY,
            minCalibratedY = minY,
            meanCalibratedY = meanY,
            peakRotationMagnitude = peakRot,
            integratedRotation = integratedRot,
            noiseLevel = noiseLevel,
            samples = samples
        )
    }

    private fun classifyLinearImpulse(analysis: WindowAnalysis, now: Long): GestureEvent? {
        val durationMs = analysis.durationMs
        if (durationMs < minImpulseMs || durationMs > maxImpulseMs) return null

        val posImpulse = analysis.maxCalibratedY
        val negImpulse = -analysis.minCalibratedY

        // Check if impulse exceeds calibrated thresholds
        val sendMagnitude = posImpulse - profile.sendThreshold
        val receiveMagnitude = negImpulse - profile.receiveThreshold

        if (sendMagnitude <= 0 && receiveMagnitude <= 0) return null

        // Determine direction alignment with calibrated signatures
        val sendAlignment = directionAlignment(analysis, profile)
        val receiveAlignment = directionAlignment(analysis, CalibrationProfile(
            version = profile.version,
            createdAt = profile.createdAt,
            baselineAccelX = profile.baselineAccelX,
            baselineAccelY = profile.baselineAccelY,
            baselineAccelZ = profile.baselineAccelZ,
            noiseFloor = profile.noiseFloor,
            sendThreshold = profile.sendThreshold,
            receiveThreshold = profile.receiveThreshold,
            sendDirectionX = -profile.sendDirectionX,
            sendDirectionY = -profile.sendDirectionY,
            sendDirectionZ = -profile.sendDirectionZ,
            receiveDirectionX = profile.receiveDirectionX,
            receiveDirectionY = profile.receiveDirectionY,
            receiveDirectionZ = profile.receiveDirectionZ,
            twistThreshold = profile.twistThreshold,
            twistAxis = profile.twistAxis,
            minImpulseMs = profile.minImpulseMs,
            maxImpulseMs = profile.maxImpulseMs,
            cooldownMs = profile.cooldownMs,
            minConfidence = profile.minConfidence,
            validated = profile.validated
        ))

        val isSend = sendMagnitude > receiveMagnitude && sendAlignment > receiveAlignment
        val isReceive = receiveMagnitude > sendMagnitude && receiveAlignment > sendAlignment

        if (!isSend && !isReceive) return null

        val peakY = if (isSend) analysis.maxCalibratedY else analysis.minCalibratedY
        val threshold = if (isSend) profile.sendThreshold else profile.receiveThreshold
        val magnitude = if (isSend) sendMagnitude else receiveMagnitude
        val alignment = if (isSend) sendAlignment else receiveAlignment

        // Confidence based on magnitude excess, alignment, duration, return-to-neutral
        val magnitudeScore = kotlin.math.min(1.0f, magnitude / (threshold * 2.0f)).toFloat()
        val durationScore = kotlin.math.min(1.0f, durationMs.toFloat() / 300.0f).toFloat()
        val alignmentScore = kotlin.math.max(0.0f, alignment)
        val returnToNeutral = checkReturnToNeutral(analysis)

        val confidence = (magnitudeScore * 0.4f + alignmentScore * 0.3f +
            durationScore * 0.2f + (if (returnToNeutral) 0.1f else 0.0f)).coerceIn(0f, 1f)

        val metrics = GestureMetrics(
            impulseDurationMs = durationMs,
            peakCalibratedY = peakY,
            noiseLevel = analysis.noiseLevel,
            directionAlignment = alignment,
            rotationDuringImpulse = analysis.integratedRotation,
            returnToNeutral = returnToNeutral
        )

        return if (isSend) {
            GestureEvent.Send(now, confidence, peakY, metrics)
        } else {
            GestureEvent.Receive(now, confidence, peakY, metrics)
        }
    }

    private fun classifyTwist(analysis: WindowAnalysis, now: Long): GestureEvent? {
        if (analysis.durationMs < minImpulseMs || analysis.durationMs > maxImpulseMs) return null

        // A twist requires significant integrated rotation but minimal linear impulse
        val linearImpulse = kotlin.math.max(
            analysis.maxCalibratedY,
            -analysis.minCalibratedY
        )

        if (linearImpulse > profile.sendThreshold * 0.5f) return null // Too much linear movement

        if (analysis.integratedRotation < profile.twistThreshold) return null

        val rotationScore = kotlin.math.min(1.0f, analysis.integratedRotation / (profile.twistThreshold * 2.0f)).toFloat()
        val linearPenalty = kotlin.math.max(0.0f, 1.0f - linearImpulse / profile.sendThreshold)
        val durationScore = kotlin.math.min(1.0f, analysis.durationMs.toFloat() / 400.0f).toFloat()

        val confidence = (rotationScore * 0.5f + linearPenalty * 0.3f + durationScore * 0.2f).coerceIn(0f, 1f)

        if (confidence < minConfidence) return null

        return GestureEvent.Cancel(
            timestamp = now,
            confidence = confidence,
            rotationMagnitude = analysis.peakRotationMagnitude,
            metrics = GestureMetrics(
                impulseDurationMs = analysis.durationMs,
                peakCalibratedY = linearImpulse,
                noiseLevel = analysis.noiseLevel,
                directionAlignment = linearPenalty,
                rotationDuringImpulse = analysis.integratedRotation,
                returnToNeutral = checkReturnToNeutral(analysis)
            )
        )
    }

    private fun classifyLowConfidence(analysis: WindowAnalysis, now: Long): GestureEvent? {
        val maxAbsY = kotlin.math.max(kotlin.math.abs(analysis.maxCalibratedY), kotlin.math.abs(analysis.minCalibratedY))
        if (maxAbsY > profile.noiseFloor * 3.0f && maxAbsY < profile.sendThreshold) {
            val gestureType = if (analysis.maxCalibratedY > 0) "SEND" else "RECEIVE"
            return GestureEvent.LowConfidence(
                timestamp = now,
                gestureType = gestureType,
                confidence = maxAbsY / profile.sendThreshold
            )
        }
        return null
    }

    private fun directionAlignment(analysis: WindowAnalysis, profile: CalibrationProfile): Float {
        // Simple alignment: compare mean calibrated Y direction with expected direction
        val expectedY = profile.sendDirectionY
        val actualY = analysis.meanCalibratedY

        if (expectedY > 0 && actualY > 0) {
            return kotlin.math.min(1.0f, actualY / (profile.sendThreshold * 2.0f))
        } else if (expectedY < 0 && actualY < 0) {
            return kotlin.math.min(1.0f, -actualY / (profile.sendThreshold * 2.0f))
        }
        return 0f
    }

    private fun checkReturnToNeutral(analysis: WindowAnalysis): Boolean {
        // Check if the last few samples return near the baseline
        val lastSamples = analysis.samples.takeLast(3)
        if (lastSamples.isEmpty()) return false
        val lastMeanY = if (lastSamples.isNotEmpty()) lastSamples.fold(0f) { acc: Float, it: ProcessedSample -> acc + it.calibratedY } / lastSamples.size else 0f
        return kotlin.math.abs(lastMeanY) < profile.noiseFloor * 2.0f
    }
}
