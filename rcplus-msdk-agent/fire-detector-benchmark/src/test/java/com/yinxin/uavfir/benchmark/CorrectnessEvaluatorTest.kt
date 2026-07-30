package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class CorrectnessEvaluatorTest {
    @Test
    fun evaluate_doesNotMatchAnOverlappingDetectionFromTheWrongVisibleClass() {
        val expectedSmoke = Detection(0.1f, 0.1f, 0.5f, 0.5f, confidence = 1f, classIndex = 1)
        val detectedFire = expectedSmoke.copy(confidence = 0.9f, classIndex = 0)
        val sample = BenchmarkSample("smoke", "benchmark-set/smoke.jpg", 960, 960, listOf(expectedSmoke))

        val metrics = CorrectnessEvaluator.evaluate(listOf(sample to listOf(detectedFire)))

        assertEquals(0.0, metrics.recall, 0.0)
        assertEquals(1, metrics.falsePositives)
    }
}
