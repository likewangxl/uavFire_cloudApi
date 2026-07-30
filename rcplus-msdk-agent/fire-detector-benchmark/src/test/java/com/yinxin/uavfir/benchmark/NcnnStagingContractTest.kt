package com.yinxin.uavfir.benchmark

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NcnnStagingContractTest {
    @Test
    fun stagingConsumesOnlyIgnoredVisible960ArtifactsAndBenchmarkData() {
        val buildScript = File("build.gradle.kts").readText()

        assertTrue(buildScript.contains("ai-service/mobile-model/visible-960"))
        assertTrue(buildScript.contains("visible-fire-wechat-best2-20260728.onnx"))
        assertTrue(buildScript.contains("visible-fire-wechat-best2-20260728_float32.tflite"))
        assertTrue(buildScript.contains("visible-fire-wechat-best2-20260728_ncnn_model"))
        assertTrue(buildScript.contains("verifyVisibleBenchmarkInputs"))
        assertTrue(buildScript.contains("Expected exactly 400 visible benchmark images"))
        assertFalse(buildScript.contains("thermal-fire-yolov8n-640"))
    }

    @Test
    fun stagingAlwaysRunsToRemoveStaleLibrariesAndFailsClosedForNcnnCandidates() {
        val buildScript = File("build.gradle.kts").readText()

        assertTrue(buildScript.contains("outputs.upToDateWhen { false }"))
        assertFalse(buildScript.contains("onlyIf { measurementCandidate in setOf(\"ncnn\", \"benchmark\")"))
        assertTrue(buildScript.contains("deleteRecursively() || !outputDirectory.exists()"))
        assertTrue(buildScript.contains("if (!requiresNcnnRuntime) return@doLast"))
        assertTrue(buildScript.contains("NCNN packaging requires -PncnnPackageDir"))
        assertTrue(buildScript.contains("copy {"))
    }

    @Test
    fun syntheticApkDeltaFixtureCheckDoesNotLeaveDeviceGateMetadataBehind() {
        val buildScript = File("build.gradle.kts").readText()

        assertTrue(buildScript.contains("check(metadata.delete())"))
        assertTrue(buildScript.contains("Synthetic APK delta metadata must not remain"))
    }
}
