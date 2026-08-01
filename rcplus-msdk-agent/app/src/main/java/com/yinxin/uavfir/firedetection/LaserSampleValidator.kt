package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.api.LaserRangefinderResult
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LaserOperationBinding(
    val sessionId: String,
    val eventId: String,
    val targetRoi: NormalizedRoi,
    val sourceGeneration: Long,
    val operationGeneration: Long,
    val windowStartedAtMonotonicMs: Long,
    val windowEndsAtMonotonicMs: Long,
) {
    init {
        require(sessionId.isNotBlank() && eventId.isNotBlank())
        require(sourceGeneration > 0 && operationGeneration > 0)
        require(windowStartedAtMonotonicMs >= 0)
        require(windowEndsAtMonotonicMs >= windowStartedAtMonotonicMs)
    }
}

data class BoundLaserSample(
    val binding: LaserOperationBinding,
    val hardwareOperationGeneration: Long,
    val observationSequence: Long,
    val sampledAtMonotonicMs: Long,
    val measurement: LaserRangefinderResult,
) {
    init {
        require(hardwareOperationGeneration > 0 && observationSequence > 0)
        require(sampledAtMonotonicMs >= 0)
    }
}

data class LaserHardwareOperationToken(
    val binding: LaserOperationBinding,
    val hardwareOperationGeneration: Long,
    val observationCursorAtEnable: Long,
    val enabledAtMonotonicMs: Long,
) {
    init {
        require(hardwareOperationGeneration == binding.operationGeneration)
        require(observationCursorAtEnable >= 0)
        require(enabledAtMonotonicMs >= binding.windowStartedAtMonotonicMs)
    }
}

sealed interface LaserHardwareAwaitResult {
    data class Observed(val sample: BoundLaserSample) : LaserHardwareAwaitResult
    data object Timeout : LaserHardwareAwaitResult
    data object Overflow : LaserHardwareAwaitResult
}

interface BoundLaserObservationClient {
    suspend fun beginOperation(binding: LaserOperationBinding): LaserHardwareOperationToken
    suspend fun awaitNext(
        token: LaserHardwareOperationToken,
        afterObservationSequence: Long,
        timeoutMs: Long,
    ): LaserHardwareAwaitResult
    suspend fun endOperation(token: LaserHardwareOperationToken)
}

fun interface VisibleSourceGenerationGuard {
    fun currentVisibleGeneration(): Long?
}

enum class LaserValidationFailure {
    SAMPLE_COUNT,
    BINDING_MISMATCH,
    INVALID_SAMPLE,
    DUPLICATE_OBSERVATION,
    SAMPLE_INTERVAL,
    SCATTER_EXCEEDED,
}

sealed interface LaserValidationResult {
    data class Valid(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val rangeM: Double,
        val errorRadiusM: Double,
        val rawSamples: List<BoundLaserSample>,
    ) : LaserValidationResult

    data class Invalid(val reason: LaserValidationFailure) : LaserValidationResult
}

class LaserSampleValidator {
    fun isAcceptableCandidate(
        expected: LaserOperationBinding,
        sample: BoundLaserSample,
    ): Boolean = sample.binding == expected && sample.isValid(expected)

