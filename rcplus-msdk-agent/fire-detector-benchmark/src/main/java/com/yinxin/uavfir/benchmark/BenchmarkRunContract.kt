package com.yinxin.uavfir.benchmark

internal object BenchmarkRunContract {
    const val CORRECTNESS_SAMPLE_COUNT = 400
    const val WARM_UP_FRAMES = 30
    const val RUN_DURATION_MILLIS = 30 * 60 * 1_000L
    const val WINDOW_MILLIS = 5 * 60 * 1_000L

    fun validateSampleCount(count: Int) {
        check(count == CORRECTNESS_SAMPLE_COUNT) { "Expected the fixed 400-image benchmark set" }
    }
}
