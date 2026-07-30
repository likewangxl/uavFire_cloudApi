package com.yinxin.uavfir.benchmark

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BenchmarkPartialResultsTest {
    @Test
    fun beginSession_generatesDigestOverHarnessNonceBootAndExpiry() {
        val session = provenance()

        assertEquals("harness-generated-nonce", session.getString("gateSessionNonce"))
        assertEquals("boot-identity", session.getString("bootId"))
        assertNotEquals("", session.getString("provenanceDigest"))
        BenchmarkPartialResults.requireFreshSession(session, NOW + 1, "boot-identity")
    }

    @Test
    fun merge_accumulatesEnginesOnlyUnderTheExactSameRunProvenance() {
        val provenance = provenance()
        val first = BenchmarkPartialResults.merge(null, provenance, Engine.ONNX, report(Engine.ONNX, provenance))
        val second = BenchmarkPartialResults.merge(first, provenance, Engine.NCNN, report(Engine.NCNN, provenance))

        assertEquals(setOf("onnx", "ncnn"), second.getJSONObject("engines").keys().asSequence().toSet())
        BenchmarkPartialResults.requireMatchingProvenance(second, provenance)
    }

    @Test(expected = IllegalStateException::class)
    fun merge_rejectsEvidenceFromADifferentModelOrSession() {
        val firstSession = provenance()
        val current = BenchmarkPartialResults.merge(
            null,
            firstSession,
            Engine.ONNX,
            report(Engine.ONNX, firstSession),
        )
        val differentSession = provenance(nonce = "different-harness-session")

        BenchmarkPartialResults.merge(
            current,
            differentSession,
            Engine.NCNN,
            report(Engine.NCNN, differentSession),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun requireFreshSession_rejectsExpiredEvidence() {
        BenchmarkPartialResults.requireFreshSession(provenance(), EXPIRES_AT + 1, "boot-identity")
    }

    @Test(expected = IllegalStateException::class)
    fun requireFreshSession_rejectsCrossBootEvidence() {
        BenchmarkPartialResults.requireFreshSession(provenance(), NOW + 1, "different-boot")
    }

    @Test(expected = IllegalStateException::class)
    fun requireFreshSession_rejectsTamperedProvenanceDigest() {
        val provenance = provenance().put("agentVersionName", "tampered")

        BenchmarkPartialResults.requireFreshSession(provenance, NOW + 1, "boot-identity")
    }

    @Test(expected = IllegalStateException::class)
    fun merge_rejectsReportWhoseDeclaredEngineDoesNotMatchTarget() {
        val provenance = provenance()
        BenchmarkPartialResults.merge(
            null,
            provenance,
            Engine.NCNN,
            report(Engine.ONNX, provenance),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun merge_rejectsReportFromAnotherProvenanceDigest() {
        val provenance = provenance()
        BenchmarkPartialResults.merge(
            null,
            provenance,
            Engine.NCNN,
            report(Engine.NCNN, provenance).put("provenanceDigest", "0".repeat(64)),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun merge_rejectsLegacyPartialsWithoutProvenance() {
        val provenance = provenance()
        BenchmarkPartialResults.merge(
            JSONObject("""{"onnx":{"recall":0.9}}"""),
            provenance,
            Engine.NCNN,
            report(Engine.NCNN, provenance),
        )
    }

    private fun provenance(nonce: String = "harness-generated-nonce"): JSONObject =
        BenchmarkPartialResults.beginSession(
            staticProvenance(),
            nonce = nonce,
            bootId = "boot-identity",
            startedAtEpochMillis = NOW,
            expiresAtEpochMillis = EXPIRES_AT,
        )

    private fun staticProvenance() = JSONObject(
        """
        {
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
          "agentRealUxsdk":true,
          "agentHealthContract":"agent-process-v1",
          "agentRunning":true
        }
        """.trimIndent(),
    ).put("agentVersionCode", 3L)

    private fun report(engine: Engine, provenance: JSONObject) = JSONObject()
        .put("engine", engine.name.lowercase())
        .put("provenanceDigest", provenance.getString("provenanceDigest"))
        .put("recall", 0.9)

    private companion object {
        const val NOW = 1_000_000L
        const val EXPIRES_AT = NOW + 6 * 60 * 60 * 1_000L
    }
}
