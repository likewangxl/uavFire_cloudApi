package com.yinxin.uavfir.benchmark

import org.json.JSONObject
import kotlin.math.abs

internal object BenchmarkRunContract {
    const val CORRECTNESS_SAMPLE_COUNT = 400
    const val WARM_UP_FRAMES = 30
    const val RUN_DURATION_MILLIS = 30 * 60 * 1_000L
    const val WINDOW_MILLIS = 5 * 60 * 1_000L

    fun validateSampleCount(count: Int) {
        check(count == CORRECTNESS_SAMPLE_COUNT) { "Expected the fixed 400-image benchmark set" }
    }

    fun validateVisibleDataset(manifest: JSONObject, classNames: List<String>) {
        check(manifest.optInt("schemaVersion", -1) == 2) {
            "Only visible benchmark manifest schema v2 is supported"
        }
        check(classNames == listOf("fire", "smoke")) { "Visible benchmark requires fire and smoke classes" }
        val samples = manifest.getJSONArray("samples")
        val evaluatedClasses = buildSet {
            for (sampleIndex in 0 until samples.length()) {
                val boxes = samples.getJSONObject(sampleIndex).getJSONArray("expectedBoxes")
                for (boxIndex in 0 until boxes.length()) add(boxes.getJSONObject(boxIndex).getInt("class"))
            }
        }
        check(evaluatedClasses.containsAll(classNames.indices.toSet())) {
            "Visible benchmark samples must evaluate both fire and smoke"
        }
    }

    fun validateVisibleBaseline(baseline: JSONObject, benchmark: JSONObject, model: ModelManifest) {
        val baselineModel = baseline.getJSONObject("model")
        check(
            baselineModel.getString("name") == model.sourceName &&
                baselineModel.getString("sha256") == model.sourceSha256,
        ) { "PyTorch baseline must be generated from the visible model manifest source" }
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
    }

    private fun org.json.JSONArray.sampleIds(): Set<String> = buildSet {
        for (index in 0 until length()) add(getJSONObject(index).getString("id"))
    }
}
