package com.yinxin.uavfir.wayline

import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.round

data class PatrolZoomRange(val continuous: Boolean, val gears: List<Double>) {
    fun target(heightM: Double): Double? {
        if (!heightM.isFinite() || heightM <= 0) return null
        val valid = gears.filter { it.isFinite() && it >= 1.0 }.sorted()
        if (valid.isEmpty()) return null
        val requested = round(heightM / 30.0 * 2.0 * 10.0) / 10.0
        return if (continuous) requested.coerceIn(valid.first(), valid.last())
        else valid.minByOrNull { abs(it - requested) }
    }
}

interface PatrolZoomPort {
    /** Relative-to-takeoff height: flat-ground assumption, NOT terrain-following AGL. */
    suspend fun relativeHeightM(): Double?
    suspend fun pitchDegrees(): Double?
    suspend fun range(): PatrolZoomRange
    suspend fun currentRatio(): Double?
    /** False while thermal/another unsupported source owns the camera. */
    suspend fun prepareVisibleZoom(): Boolean
    suspend fun setRatio(ratio: Double)
}

/** Called only while the aircraft reports EXECUTING; cancellation relinquishes camera control. */
class PatrolZoomController(private val port: PatrolZoomPort) {
    private var lastTarget: Double? = null

    fun reset() { lastTarget = null }

    suspend fun tick(): String {
        val height = port.relativeHeightM()
        if (height == null || !height.isFinite() || height <= 0) return "height-unavailable"
        val pitch = port.pitchDegrees()
        if (pitch == null || !pitch.isFinite() || abs(pitch + 45.0) > 5.0) return "waiting-for-pitch-minus45"
        if (!port.prepareVisibleZoom()) return "camera-owned-by-other-source"
        val target = port.range().target(height) ?: return "zoom-range-unavailable"
        val actual = port.currentRatio()
        if (actual != null && actual.isFinite() && abs(actual - target) < 0.05) {
            lastTarget = target
            return "stable"
        }
        // A 0.1x height fluctuation does not constantly move the lens. Still repair external resets.
        val previous = lastTarget
        if (previous != null && abs(previous - target) < 0.19 && actual != null &&
            actual.isFinite() && abs(actual - previous) < 0.05) return "stable"
        port.setRatio(target)
        repeat(5) {
            delay(200)
            val observed = port.currentRatio()
            if (observed != null && observed.isFinite() && abs(observed - target) < 0.05) {
                lastTarget = target
                return "applied heightRelativeToTakeoffM=$height target=$target actual=$observed"
            }
        }
        error("zoom-readback-mismatch target=$target")
    }
}
