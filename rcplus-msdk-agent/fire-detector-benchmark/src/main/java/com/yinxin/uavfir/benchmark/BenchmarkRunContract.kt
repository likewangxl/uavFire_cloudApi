package com.yinxin.uavfir.benchmark

import org.json.JSONObject
import kotlin.math.abs

internal object BenchmarkRunContract {
    const val CORRECTNESS_SAMPLE_COUNT = 400
    const val WARM_UP_FRAMES = 30
    const val RUN_DURATION_MILLIS = 30 * 60 * 1_000L
    const val WINDOW_MILLIS = 5 * 60 * 1_000L
    const val BENCHMARK_SEED = 20260730
    const val VISIBLE_DATASET_ID = "visible-validation-20260730"
    private val REQUIRED_TAGS = setOf(
        "fire",
        "smoke",
        "hard-negative-orange-red",
        "night-dark",
        "small-target",
        "zoomed-roi",
    )
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun validateSampleCount(count: Int) {
        check(count == CORRECTNESS_SAMPLE_COUNT) { "Expected the fixed 400-image benchmark set" }
    }

    fun validateVisibleDataset(manifest: JSONObject, classNames: List<String>) {
        check(manifest.optInt("schemaVersion", -1) == 2) {
            "Only visible benchmark manifest schema v2 is supported"
        }
        check(manifest.optString("modality") == "visible") { "Benchmark modality must be visible" }
        check(manifest.optString("datasetId") == VISIBLE_DATASET_ID) { "Unapproved visible benchmark dataset" }
        check(manifest.optInt("seed", -1) == BENCHMARK_SEED) { "Visible benchmark seed must be 20260730" }
        check(classNames == listOf("fire", "smoke")) { "Visible benchmark requires fire and smoke classes" }
        val samples = manifest.getJSONArray("samples")
        check(samples.length() == CORRECTNESS_SAMPLE_COUNT) { "Expected the fixed 400-image benchmark set" }
        val evaluatedClasses = buildSet {
            for (sampleIndex in 0 until samples.length()) {
                val boxes = samples.getJSONObject(sampleIndex).getJSONArray("expectedBoxes")
                val sample = samples.getJSONObject(sampleIndex)
                check(sample.getString("imageSha256").matches(SHA256)) { "Visible image SHA-256 is required" }
                check(sample.getString("labelSha256").matches(SHA256)) { "Visible label SHA-256 is required" }
                check(sample.getString("image").startsWith("images/")) { "Visible benchmark image path is invalid" }
                check(sample.getString("label").startsWith("labels/")) { "Visible benchmark label path is invalid" }
                for (boxIndex in 0 until boxes.length()) {
                    val classIndex = boxes.getJSONObject(boxIndex).getInt("class")
                    check(classIndex in classNames.indices) { "Visible benchmark box class must be 0 or 1" }
                    add(classIndex)
                }
            }
        }
        val foundTags = buildSet {
            for (sampleIndex in 0 until samples.length()) {
                val tags = samples.getJSONObject(sampleIndex).getJSONArray("tags")
                for (tagIndex in 0 until tags.length()) add(tags.getString(tagIndex))
            }
        }
        check(foundTags.containsAll(REQUIRED_TAGS)) { "Visible benchmark is missing required tags" }
        check(evaluatedClasses.containsAll(classNames.indices.toSet())) {
            "Visible benchmark samples must evaluate both fire and smoke"
        }
    }

    fun validateVisibleBaseline(
        baseline: JSONObject,
        benchmark: JSONObject,
        model: ModelManifest,
        benchmarkManifestSha256: String,
    ) {
        val baselineModel = baseline.getJSONObject("model")
        check(
            baselineModel.getString("name") == model.sourceName &&
                baselineModel.getString("sha256") == model.sourceSha256,
        ) { "PyTorch baseline must be generated from the visible model manifest source" }
        check(baseline.getString("modality") == "visible") { "PyTorch baseline modality must be visible" }
        check(baseline.getString("datasetId") == VISIBLE_DATASET_ID) { "PyTorch baseline dataset is unapproved" }
        check(baseline.getInt("seed") == BENCHMARK_SEED) { "PyTorch baseline seed is invalid" }
        check(baseline.getString("benchmarkManifestSha256") == benchmarkManifestSha256) {
            "PyTorch baseline is not bound to the packaged benchmark manifest"
        }
        check(baseline.getInt("inputSize") == model.inputWidth && model.inputWidth == 960) {
            "PyTorch baseline must use the visible 960 input"
        }
        check(
            abs(baseline.getDouble("confidenceThreshold") - model.confidenceThreshold) < 1e-6 &&
                abs(baseline.getDouble("iouThreshold") - model.iouThreshold) < 1e-6,
        ) { "PyTorch baseline thresholds must match the visible model manifest" }
        val benchmarkIds = benchmark.getJSONArray("samples").sampleIds()
        val baselineIds = baseline.getJSONArray("samples").sampleIds()
        check(benchmarkIds.size == benchmark.getJSONArray("samples").length() && baselineIds.size == baseline.getJSONArray("samples").length()) {
            "Benchmark and PyTorch baseline sample IDs must be unique"
        }
        check(baselineIds == benchmarkIds) { "PyTorch baseline must cover exactly the evaluated visible samples" }
        val benchmarkSamples = benchmark.getJSONArray("samples").byId()
        val baselineSamples = baseline.getJSONArray("samples").byId()
        for (id in benchmarkIds) {
            val expected = benchmarkSamples.getValue(id)
            val actual = baselineSamples.getValue(id)
            check(actual.getString("imageSha256") == expected.getString("imageSha256")) {
                "PyTorch baseline image hash differs for $id"
            }
            check(actual.getString("labelSha256") == expected.getString("labelSha256")) {
                "PyTorch baseline label hash differs for $id"
            }
            check(actual.getJSONArray("tags").strings() == expected.getJSONArray("tags").strings()) {
                "PyTorch baseline tags differ for $id"
            }
            check(boxesEqual(actual.getJSONArray("expectedBoxes"), expected.getJSONArray("expectedBoxes"))) {
                "PyTorch baseline expected boxes differ for $id"
            }
        }
    }

    private fun org.json.JSONArray.sampleIds(): Set<String> = buildSet {
        for (index in 0 until length()) add(getJSONObject(index).getString("id"))
    }

    private fun org.json.JSONArray.byId(): Map<String, JSONObject> = buildMap {
        for (index in 0 until length()) {
            val sample = getJSONObject(index)
            put(sample.getString("id"), sample)
        }
    }

    private fun org.json.JSONArray.strings(): List<String> = buildList {
        for (index in 0 until length()) add(getString(index))
    }

    private fun boxesEqual(first: org.json.JSONArray, second: org.json.JSONArray): Boolean {
        if (first.length() != second.length()) return false
        return (0 until first.length()).all { index ->
            val left = first.getJSONObject(index)
            val right = second.getJSONObject(index)
            left.getInt("class") == right.getInt("class") &&
                listOf("x", "y", "width", "height").all { field ->
                    abs(left.getDouble(field) - right.getDouble(field)) < 1e-9
                }
        }
    }
}
