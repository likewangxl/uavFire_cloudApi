package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineSelectionPolicyTest {
    @Test
    fun select_rejectsRecallMoreThanTwoPercentagePointsBelowPytorch() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(candidate(Engine.ONNX, recall = 0.879)),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsP95AboveTwoHundredMilliseconds() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(candidate(Engine.ONNX, p95Millis = 200.1)),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsFinalWindowMoreThanTwentyPercentSlowerThanFirstWindow() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(candidate(Engine.ONNX, firstWindowP95Millis = 100.0, finalWindowP95Millis = 120.1)),
        )

        assertNull(result)
    }

    @Test
    fun select_acceptsCandidatesExactlyAtEveryHardThreshold() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(
                candidate(
                    Engine.ONNX,
                    recall = 0.88,
                    p95Millis = 200.0,
                    firstWindowP95Millis = 100.0,
                    finalWindowP95Millis = 120.0,
                ),
            ),
        )

        assertEquals(Engine.ONNX, result?.engine)
    }

    @Test
    fun select_choosesLowestP95AmongCandidatesThatPassEveryGate() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(
                candidate(Engine.ONNX, p95Millis = 121.0),
                candidate(Engine.TFLITE, p95Millis = 115.0),
                candidate(Engine.NCNN, p95Millis = 119.0),
            ),
        )

        assertEquals(Engine.TFLITE, result?.engine)
    }

    @Test
    fun select_choosesSmallestApkDeltaWhenP95ValuesAreWithinTenPercent() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(
                candidate(Engine.ONNX, p95Millis = 100.0, apkDeltaBytes = 9_000_000),
                candidate(Engine.TFLITE, p95Millis = 109.9, apkDeltaBytes = 4_000_000),
                candidate(Engine.NCNN, p95Millis = 120.0, apkDeltaBytes = 1_000_000),
            ),
        )

        assertEquals(Engine.TFLITE, result?.engine)
    }

    @Test
    fun select_usesOnnxThenTfliteThenNcnnForAnExactTie() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(
                candidate(Engine.NCNN, apkDeltaBytes = 1_000_000),
                candidate(Engine.TFLITE, apkDeltaBytes = 1_000_000),
                candidate(Engine.ONNX, apkDeltaBytes = 1_000_000),
            ),
        )

        assertEquals(Engine.ONNX, result?.engine)
    }

    private fun candidate(
        engine: Engine,
        recall: Double = 0.90,
        p95Millis: Double = 100.0,
        firstWindowP95Millis: Double = 100.0,
        finalWindowP95Millis: Double = 120.0,
        apkDeltaBytes: Long = 1_000_000,
    ) = EngineBenchmark(
        engine = engine,
        recall = recall,
        p95Millis = p95Millis,
        firstWindowP95Millis = firstWindowP95Millis,
        finalWindowP95Millis = finalWindowP95Millis,
        apkDeltaBytes = apkDeltaBytes,
    )
}
