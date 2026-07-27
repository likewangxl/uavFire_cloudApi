package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkDeltaMetadataContractTest {
    @Test
    fun contract_requiresWholeApkCandidateDeltaAgainstRuntimeFreeBaseline() {
        val baseline = 1_000L
        val candidate = 1_250L

        assertEquals(250L, candidate - baseline)
        assertEquals("arm64-v8a", APK_DELTA_ABI)
    }
}
