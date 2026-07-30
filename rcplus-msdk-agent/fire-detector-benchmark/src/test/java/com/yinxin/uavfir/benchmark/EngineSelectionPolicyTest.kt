package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineSelectionPolicyTest {
    @Test
    fun select_rejectsRecallMoreThanTwoPercentagePointsBelowPytorch() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = candidatesWithNcnn(recall = 0.879),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsP95AboveTwoHundredMilliseconds() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = candidatesWithNcnn(p95Millis = 200.1),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsFinalWindowMoreThanTwentyPercentSlowerThanFirstWindow() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = candidatesWithNcnn(firstWindowP95Millis = 100.0, finalWindowP95Millis = 120.1),
        )

        assertNull(result)
    }

    @Test
    fun select_acceptsCandidatesExactlyAtEveryHardThreshold() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = candidatesWithNcnn(
                recall = 0.88,
                p95Millis = 200.0,
                firstWindowP95Millis = 100.0,
                finalWindowP95Millis = 120.0,
            ),
        )

        assertEquals(Engine.NCNN, result?.engine)
    }

    @Test
    fun select_requiresNcnnToPassIndependentlyOfComparisonEngines() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(
                candidate(Engine.ONNX, p95Millis = 121.0),
                candidate(Engine.TFLITE, p95Millis = 115.0),
                candidate(Engine.NCNN, p95Millis = 200.1),
            ),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsMissingThirtyMinuteStabilityEvidence() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = candidatesWithNcnn(stabilityDurationMillis = 30 * 60 * 1_000L - 1),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsMissingFirstOrFinalFiveMinuteWindowEvidence() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = candidatesWithNcnn(finalWindowP95Millis = 0.0),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsMissingComparisonEngineEvidence() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(candidate(Engine.ONNX), candidate(Engine.NCNN)),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsIncompleteComparisonEngineStabilityEvidence() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(
                candidate(Engine.ONNX),
                candidate(Engine.TFLITE, stabilityDurationMillis = 1),
                candidate(Engine.NCNN),
            ),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsDuplicateEngineEvidence() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(
                candidate(Engine.ONNX),
                candidate(Engine.TFLITE),
                candidate(Engine.NCNN),
                candidate(Engine.ONNX),
            ),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsMissingAccuracyOrWindowObservationEvidence() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(
                candidate(Engine.ONNX),
                candidate(Engine.TFLITE),
                candidate(Engine.NCNN, inferenceSampleCount = 0),
            ),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsMissingFormalAgentHealthChecks() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(
                candidate(Engine.ONNX),
                candidate(Engine.TFLITE),
                candidate(Engine.NCNN, agentHealthCheckCount = 2),
            ),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsMissingCandidateRuntimeOrCurrentSourceBridgeIdentity() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(
                candidate(Engine.ONNX),
                candidate(Engine.TFLITE),
                candidate(Engine.NCNN, ncnnBridgeSourceSha256 = ""),
            ),
        )

        assertNull(result)
    }

    @Test
    fun select_rejectsMeasurementOnlyNcnnWithoutActuallyExecutingThoseLibraries() {
        val result = EngineSelectionPolicy.select(
            pytorchRecall = 0.90,
            candidates = listOf(
                candidate(Engine.ONNX),
                candidate(Engine.TFLITE),
                candidate(Engine.NCNN, executingNcnnRuntimeSha256 = null),
            ),
        )

        assertNull(result)
    }

    private fun candidatesWithNcnn(
        recall: Double = 0.90,
        p95Millis: Double = 100.0,
        firstWindowP95Millis: Double = 100.0,
        finalWindowP95Millis: Double = 120.0,
        stabilityDurationMillis: Long = 30 * 60 * 1_000L,
    ) = listOf(
        candidate(Engine.ONNX),
        candidate(Engine.TFLITE),
        candidate(
            Engine.NCNN,
            recall = recall,
            p95Millis = p95Millis,
            firstWindowP95Millis = firstWindowP95Millis,
            finalWindowP95Millis = finalWindowP95Millis,
            stabilityDurationMillis = stabilityDurationMillis,
        ),
    )

    private fun candidate(
        engine: Engine,
        recall: Double = 0.90,
        p95Millis: Double = 100.0,
        firstWindowP95Millis: Double = 100.0,
        finalWindowP95Millis: Double = 120.0,
        apkDeltaBytes: Long = 1_000_000,
        stabilityDurationMillis: Long = 30 * 60 * 1_000L,
        falsePositives: Int = 0,
        inferenceSampleCount: Int = 1,
        firstWindowSampleCount: Int = 1,
        finalWindowSampleCount: Int = 1,
        agentHealthCheckCount: Int = 3,
        candidateApkSha256: String = "a".repeat(64),
        runtimeSha256: Map<String, String> = ApkDeltaMetadata.expectedRuntimeEntries(engine)
            .associateWith { "b".repeat(64) },
        ncnnPackageVersion: String? = if (engine == Engine.NCNN) APPROVED_NCNN_VERSION else null,
        ncnnPackageArchiveSha256: String? = if (engine == Engine.NCNN) APPROVED_NCNN_ARCHIVE_SHA256 else null,
        ncnnBridgeSourceSha256: String? = if (engine == Engine.NCNN) "d".repeat(64) else null,
        ncnnBridgeSha256: String? = if (engine == Engine.NCNN) "b".repeat(64) else null,
        executingBenchmarkApkSha256: String? = if (engine == Engine.NCNN) "e".repeat(64) else null,
        executingNcnnRuntimeSha256: String? = if (engine == Engine.NCNN) "b".repeat(64) else null,
        executingNcnnBridgeSha256: String? = if (engine == Engine.NCNN) "b".repeat(64) else null,
        reviewedNcnnBridgeSourceSha256: String? = if (engine == Engine.NCNN) "d".repeat(64) else null,
    ) = EngineBenchmark(
        engine = engine,
        recall = recall,
        p95Millis = p95Millis,
        firstWindowP95Millis = firstWindowP95Millis,
        finalWindowP95Millis = finalWindowP95Millis,
        apkDeltaBytes = apkDeltaBytes,
        stabilityDurationMillis = stabilityDurationMillis,
        falsePositives = falsePositives,
        inferenceSampleCount = inferenceSampleCount,
        firstWindowSampleCount = firstWindowSampleCount,
        finalWindowSampleCount = finalWindowSampleCount,
        agentHealthCheckCount = agentHealthCheckCount,
        candidateApkSha256 = candidateApkSha256,
        runtimeSha256 = runtimeSha256,
        ncnnPackageVersion = ncnnPackageVersion,
        ncnnPackageArchiveSha256 = ncnnPackageArchiveSha256,
        ncnnBridgeSourceSha256 = ncnnBridgeSourceSha256,
        ncnnBridgeSha256 = ncnnBridgeSha256,
        executingBenchmarkApkSha256 = executingBenchmarkApkSha256,
        executingNcnnRuntimeSha256 = executingNcnnRuntimeSha256,
        executingNcnnBridgeSha256 = executingNcnnBridgeSha256,
        reviewedNcnnBridgeSourceSha256 = reviewedNcnnBridgeSourceSha256,
    )
}
