package com.yinxin.uavfir.benchmark

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkPartialResultsTest {
    @Test
    fun merge_accumulatesEnginesOnlyUnderTheExactSameRunProvenance() {
        val provenance = provenance()
        val first = BenchmarkPartialResults.merge(null, provenance, Engine.ONNX, JSONObject("""{"recall":0.9}"""))
        val second = BenchmarkPartialResults.merge(first, provenance, Engine.NCNN, JSONObject("""{"recall":0.9}"""))

        assertEquals(setOf("onnx", "ncnn"), second.getJSONObject("engines").keys().asSequence().toSet())
        BenchmarkPartialResults.requireMatchingProvenance(second, provenance)
    }

    @Test(expected = IllegalStateException::class)
    fun merge_rejectsEvidenceFromADifferentModelOrRun() {
        val current = BenchmarkPartialResults.merge(
            null,
            provenance(),
            Engine.ONNX,
            JSONObject("""{"recall":0.9}"""),
        )

        BenchmarkPartialResults.merge(
            current,
            provenance().put("runId", "different-run"),
            Engine.NCNN,
            JSONObject("""{"recall":0.9}"""),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun merge_rejectsLegacyPartialsWithoutProvenance() {
        BenchmarkPartialResults.merge(
            JSONObject("""{"onnx":{"recall":0.9}}"""),
            provenance(),
            Engine.NCNN,
            JSONObject("""{"recall":0.9}"""),
        )
    }

    private fun provenance() = JSONObject(
        """
        {
          "runId":"visible-960-run",
          "modelManifestSha256":"${"a".repeat(64)}",
          "benchmarkManifestSha256":"${"b".repeat(64)}",
          "pytorchBaselineSha256":"${"c".repeat(64)}",
          "deviceFingerprint":"dji/rcplus/test",
          "deviceIdSha256":"${"1".repeat(64)}",
          "benchmarkApkSha256":"${"d".repeat(64)}",
          "instrumentationApkSha256":"${"2".repeat(64)}",
          "agentPackage":"com.yinxin.uavfir",
          "agentVersionName":"0.1.2",
          "agentVersionCode":3,
          "agentApkSha256":"${"e".repeat(64)}",
          "agentSigningCertificateSha256":"${"f".repeat(64)}",
          "agentRunning":true
        }
        """.trimIndent(),
    ).put("agentVersionCode", 3L)
}
