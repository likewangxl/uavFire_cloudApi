package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class ProvisionalNcnnSelectionPolicyTest {
    @Test
    fun humanOverride_onlyUnblocksTask3Development() {
        val json = java.io.File("src/main/assets/provisional-ncnn-selection.json").readText()
        val selection = ProvisionalNcnnSelectionPolicy.parse(json)

        assertEquals("PROVISIONAL_NCNN_SELECTED", selection.status)
        assertEquals(true, selection.task3DevelopmentBuildAllowed)
    }

    @Test(expected = IllegalStateException::class)
    fun provisionalEvidence_cannotClaimVisible960GatePassed() {
        val json = java.io.File("src/main/assets/provisional-ncnn-selection.json")
            .readText()
            .replace("\"visible960GatePassed\": false", "\"visible960GatePassed\": true")

        ProvisionalNcnnSelectionPolicy.parse(json)
    }
}
