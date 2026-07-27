package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkRunContractTest {
    @Test
    fun contract_preservesFixedWarmupCorrectnessAndStabilityDurations() {
        BenchmarkRunContract.validateSampleCount(400)

        assertEquals(30, BenchmarkRunContract.WARM_UP_FRAMES)
        assertEquals(30 * 60 * 1_000L, BenchmarkRunContract.RUN_DURATION_MILLIS)
        assertEquals(5 * 60 * 1_000L, BenchmarkRunContract.WINDOW_MILLIS)
    }

    @Test(expected = IllegalStateException::class)
    fun contract_rejectsAnyCorrectnessSetOtherThanFourHundredImages() {
        BenchmarkRunContract.validateSampleCount(399)
    }
}
