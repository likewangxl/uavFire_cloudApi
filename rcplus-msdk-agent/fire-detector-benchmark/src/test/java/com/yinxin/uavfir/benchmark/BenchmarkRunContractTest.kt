package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

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

    @Test
    fun contract_acceptsSchemaV2WhenEvaluatedSamplesContainFireAndSmoke() {
        val manifest = JSONObject(
            """
            {"schemaVersion":2,"samples":[
              {"id":"fire","expectedBoxes":[{"class":0}]},
              {"id":"smoke","expectedBoxes":[{"class":1}]}
            ]}
            """.trimIndent(),
        )

        BenchmarkRunContract.validateVisibleDataset(manifest, listOf("fire", "smoke"))
    }

    @Test(expected = IllegalStateException::class)
    fun contract_rejectsLegacyBenchmarkManifest() {
        BenchmarkRunContract.validateVisibleDataset(
            JSONObject("""{"samples":[{"id":"fire","expectedBoxes":[{"class":0}]}]}"""),
            listOf("fire", "smoke"),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun contract_rejectsEvaluatedSamplesWithoutSmokeLabels() {
        BenchmarkRunContract.validateVisibleDataset(
            JSONObject("""{"schemaVersion":2,"samples":[{"id":"fire","expectedBoxes":[{"class":0}]}]}"""),
            listOf("fire", "smoke"),
        )
    }

    @Test
    fun contract_acceptsBaselineBoundToVisible960ModelAndSamples() {
        val benchmark = JSONObject(
            """{"schemaVersion":2,"samples":[
              {"id":"fire","expectedBoxes":[{"class":0}]},
              {"id":"smoke","expectedBoxes":[{"class":1}]}
            ]}""",
        )
        val baseline = JSONObject(
            """{"model":{"name":"visible-fire-test.pt","sha256":"${"a".repeat(64)}"},
              "inputSize":960,"confidenceThreshold":0.25,"iouThreshold":0.7,
              "samples":[{"id":"smoke","detections":[]},{"id":"fire","detections":[]}]}""",
        )

        BenchmarkRunContract.validateVisibleBaseline(baseline, benchmark, testManifest())
    }

    @Test(expected = IllegalStateException::class)
    fun contract_rejectsThermal640Baseline() {
        val benchmark = JSONObject("""{"schemaVersion":2,"samples":[{"id":"fire"},{"id":"smoke"}]}""")
        val baseline = JSONObject(
            """{"model":{"name":"thermal-fire.pt","sha256":"${"b".repeat(64)}"},
              "inputSize":640,"confidenceThreshold":0.25,"iouThreshold":0.7,
              "samples":[{"id":"fire","detections":[]},{"id":"smoke","detections":[]}]}""",
        )

        BenchmarkRunContract.validateVisibleBaseline(baseline, benchmark, testManifest())
    }
}
