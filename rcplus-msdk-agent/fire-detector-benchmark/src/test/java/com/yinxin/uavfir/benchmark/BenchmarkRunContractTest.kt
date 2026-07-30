package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

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
        val manifest = visibleBenchmark()

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
            visibleBenchmark().apply {
                getJSONArray("samples").getJSONObject(1).getJSONArray("expectedBoxes")
                    .getJSONObject(0).put("class", 0)
            },
            listOf("fire", "smoke"),
        )
    }

    @Test
    fun contract_acceptsBaselineBoundToVisible960ModelAndSamples() {
        val benchmark = visibleBenchmark()
        val benchmarkBytes = benchmark.toString().toByteArray()
        val baseline = visibleBaseline(benchmark, sha256(benchmarkBytes))

        BenchmarkRunContract.validateVisibleBaseline(
            baseline,
            benchmark,
            testManifest(),
            sha256(benchmarkBytes),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun contract_rejectsThermal640Baseline() {
        val benchmark = visibleBenchmark()
        val baseline = JSONObject(
            """{"model":{"name":"thermal-fire.pt","sha256":"${"b".repeat(64)}"},
              "inputSize":640,"confidenceThreshold":0.25,"iouThreshold":0.7,
              "samples":[{"id":"fire","detections":[]},{"id":"smoke","detections":[]}]}""",
        )

        BenchmarkRunContract.validateVisibleBaseline(baseline, benchmark, testManifest(), "c".repeat(64))
    }

    @Test(expected = IllegalStateException::class)
    fun contract_rejectsBaselineBoundToDifferentBenchmarkBytes() {
        val benchmark = visibleBenchmark()
        val baseline = visibleBaseline(benchmark, "d".repeat(64))

        BenchmarkRunContract.validateVisibleBaseline(baseline, benchmark, testManifest(), "e".repeat(64))
    }

    @Test(expected = IllegalStateException::class)
    fun contract_rejectsPerSampleImageLabelOrExpectedBoxSplicing() {
        val benchmark = visibleBenchmark()
        val benchmarkSha = sha256(benchmark.toString().toByteArray())
        val baseline = visibleBaseline(benchmark, benchmarkSha)
        baseline.getJSONArray("samples").getJSONObject(0).put("labelSha256", "f".repeat(64))

        BenchmarkRunContract.validateVisibleBaseline(baseline, benchmark, testManifest(), benchmarkSha)
    }

    @Test(expected = IllegalStateException::class)
    fun contract_rejectsWrongModalitySeedTagsOrOutOfRangeClasses() {
        val benchmark = visibleBenchmark().put("modality", "thermal")

        BenchmarkRunContract.validateVisibleDataset(benchmark, listOf("fire", "smoke"))
    }

    private fun visibleBenchmark(): JSONObject {
        val samples = JSONArray()
        repeat(400) { index ->
            val classIndex = if (index == 1) 1 else 0
            val tags = when (index) {
                0 -> listOf("fire", "small-target", "zoomed-roi", "night-dark")
                1 -> listOf("smoke")
                2 -> listOf("hard-negative-orange-red")
                else -> listOf("fire")
            }
            val boxes = if (index == 2) JSONArray() else JSONArray().put(
                JSONObject()
                    .put("class", classIndex)
                    .put("x", 0.5)
                    .put("y", 0.5)
                    .put("width", 0.1)
                    .put("height", 0.1),
            )
            samples.put(
                JSONObject()
                    .put("id", "sample-$index")
                    .put("tags", JSONArray(tags))
                    .put("image", "images/sample-$index.jpg")
                    .put("label", "labels/sample-$index.txt")
                    .put("imageSha256", "a".repeat(64))
                    .put("labelSha256", "b".repeat(64))
                    .put("expectedBoxes", boxes),
            )
        }
        return JSONObject()
            .put("schemaVersion", 2)
            .put("modality", "visible")
            .put("datasetId", "visible-validation-20260730")
            .put("seed", 20260730)
            .put("samples", samples)
    }

    private fun visibleBaseline(benchmark: JSONObject, benchmarkSha: String): JSONObject {
        val boundSamples = JSONArray()
        val samples = benchmark.getJSONArray("samples")
        for (index in 0 until samples.length()) {
            val sample = samples.getJSONObject(index)
            boundSamples.put(
                JSONObject()
                    .put("id", sample.getString("id"))
                    .put("tags", sample.getJSONArray("tags"))
                    .put("imageSha256", sample.getString("imageSha256"))
                    .put("labelSha256", sample.getString("labelSha256"))
                    .put("expectedBoxes", sample.getJSONArray("expectedBoxes"))
                    .put("detections", JSONArray()),
            )
        }
        return JSONObject()
            .put(
                "model",
                JSONObject()
                    .put("name", "visible-fire-wechat-best2-20260728.pt")
                    .put("sha256", "957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650"),
            )
            .put("modality", "visible")
            .put("datasetId", "visible-validation-20260730")
            .put("seed", 20260730)
            .put("benchmarkManifestSha256", benchmarkSha)
            .put("inputSize", 960)
            .put("confidenceThreshold", 0.25)
            .put("iouThreshold", 0.7)
            .put("samples", boundSamples)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
