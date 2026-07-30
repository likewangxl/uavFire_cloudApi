package com.yinxin.uavfir.benchmark

import org.json.JSONObject
import org.json.JSONArray
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
        BenchmarkPartialResults.requireFreshSession(session, NOW + 1, "boot-identity", ELAPSED + 1)
    }

    @Test
    fun merge_accumulatesEnginesOnlyUnderTheExactSameRunProvenance() {
        val provenance = provenance()
        val first = BenchmarkPartialResults.merge(null, provenance, Engine.ONNX, report(Engine.ONNX, provenance))
        val second = BenchmarkPartialResults.merge(first, provenance, Engine.NCNN, report(Engine.NCNN, provenance))

        assertEquals(setOf("onnx", "ncnn"), second.getJSONObject("engines").keys().asSequence().toSet())
        BenchmarkPartialResults.requireMatchingProvenance(second, provenance)
    }

    @Test
    fun persistedIntegralDoubleDigestSurvivesRoundTripAndNextEngineMerge() {
        val provenance = provenance()
        val first = BenchmarkPartialResults.merge(
            null,
            provenance,
            Engine.ONNX,
            fullReport(Engine.ONNX, provenance, p95 = 152.0),
        )
        val persisted = JSONObject(first.toString())

        val second = BenchmarkPartialResults.merge(
            persisted,
            provenance,
            Engine.TFLITE,
            fullReport(Engine.TFLITE, provenance, p95 = 151.0),
        )

        assertEquals(setOf("onnx", "tflite"), second.getJSONObject("engines").keys().asSequence().toSet())
    }

    @Test
    fun persistedThreeEngineReportsCanSealRoundTripAndValidateFinalResult() {
        val provenance = provenance()
        var document: JSONObject? = null
        Engine.entries.forEach { engine ->
            document = BenchmarkPartialResults.merge(
                document?.let { JSONObject(it.toString()) },
                provenance,
                engine,
                fullReport(engine, provenance, p95 = 152.0),
            )
        }
        val engines = checkNotNull(document).getJSONObject("engines")
        val reports = Engine.entries.associate { it.name.lowercase() to engines.getJSONObject(it.name.lowercase()) }
        val final = BenchmarkPartialResults.sealFinalResult(provenance, 0.99, 0, reports)

        BenchmarkPartialResults.validateFinalResult(
            JSONObject(final.toString()),
            NOW + 1,
            "boot-identity",
            ELAPSED + 1,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun mergeRejectsNcnnExecutionApkThatDiffersFromSessionBenchmarkApk() {
        val provenance = provenance()
        val report = fullReport(Engine.NCNN, provenance, p95 = 152.0)
            .put("executingBenchmarkApkSha256", "7".repeat(64))
        val resealed = BenchmarkPartialResults.sealReport(Engine.NCNN, provenance, report)

        BenchmarkPartialResults.merge(null, provenance, Engine.NCNN, resealed)
    }

    @Test(expected = IllegalStateException::class)
    fun currentStaticProvenanceRejectsDifferentBenchmarkApkAtExportTime() {
        val session = provenance()
        val changed = staticProvenance().put("benchmarkApkSha256", "7".repeat(64))

        BenchmarkPartialResults.requireMatchingStaticProvenance(session, changed)
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
        BenchmarkPartialResults.requireFreshSession(provenance(), NOW + 1, "boot-identity", ELAPSED_EXPIRES + 1)
    }

    @Test(expected = IllegalStateException::class)
    fun requireFreshSession_rejectsCrossBootEvidence() {
        BenchmarkPartialResults.requireFreshSession(provenance(), NOW + 1, "different-boot", ELAPSED + 1)
    }

    @Test(expected = IllegalStateException::class)
    fun requireFreshSession_rejectsTamperedProvenanceDigest() {
        val provenance = provenance().put("agentVersionName", "tampered")

        BenchmarkPartialResults.requireFreshSession(provenance, NOW + 1, "boot-identity", ELAPSED + 1)
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
    fun merge_rejectsPersistedMetricsChangedAfterReportWasSealed() {
        val provenance = provenance()
        val tampered = report(Engine.NCNN, provenance).put("recall", 0.1)

        BenchmarkPartialResults.merge(null, provenance, Engine.NCNN, tampered)
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
            startedElapsedRealtimeMillis = ELAPSED,
            expiresElapsedRealtimeMillis = ELAPSED_EXPIRES,
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
          "benchmarkSigningCertificateSha256":"${"9".repeat(64)}",
          "instrumentationApkSha256":"${"2".repeat(64)}",
          "agentPackage":"com.yinxin.uavfir",
          "agentVersionName":"0.1.2",
          "agentVersionCode":3,
          "agentApkSha256":"${"e".repeat(64)}",
          "agentSigningCertificateSha256":"${"f".repeat(64)}",
          "agentBuildId":"uavfire-agent-0.1.2-3",
          "agentRealUxsdk":true,
          "agentHealthContract":"agent-sdk-health-v1",
          "agentRunning":true
        }
        """.trimIndent(),
    ).put("agentVersionCode", 3L)

    private fun report(engine: Engine, provenance: JSONObject) =
        fullReport(engine, provenance, p95 = 152.0)

    private fun fullReport(
        engine: Engine,
        provenance: JSONObject,
        p95: Double,
    ): JSONObject {
        val runtimePath = when (engine) {
            Engine.ONNX -> "lib/arm64-v8a/libonnxruntime4j_jni.so"
            Engine.TFLITE -> "lib/arm64-v8a/libtensorflowlite_jni.so"
            Engine.NCNN -> "lib/arm64-v8a/libncnn.so"
        }
        val runtimeEntries = mutableListOf(
            JSONObject().put("path", runtimePath).put("sha256", "6".repeat(64)),
        )
        if (engine == Engine.NCNN) {
            runtimeEntries += JSONObject()
                .put("path", "lib/arm64-v8a/libfire_detector_ncnn.so")
                .put("sha256", "8".repeat(64))
        }
        val payload = JSONObject()
            .put("recall", 0.985)
            .put("falsePositives", 0)
            .put("p95InferenceMillis", p95)
            .put("firstFiveMinuteP95Millis", 138.0)
            .put("finalFiveMinuteP95Millis", 153.0)
            .put("apkDeltaBytes", 1000L)
            .put("candidateApkSha256", "5".repeat(64))
            .put("runtimeEntries", JSONArray(runtimeEntries))
            .put("stabilityDurationMillis", 30 * 60 * 1_000L)
            .put("firstFiveMinuteSampleCount", 1)
            .put("finalFiveMinuteSampleCount", 1)
            .put("agentHealthCheckCount", 3)
            .put("inferenceSamples", JSONArray().put(JSONObject().put("inferenceMillis", 152.0)))
        if (engine == Engine.NCNN) {
            payload
                .put("ncnnPackageVersion", APPROVED_NCNN_VERSION)
                .put("ncnnPackageArchiveSha256", APPROVED_NCNN_ARCHIVE_SHA256)
                .put("ncnnBridgeSourceSha256", "9".repeat(64))
                .put("ncnnBridgeSha256", "8".repeat(64))
                .put("executingBenchmarkApkSha256", provenance.getString("benchmarkApkSha256"))
                .put("executingNcnnRuntimeSha256", "6".repeat(64))
                .put("executingNcnnBridgeSha256", "8".repeat(64))
                .put("reviewedNcnnBridgeSourceSha256", "9".repeat(64))
        }
        return BenchmarkPartialResults.sealReport(engine, provenance, payload)
    }

    private companion object {
        const val NOW = 1_000_000L
        const val EXPIRES_AT = NOW + 6 * 60 * 60 * 1_000L
        const val ELAPSED = 200_000L
        const val ELAPSED_EXPIRES = ELAPSED + 6 * 60 * 60 * 1_000L
    }
}
