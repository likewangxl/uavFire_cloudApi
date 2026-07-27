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
        val passing = candidates.filter { candidate ->
            candidate.recall >= pytorchRecall - MAX_RECALL_DROP &&
                candidate.p95Millis <= MAX_P95_MILLIS &&
                candidate.firstWindowP95Millis > 0.0 &&
                candidate.finalWindowP95Millis <= candidate.firstWindowP95Millis * MAX_STABILITY_SLOWDOWN
        }
        val fastest = passing.minOfOrNull(EngineBenchmark::p95Millis) ?: return null
        return passing
            .filter { it.p95Millis <= fastest * APK_DELTA_P95_WINDOW }
            .sortedWith(
                compareBy<EngineBenchmark> { it.apkDeltaBytes }
                    .thenBy { it.p95Millis }
                    .thenBy { engineTieBreak(it.engine) },
            )
            .firstOrNull()
    }

    private fun engineTieBreak(engine: Engine): Int = when (engine) {
        Engine.ONNX -> 0
        Engine.TFLITE -> 1
        Engine.NCNN -> 2
    }
}
