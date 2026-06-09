package com.yinxin.uavfir.stream

import org.junit.Assert.assertEquals
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
    fun shouldFastConfirmThermalHotspot_onlyForStrongHeat() {
        assertEquals(false, shouldFastConfirmThermalHotspot(119.9))
        assertEquals(true, shouldFastConfirmThermalHotspot(120.0))
    }
}
