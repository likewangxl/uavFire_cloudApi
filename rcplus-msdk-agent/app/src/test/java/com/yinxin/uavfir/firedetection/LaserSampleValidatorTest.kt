package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.api.LaserRangefinderResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaserSampleValidatorTest {
    @Test
    fun acceptsExact300msAndExact15mBoundariesAndReturnsMedians() {
        val binding = binding()
        val baseLat = 34.0
        val deltaLat15m = Math.toDegrees(15.0 / LaserSampleValidator.EARTH_RADIUS_M)
        val samples = listOf(
            sample(binding, 1_000, baseLat, 109.0, 10.0, 60.0),
            sample(binding, 1_300, baseLat + deltaLat15m / 2, 109.0, 12.0, 62.0),
            sample(binding, 1_600, baseLat + deltaLat15m, 109.0, 11.0, 61.0),
        )

        val result = LaserSampleValidator().validate(binding, samples)

        assertTrue(result is LaserValidationResult.Valid)
        result as LaserValidationResult.Valid
        assertEquals(baseLat + deltaLat15m / 2, result.latitude, 1e-9)
        assertEquals(109.0, result.longitude, 1e-9)
        assertEquals(11.0, result.altitude, 1e-9)
        assertEquals(61.0, result.rangeM, 1e-9)
        assertEquals(5.0, result.errorRadiusM, 0.0)
        assertEquals(samples, result.rawSamples)
    }

    @Test
    fun rejectsWrongOperationBinding() {
        val binding = binding()
        val invalid = listOf(
            sample(binding, 1_000, 91.0, 109.0, 10.0, 60.0),
            sample(binding.copy(operationGeneration = 8), 1_299, 34.0, 181.0, 10.0, -1.0),
            sample(binding, 1_600, 34.0, 109.0, Double.NaN, 60.0, state = "INVALID"),
        )

        val result = LaserSampleValidator().validate(binding, invalid)

        assertEquals(LaserValidationFailure.BINDING_MISMATCH, (result as LaserValidationResult.Invalid).reason)
    }

    @Test
    fun rejectsNonNormalInvalidDomainsAndTooCloseSamples() {
        val binding = binding()
        val result = LaserSampleValidator().validate(
            binding,
            listOf(
                sample(binding, 1_000, 91.0, 109.0, 10.0, 60.0),
                sample(binding, 1_299, 34.0, 181.0, 10.0, -1.0),
                sample(binding, 1_600, 34.0, 109.0, Double.NaN, 60.0, state = "INVALID"),
            ),
        )
        assertEquals(
            LaserValidationFailure.INVALID_SAMPLE,
            (result as LaserValidationResult.Invalid).reason,
        )
    }

    @Test
    fun rejectsScatterGreaterThan15m() {
        val binding = binding()
        val deltaLat = Math.toDegrees(15.01 / LaserSampleValidator.EARTH_RADIUS_M)
        val result = LaserSampleValidator().validate(
            binding,
            listOf(
                sample(binding, 1_000, 34.0, 109.0, 10.0, 60.0),
                sample(binding, 1_300, 34.0 + deltaLat / 2, 109.0, 10.0, 60.0),
                sample(binding, 1_600, 34.0 + deltaLat, 109.0, 10.0, 60.0),
            ),
        )
        assertEquals(LaserValidationFailure.SCATTER_EXCEEDED, (result as LaserValidationResult.Invalid).reason)
    }

    private fun binding() = LaserOperationBinding(
        sessionId = "session-1",
        eventId = "event-1",
        targetRoi = NormalizedRoi(.4f, .4f, .6f, .6f),
        sourceGeneration = 2,
        operationGeneration = 7,
        windowStartedAtMonotonicMs = 900,
        windowEndsAtMonotonicMs = 2_000,
    )

    private fun sample(
        binding: LaserOperationBinding,
        at: Long,
        lat: Double,
        lng: Double,
        alt: Double,
        range: Double,
        state: String = "NORMAL",
    ) = BoundLaserSample(
        binding = binding,
        hardwareOperationGeneration = binding.operationGeneration,
        observationSequence = at,
        sampledAtMonotonicMs = at,
        measurement = LaserRangefinderResult(lat, lng, alt, range, state, .5, .5),
    )
}
