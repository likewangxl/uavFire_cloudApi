package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.BoundStreamState
import com.yinxin.uavfir.stream.StreamProvider
import com.yinxin.uavfir.stream.StreamStartResult
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThermalDwellConfirmerTest {
    @Test
    fun confirm_passes_when_min_hits_reached() = runTest {
        val provider = SequenceHotspotProvider(
            listOf(
                HotspotResult(46.0, REGION_2),
                HotspotResult(47.0, REGION_3),
                HotspotResult(44.0, REGION_4),
            ),
        )
        val hold = RecordingMissionHoldControl()
        val confirmer = confirmer(provider, hold)

        val result = confirmer.confirm(
            droneSn = "DRONE-001",
            thresholdC = 45.0,
            firstSample = DwellSample(45.5, REGION_1, 100L),
            seedRegion = REGION_1,
        )

        assertTrue(result.confirmed)
        assertFalse(result.degraded)
        assertEquals(4, result.samples.size)
        assertEquals(1, hold.holdCalls)
        assertEquals(1, hold.resumeCalls)
    }

    @Test
    fun confirm_rejects_when_hits_insufficient() = runTest {
        val provider = SequenceHotspotProvider(
            listOf(
                HotspotResult(41.0, REGION_2),
                HotspotResult(42.0, REGION_3),
                HotspotResult(43.0, REGION_4),
            ),
        )
        val hold = RecordingMissionHoldControl()
        val confirmer = confirmer(provider, hold)

        val result = confirmer.confirm(
            droneSn = "DRONE-001",
            thresholdC = 45.0,
            firstSample = DwellSample(46.0, REGION_1, 100L),
            seedRegion = REGION_1,
        )

        assertFalse(result.confirmed)
        assertFalse(result.degraded)
        assertEquals(4, result.samples.size)
        assertEquals(1, hold.resumeCalls)
    }

    @Test
    fun confirm_fail_open_on_consecutive_measure_failures() = runTest {
        val provider = SequenceHotspotProvider(
            listOf(
                HotspotResult.failure(),
                HotspotResult.failure(),
                HotspotResult(48.0, REGION_3),
            ),
        )
        val hold = RecordingMissionHoldControl()
        val confirmer = confirmer(provider, hold)

        val result = confirmer.confirm(
            droneSn = "DRONE-001",
            thresholdC = 45.0,
            firstSample = DwellSample(46.0, REGION_1, 100L),
            seedRegion = REGION_1,
        )

        assertFalse(result.confirmed)
        assertTrue(result.degraded)
        assertEquals(1, result.samples.size)
        assertEquals(2, provider.measureHotspotCalls)
        assertEquals(1, hold.resumeCalls)
    }

    @Test
    fun confirm_disabled_bypasses_hold() = runTest {
        val provider = SequenceHotspotProvider(listOf(HotspotResult(80.0, REGION_2)))
        val hold = RecordingMissionHoldControl()
        val confirmer = confirmer(provider, hold, enabled = false)
        val firstSample = DwellSample(46.0, REGION_1, 100L)

        val result = confirmer.confirm(
            droneSn = "DRONE-001",
            thresholdC = 45.0,
            firstSample = firstSample,
            seedRegion = REGION_1,
        )

        assertTrue(result.confirmed)
        assertFalse(result.degraded)
        assertEquals(listOf(firstSample), result.samples)
        assertEquals(firstSample, result.bestSample)
        assertEquals(0, provider.measureHotspotCalls)
        assertEquals(0, hold.holdCalls)
        assertEquals(0, hold.resumeCalls)
    }

    @Test
    fun resume_called_even_when_measure_throws() = runTest {
        val provider = ThrowingHotspotProvider()
        val hold = RecordingMissionHoldControl()
        val confirmer = confirmer(provider, hold)

        val result = confirmer.confirm(
            droneSn = "DRONE-001",
            thresholdC = 45.0,
            firstSample = DwellSample(46.0, REGION_1, 100L),
            seedRegion = REGION_1,
        )

        assertTrue(result.degraded)
        assertEquals(1, hold.resumeCalls)
    }

    @Test
    fun best_sample_is_max_temperature() = runTest {
        val provider = SequenceHotspotProvider(
            listOf(
                HotspotResult(49.0, REGION_2),
                HotspotResult(55.0, REGION_3),
                HotspotResult(52.0, REGION_4),
            ),
        )
        val hold = RecordingMissionHoldControl()
        val confirmer = confirmer(provider, hold)

        val result = confirmer.confirm(
            droneSn = "DRONE-001",
            thresholdC = 45.0,
            firstSample = DwellSample(46.0, REGION_1, 100L),
            seedRegion = REGION_1,
        )

        assertEquals(55.0, result.bestSample?.temperatureC ?: -1.0, 1e-6)
        assertEquals(REGION_3, result.bestSample?.region)
        assertEquals(46.0, result.minC ?: -1.0, 1e-6)
        assertEquals(55.0, result.maxC ?: -1.0, 1e-6)
        assertEquals(9.0, result.spreadC ?: -1.0, 1e-6)
    }

    private fun confirmer(
        provider: StreamProvider,
        hold: MissionHoldControl,
        enabled: Boolean = true,
    ): ThermalDwellConfirmer = ThermalDwellConfirmer(
        sessionManager = DualStreamSessionManager(provider),
        missionHold = hold,
        enabled = enabled,
        stabilizeMs = 0L,
        sampleIntervalMs = 0L,
        clockMs = { 100L },
    )

    private class RecordingMissionHoldControl : MissionHoldControl {
        var holdCalls = 0
        var resumeCalls = 0

        override suspend fun holdForConfirmation(): Boolean {
            holdCalls += 1
            return true
        }

        override suspend fun resumeAfterConfirmation() {
            resumeCalls += 1
        }
    }

    private data class HotspotResult(
        val temperatureC: Double?,
        val region: ThermalMeasureRegion?,
        val throws: Boolean = false,
    ) {
        companion object {
            fun failure(): HotspotResult = HotspotResult(null, null, throws = true)
        }
    }

    private class SequenceHotspotProvider(
        private val results: List<HotspotResult>,
    ) : StreamProvider {
        var measureHotspotCalls = 0

        override suspend fun start(droneSn: String): StreamStartResult = applied()

        override suspend fun focusVisible(droneSn: String): StreamStartResult = applied()

        override suspend fun focusThermal(droneSn: String): StreamStartResult = applied()

        override suspend fun measureThermalHotspot(
            droneSn: String,
            seedRegion: ThermalMeasureRegion?,
        ): StreamStartResult {
            val result = results.getOrElse(measureHotspotCalls) { results.last() }
            measureHotspotCalls += 1
            if (result.throws) {
                error("thermal measure failed")
            }
            return applied(result.temperatureC, result.region)
        }

        override suspend fun stop() = Unit
    }

    private class ThrowingHotspotProvider : StreamProvider {
        override suspend fun start(droneSn: String): StreamStartResult = applied()

        override suspend fun focusVisible(droneSn: String): StreamStartResult = applied()

        override suspend fun focusThermal(droneSn: String): StreamStartResult = applied()

        override suspend fun measureThermalHotspot(
            droneSn: String,
            seedRegion: ThermalMeasureRegion?,
        ): StreamStartResult {
            error("thermal measure failed")
        }

        override suspend fun stop() = Unit
    }

    private companion object {
        val REGION_1 = ThermalMeasureRegion(x = 0.10, y = 0.10, width = 0.10, height = 0.10)
        val REGION_2 = ThermalMeasureRegion(x = 0.20, y = 0.20, width = 0.10, height = 0.10)
        val REGION_3 = ThermalMeasureRegion(x = 0.30, y = 0.30, width = 0.10, height = 0.10)
        val REGION_4 = ThermalMeasureRegion(x = 0.40, y = 0.40, width = 0.10, height = 0.10)

        fun applied(
            temperatureC: Double? = null,
            region: ThermalMeasureRegion? = null,
        ): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.BOUND,
            playbackStatus = "shared-side-by-side-preview",
            thermalCenterTemperatureC = temperatureC,
            thermalMeasureRegion = region,
        )
    }
}
