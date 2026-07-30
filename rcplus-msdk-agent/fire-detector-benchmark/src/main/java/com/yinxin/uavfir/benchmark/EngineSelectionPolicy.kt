package com.yinxin.uavfir.benchmark

enum class Engine {
    ONNX,
    TFLITE,
    NCNN,
}

data class EngineBenchmark(
    val engine: Engine,
    val recall: Double,
    val p95Millis: Double,
    val firstWindowP95Millis: Double,
    val finalWindowP95Millis: Double,
    val apkDeltaBytes: Long,
    val stabilityDurationMillis: Long,
    val falsePositives: Int,
    val inferenceSampleCount: Int,
    val firstWindowSampleCount: Int,
    val finalWindowSampleCount: Int,
)

/**
 * The release gate deliberately returns no engine when the device run cannot prove every limit.
 */
object EngineSelectionPolicy {
    const val MAX_RECALL_DROP = 0.02
    const val MAX_P95_MILLIS = 200.0
    const val MAX_STABILITY_SLOWDOWN = 1.20
    const val APK_DELTA_P95_WINDOW = 1.10

    fun select(pytorchRecall: Double, candidates: List<EngineBenchmark>): EngineBenchmark? {
        if (candidates.size != Engine.entries.size) return null
        if (candidates.map(EngineBenchmark::engine).toSet() != Engine.entries.toSet()) return null
        if (candidates.any { !it.hasCompleteEvidence() }) return null
        val ncnn = candidates.singleOrNull { it.engine == Engine.NCNN } ?: return null
        return ncnn.takeIf {
            it.recall >= pytorchRecall - MAX_RECALL_DROP &&
                it.p95Millis <= MAX_P95_MILLIS &&
                it.finalWindowP95Millis <= it.firstWindowP95Millis * MAX_STABILITY_SLOWDOWN
        }
    }

    private fun EngineBenchmark.hasCompleteEvidence(): Boolean =
        recall in 0.0..1.0 &&
            p95Millis > 0.0 &&
            firstWindowP95Millis > 0.0 &&
            finalWindowP95Millis > 0.0 &&
            apkDeltaBytes >= 0 &&
            stabilityDurationMillis >= BenchmarkRunContract.RUN_DURATION_MILLIS &&
            falsePositives >= 0 &&
            inferenceSampleCount > 0 &&
            firstWindowSampleCount > 0 &&
            finalWindowSampleCount > 0
}
