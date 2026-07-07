package com.yinxin.uavfir.stream

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DjiMsdkStreamBinderHotspotSelectionTest {
    @Test
    fun selectThermalHotspotRegion_prefersFreshFrameHotspotOverPreviousSeed() {
        val freshHotspot = ThermalMeasureRegion(x = 0.62, y = 0.44, width = 0.07, height = 0.07)
        val previousSeed = ThermalMeasureRegion(x = 0.81, y = 0.535, width = 0.18, height = 0.18)

        val selected = selectThermalHotspotRegion(
            latestFrameHotspotRegion = freshHotspot,
            seedRegion = previousSeed,
        )

        assertEquals(freshHotspot, selected)
    }

    @Test
    fun selectThermalHotspotRegion_usesSeedOnlyWhenNoFreshFrameHotspotExists() {
        val previousSeed = ThermalMeasureRegion(x = 0.81, y = 0.535, width = 0.18, height = 0.18)

        val selected = selectThermalHotspotRegion(
            latestFrameHotspotRegion = null,
            seedRegion = previousSeed,
        )

        assertEquals(previousSeed, selected)
    }

    @Test
    fun selectThermalHotspotRegions_clustersNearbyRegionsBeforeMeasurement() {
        val regions = listOf(
            ThermalMeasureRegion(x = 0.60, y = 0.40, width = 0.06, height = 0.06),
            ThermalMeasureRegion(x = 0.62, y = 0.42, width = 0.06, height = 0.06),
            ThermalMeasureRegion(x = 0.10, y = 0.76, width = 0.06, height = 0.06),
        )

        val selected = selectThermalHotspotRegions(
            latestFrameHotspotRegions = regions,
            seedRegion = null,
        )

        assertEquals(
            listOf(
                ThermalMeasureRegion(x = 0.60, y = 0.40, width = 0.06, height = 0.06),
                ThermalMeasureRegion(x = 0.10, y = 0.76, width = 0.06, height = 0.06),
            ),
            selected,
        )
    }

    @Test
    fun selectThermalHotspotRegions_limitsFrameHotspotsToThreeCandidates() {
        val regions = listOf(
            ThermalMeasureRegion(x = 0.10, y = 0.10, width = 0.06, height = 0.06),
            ThermalMeasureRegion(x = 0.30, y = 0.10, width = 0.06, height = 0.06),
            ThermalMeasureRegion(x = 0.50, y = 0.10, width = 0.06, height = 0.06),
            ThermalMeasureRegion(x = 0.70, y = 0.10, width = 0.06, height = 0.06),
        )

        val selected = selectThermalHotspotRegions(
            latestFrameHotspotRegions = regions,
            seedRegion = null,
        )

        assertEquals(regions.take(3), selected)
    }

    @Test
    fun measureThermalHotspotCandidates_skipsMeasurementWhenNoCandidateOrSeedExists() = runTest {
        var measureCalls = 0

        val result = measureThermalHotspotCandidates(
            requestedAtMs = 1_780_059_017_562L,
            latestFrameHotspotRegions = emptyList(),
            seedRegion = null,
            measureTemperatureC = {
                measureCalls += 1
                153.0
            },
            latestSnapshotPath = { "/tmp/thermal.jpg" },
        )

        assertNull(result)
        assertEquals(0, measureCalls)
    }

    @Test
    fun measureThermalHotspotCandidates_fastConfirmsStrongHeat() = runTest {
        val measuredRegions = mutableListOf<ThermalMeasureRegion>()
        val first = ThermalMeasureRegion(x = 0.10, y = 0.10, width = 0.06, height = 0.06)
        val second = ThermalMeasureRegion(x = 0.30, y = 0.10, width = 0.06, height = 0.06)

        val result = measureThermalHotspotCandidates(
            requestedAtMs = 1_780_059_017_562L,
            latestFrameHotspotRegions = listOf(first, second),
            seedRegion = null,
            measureTemperatureC = { region ->
                measuredRegions += region
                if (region == first) 120.0 else 90.0
            },
            latestSnapshotPath = { minTimestampMs ->
                assertEquals(1_780_059_016_562L, minTimestampMs)
                "/tmp/thermal.jpg"
            },
        )

        assertEquals(first, result?.region)
        assertEquals(120.0, result?.temperatureC ?: -1.0, 1e-6)
        assertEquals(listOf(first), measuredRegions)
    }

    @Test
    fun shouldFastConfirmThermalHotspot_onlyForStrongHeat() {
        assertEquals(false, shouldFastConfirmThermalHotspot(119.9))
        assertEquals(true, shouldFastConfirmThermalHotspot(120.0))
    }
}
