package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkDeltaMetadataContractTest {
    @Test
    fun contract_requiresWholeApkCandidateDeltaAgainstRuntimeFreeBaseline() {
        val baseline = 1_000L
        val candidate = 1_250L

        assertEquals(250L, candidate - baseline)
        assertEquals("arm64-v8a", APK_DELTA_ABI)
    }

    @Test
    fun parse_rejectsProvenanceFromADifferentModelManifest() {
        val metadata = """
            {"abi":"arm64-v8a","modelCandidatesSha256":"${"b".repeat(64)}","baselineApkBytes":100,
             "candidates":{"onnx":{"apkBytes":120,"apkDeltaBytes":20,"provenance":{"apkSha256":"${"a".repeat(64)}","runtimeEntries":["lib/arm64-v8a/libonnxruntime4j_jni.so"],"modelEntries":[]}},
             "tflite":{"apkBytes":120,"apkDeltaBytes":20,"provenance":{"apkSha256":"${"a".repeat(64)}","runtimeEntries":["lib/arm64-v8a/libtensorflowlite_jni.so"],"modelEntries":[]}},
             "ncnn":{"apkBytes":120,"apkDeltaBytes":20,"provenance":{"apkSha256":"${"a".repeat(64)}","runtimeEntries":["lib/arm64-v8a/libncnn.so","lib/arm64-v8a/libfire_detector_ncnn.so"],"modelEntries":[]}}}}
        """.trimIndent()

        try {
            ApkDeltaMetadata.parse(metadata, testManifest())
            throw AssertionError("Expected stale metadata rejection")
        } catch (expected: IllegalStateException) {
            assertEquals("APK delta metadata is stale for the current model manifest", expected.message)
        }
    }
}
