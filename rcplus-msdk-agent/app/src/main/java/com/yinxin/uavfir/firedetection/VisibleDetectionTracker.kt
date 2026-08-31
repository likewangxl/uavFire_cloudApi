package com.yinxin.uavfir.firedetection

import kotlin.math.hypot

class VisibleDetectionTracker(
    private val confirmationWindowMs: Long = 6_000L,
    private val reportDebounceMs: Long = 10_000L,
    private val maxCenterShift: Double = 0.30,
) {
    private var previous: TimedDetection? = null
    private var lastReportedAtMs: Long = Long.MIN_VALUE

    @Synchronized
    fun accept(detections: List<VisibleDetection>, sourceTs: Long): VisibleDetection? {
        val best = detections.maxByOrNull { it.confidence }
        if (best == null) {
            previous = null
            return null
        }
        val prior = previous
        previous = TimedDetection(best, sourceTs)
        if (prior == null || sourceTs < prior.sourceTs || sourceTs - prior.sourceTs > confirmationWindowMs) {
            return null
        }
        if (prior.detection.classId != best.classId) return null
        val centerShift = hypot(
            prior.detection.roi.centerX - best.roi.centerX,
            prior.detection.roi.centerY - best.roi.centerY,
        )
        if (centerShift > maxCenterShift) return null
        if (lastReportedAtMs != Long.MIN_VALUE && sourceTs - lastReportedAtMs < reportDebounceMs) return null
        return best
    }

    @Synchronized
    fun markReported(sourceTs: Long) {
        if (lastReportedAtMs == Long.MIN_VALUE || sourceTs > lastReportedAtMs) {
            lastReportedAtMs = sourceTs
        }
    }

    @Synchronized
    fun reset() {
        previous = null
        lastReportedAtMs = Long.MIN_VALUE
    }

    private data class TimedDetection(val detection: VisibleDetection, val sourceTs: Long)
}
