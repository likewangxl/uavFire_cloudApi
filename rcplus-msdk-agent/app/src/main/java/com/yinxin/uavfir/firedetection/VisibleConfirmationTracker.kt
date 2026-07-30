package com.yinxin.uavfir.firedetection

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

class VisibleConfirmationTracker(
    private val policy: VisibleConfirmationPolicy,
) {
    private var pending: Candidate? = null
    private var greatestFrameTimestampMillis: Long? = null
    private var greatestObservedAtMillis: Long? = null

    fun observe(input: VisibleConfirmationInput): VisibleConfirmationOutcome {
        val timestamp = input.result.frameCapturedAtMillis
        val ageMillis = input.observedAtMillis - timestamp
        if (timestamp != input.frame.capturedAtMillis ||
            ageMillis < 0L ||
            ageMillis > policy.maxFrameAgeMs ||
            !input.health.allHealthy
        ) {
            pending = null
            return VisibleConfirmationOutcome(reset = true)
        }

        val greatestTimestamp = greatestFrameTimestampMillis
        val greatestObservation = greatestObservedAtMillis
        if ((greatestTimestamp != null && timestamp <= greatestTimestamp) ||
            (greatestObservation != null && input.observedAtMillis <= greatestObservation)
        ) {
            pending = null
            return VisibleConfirmationOutcome(reset = true)
        }

        val candidate = selectBestCandidate(input)
        if (candidate == null) {
            pending = null
            return VisibleConfirmationOutcome(reset = true)
        }
        greatestFrameTimestampMillis = timestamp
        greatestObservedAtMillis = input.observedAtMillis

        val previous = pending
        if (previous == null) {
            pending = candidate
            return VisibleConfirmationOutcome()
        }

        if (input.observedAtMillis - previous.frameTimestampMillis > policy.maxFrameAgeMs ||
            previous.kind != candidate.kind ||
            centerDistance(previous.roi, candidate.roi) > policy.maxCenterDistance
        ) {
            pending = candidate
            return VisibleConfirmationOutcome(reset = true)
        }

        pending = null
        return VisibleConfirmationOutcome(
            confirmation = VisibleConfirmation(
                kind = candidate.kind,
                confidence = minOf(previous.confidence, candidate.confidence),
                roi = candidate.roi,
                firstFrameTimestampMillis = previous.frameTimestampMillis,
                secondFrameTimestampMillis = candidate.frameTimestampMillis,
                policyVersion = policy.policyVersion,
            ),
        )
    }

    fun reset() {
        pending = null
    }

    private fun selectBestCandidate(input: VisibleConfirmationInput): Candidate? =
        input.result.detections
            .mapNotNull { detection ->
                val kind = detection.kindOrNull() ?: return@mapNotNull null
                val confidenceThreshold = when (kind) {
                    DetectionKind.FIRE -> policy.fireConfidence
                    DetectionKind.SMOKE -> policy.smokeConfidence
                }
                if (detection.confidence < confidenceThreshold) return@mapNotNull null
                val roi = NormalizedRoi.from(detection)
                if (kind == DetectionKind.FIRE && !passesFireColorGate(input.frame, roi)) {
                    return@mapNotNull null
                }
                Candidate(
                    kind = kind,
                    confidence = detection.confidence,
                    roi = roi,
                    frameTimestampMillis = input.result.frameCapturedAtMillis,
                )
            }
            .maxWithOrNull(compareBy<Candidate> { it.confidence }.thenByDescending { it.kind.ordinal })

    private fun passesFireColorGate(frame: VisibleRgbaFrame, roi: NormalizedRoi): Boolean {
        return try {
            val left = floor(roi.left * frame.width).toInt().coerceIn(0, frame.width - 1)
            val top = floor(roi.top * frame.height).toInt().coerceIn(0, frame.height - 1)
            val rightExclusive = ceil(roi.right * frame.width).toInt().coerceIn(left + 1, frame.width)
            val bottomExclusive = ceil(roi.bottom * frame.height).toInt().coerceIn(top + 1, frame.height)
            val pixels = frame.pixels
            var matchingPixels = 0
            for (y in top until bottomExclusive) {
                for (x in left until rightExclusive) {
                    val offset = (y * frame.width + x) * RGBA_CHANNELS
                    val red = pixels[offset].toInt() and BYTE_MASK
                    val green = pixels[offset + 1].toInt() and BYTE_MASK
                    val blue = pixels[offset + 2].toInt() and BYTE_MASK
                    if (red >= policy.fireColorRedMin &&
                        green >= policy.fireColorGreenMin &&
                        blue <= policy.fireColorBlueMax &&
                        red >= green
                    ) {
                        matchingPixels += 1
                        if (matchingPixels >= policy.fireColorMinPixels) return true
                    }
                }
            }
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun centerDistance(first: NormalizedRoi, second: NormalizedRoi): Double =
        hypot(first.centerX - second.centerX, first.centerY - second.centerY)

    private fun VisibleDetection.kindOrNull(): DetectionKind? = when (className.lowercase()) {
        "fire" -> DetectionKind.FIRE
        "smoke" -> DetectionKind.SMOKE
        else -> null
    }

    private data class Candidate(
        val kind: DetectionKind,
        val confidence: Float,
        val roi: NormalizedRoi,
        val frameTimestampMillis: Long,
    )

    private companion object {
        const val RGBA_CHANNELS = 4
        const val BYTE_MASK = 0xff
    }
}
