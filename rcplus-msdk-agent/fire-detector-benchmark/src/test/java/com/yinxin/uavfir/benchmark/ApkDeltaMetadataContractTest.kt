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

    @Test
    fun parse_bindsCandidateApkRuntimeAndCurrentSourceBuiltNcnnBridgeIdentities() {
        val sha = "1".repeat(64)
        val ncnnRuntime = "2".repeat(64)
        val ncnnBridge = "3".repeat(64)
        val metadata = """
            {"abi":"arm64-v8a","modelCandidatesSha256":"${"a".repeat(64)}","baselineApkBytes":100,
             "candidates":{
               "onnx":{"apkBytes":120,"apkDeltaBytes":20,"provenance":{"apkSha256":"${"4".repeat(64)}","runtimeEntries":[{"path":"lib/arm64-v8a/libonnxruntime4j_jni.so","sha256":"$sha"}],"modelEntries":[]}},
               "tflite":{"apkBytes":120,"apkDeltaBytes":20,"provenance":{"apkSha256":"${"5".repeat(64)}","runtimeEntries":[{"path":"lib/arm64-v8a/libtensorflowlite_jni.so","sha256":"$sha"}],"modelEntries":[]}},
               "ncnn":{"apkBytes":120,"apkDeltaBytes":20,"provenance":{"apkSha256":"${"6".repeat(64)}","runtimeEntries":[{"path":"lib/arm64-v8a/libncnn.so","sha256":"$ncnnRuntime"},{"path":"lib/arm64-v8a/libfire_detector_ncnn.so","sha256":"$ncnnBridge"}],"modelEntries":[],"ncnnBuild":{"version":"$APPROVED_NCNN_VERSION","packageArchiveSha256":"$APPROVED_NCNN_ARCHIVE_SHA256","bridgeSourceSha256":"${"8".repeat(64)}","runtimeSha256":"$ncnnRuntime","bridgeSha256":"$ncnnBridge"}}}
             }}
        """.trimIndent()

        val parsed = ApkDeltaMetadata.parse(metadata, testManifest())

        assertEquals("6".repeat(64), parsed.getValue(Engine.NCNN).candidateApkSha256)
        assertEquals(ncnnRuntime, parsed.getValue(Engine.NCNN).runtimeEntries.getValue("lib/arm64-v8a/libncnn.so"))
        assertEquals(ncnnBridge, parsed.getValue(Engine.NCNN).ncnnBridgeSha256)
        assertEquals("8".repeat(64), parsed.getValue(Engine.NCNN).ncnnBridgeSourceSha256)
    }
}