    fun validate(
        expected: LaserOperationBinding,
        samples: List<BoundLaserSample>,
    ): LaserValidationResult {
        if (samples.size != REQUIRED_SAMPLE_COUNT) {
            return LaserValidationResult.Invalid(LaserValidationFailure.SAMPLE_COUNT)
        }
        if (samples.any { it.binding != expected }) {
            return LaserValidationResult.Invalid(LaserValidationFailure.BINDING_MISMATCH)
        }
        if (samples.any { it.hardwareOperationGeneration != expected.operationGeneration }) {
            return LaserValidationResult.Invalid(LaserValidationFailure.BINDING_MISMATCH)
        }
        if (samples.map { it.observationSequence }.distinct().size != samples.size) {
            return LaserValidationResult.Invalid(LaserValidationFailure.DUPLICATE_OBSERVATION)
        }
        if (samples.any { !it.isValid(expected) }) {
            return LaserValidationResult.Invalid(LaserValidationFailure.INVALID_SAMPLE)
        }
        val ordered = samples.sortedBy { it.sampledAtMonotonicMs }
        if (ordered.zipWithNext().any { (a, b) ->
                b.sampledAtMonotonicMs - a.sampledAtMonotonicMs < MIN_SAMPLE_INTERVAL_MS
            }
        ) {
            return LaserValidationResult.Invalid(LaserValidationFailure.SAMPLE_INTERVAL)
        }
        for (first in ordered.indices) {
            for (second in first + 1 until ordered.size) {
                if (haversineMeters(ordered[first], ordered[second]) >
                    MAX_SCATTER_M + DISTANCE_EPSILON_M
                ) {
                    return LaserValidationResult.Invalid(LaserValidationFailure.SCATTER_EXCEEDED)
                }
            }
        }
        return LaserValidationResult.Valid(
            latitude = ordered.map { it.measurement.latitude!! }.median(),
            longitude = ordered.map { it.measurement.longitude!! }.median(),
            altitude = ordered.map { it.measurement.altitude!! }.median(),
            rangeM = ordered.map { it.measurement.distanceM!! }.median(),
            errorRadiusM = DEFAULT_ERROR_RADIUS_M,
            rawSamples = ordered.toList(),
        )
    }

    private fun BoundLaserSample.isValid(expected: LaserOperationBinding): Boolean {
        val value = measurement
        val latitude = value.latitude
        val longitude = value.longitude
        val altitude = value.altitude
        val range = value.distanceM
        val targetX = value.targetX
        val targetY = value.targetY
        return sampledAtMonotonicMs in
            expected.windowStartedAtMonotonicMs..expected.windowEndsAtMonotonicMs &&
            hardwareOperationGeneration == expected.operationGeneration &&
            value.state.equals("NORMAL", ignoreCase = true) &&
            latitude != null && latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude != null && longitude.isFinite() && longitude in -180.0..180.0 &&
            altitude != null && altitude.isFinite() && altitude in MIN_ALTITUDE_M..MAX_ALTITUDE_M &&
            range != null && range.isFinite() && range > 0.0 && range <= MAX_RANGE_M &&
            targetX != null && targetX.isFinite() && targetX in 0.0..1.0 &&
            targetY != null && targetY.isFinite() && targetY in 0.0..1.0 &&
            expected.targetRoi.contains(targetX, targetY)
    }

    /**
     * Great-circle distance on a spherical WGS84 mean Earth radius. This is
     * stable for the sub-15 m validation window and avoids longitude scaling
     * errors near the poles.
     */
    private fun haversineMeters(a: BoundLaserSample, b: BoundLaserSample): Double {
        val lat1 = Math.toRadians(a.measurement.latitude!!)
        val lat2 = Math.toRadians(b.measurement.latitude!!)
        val deltaLat = lat2 - lat1
        val deltaLng = Math.toRadians(b.measurement.longitude!! - a.measurement.longitude!!)
        val haversine = sin(deltaLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(deltaLng / 2).let { it * it }
        return 2.0 * EARTH_RADIUS_M * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    companion object {
        const val REQUIRED_SAMPLE_COUNT = 3
        const val MIN_SAMPLE_INTERVAL_MS = 300L
        const val MAX_SCATTER_M = 15.0
        const val DEFAULT_ERROR_RADIUS_M = 5.0
        const val EARTH_RADIUS_M = 6_371_008.8
        const val MIN_ALTITUDE_M = -1_000.0
        const val MAX_ALTITUDE_M = 20_000.0
        const val MAX_RANGE_M = 100_000.0
        private const val DISTANCE_EPSILON_M = 1e-6
    }
}

private fun List<Double>.median(): Double = sorted()[size / 2]
