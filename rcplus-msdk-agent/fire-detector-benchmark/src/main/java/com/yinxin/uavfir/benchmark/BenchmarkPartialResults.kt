package com.yinxin.uavfir.benchmark

import org.json.JSONObject

internal object BenchmarkPartialResults {
    private val provenanceFields = setOf(
        "runId",
        "modelManifestSha256",
        "benchmarkManifestSha256",
        "pytorchBaselineSha256",
        "deviceFingerprint",
        "deviceIdSha256",
        "benchmarkApkSha256",
        "instrumentationApkSha256",
        "agentPackage",
        "agentVersionName",
        "agentVersionCode",
        "agentApkSha256",
        "agentSigningCertificateSha256",
        "agentRunning",
    )

    fun merge(current: JSONObject?, provenance: JSONObject, engine: Engine, report: JSONObject): JSONObject {
        validateProvenance(provenance)
        val document = current ?: JSONObject()
            .put("provenance", JSONObject(provenance.toString()))
            .put("engines", JSONObject())
        requireMatchingProvenance(document, provenance)
        document.getJSONObject("engines").put(engine.name.lowercase(), JSONObject(report.toString()))
        return document
    }

    fun requireMatchingProvenance(document: JSONObject, expected: JSONObject) {
        check(document.keysSet() == setOf("provenance", "engines")) {
            "Legacy or malformed benchmark partial cannot be merged"
        }
        val actual = document.getJSONObject("provenance")
        validateProvenance(actual)
        validateProvenance(expected)
        val matches = provenanceFields.all { field ->
            when (field) {
                "agentVersionCode" -> actual.getLong(field) == expected.getLong(field)
                "agentRunning" -> actual.getBoolean(field) == expected.getBoolean(field)
                else -> actual.getString(field) == expected.getString(field)
            }
        }
        check(matches) {
            "Benchmark partial provenance does not match this visible-960 run"
        }
    }

    private fun validateProvenance(provenance: JSONObject) {
        check(provenance.keysSet() == provenanceFields) { "Incomplete benchmark run provenance" }
        check(provenance.getString("runId").isNotBlank()) { "Benchmark runId must be provided" }
        for (field in listOf(
            "modelManifestSha256",
            "benchmarkManifestSha256",
            "pytorchBaselineSha256",
            "deviceIdSha256",
            "benchmarkApkSha256",
            "instrumentationApkSha256",
            "agentApkSha256",
            "agentSigningCertificateSha256",
        )) {
            check(provenance.getString(field).matches(Regex("[0-9a-f]{64}"))) {
                "$field must be a lowercase SHA-256"
            }
        }
        check(provenance.getString("deviceFingerprint").isNotBlank()) { "Device fingerprint is required" }
        check(provenance.getString("agentPackage") == "com.yinxin.uavfir") { "Formal Agent package is required" }
        check(provenance.getString("agentVersionName").isNotBlank()) { "Agent version name is required" }
        check(provenance.getLong("agentVersionCode") > 0) { "Agent version code is required" }
    }

    private fun JSONObject.keysSet(): Set<String> = buildSet {
        val iterator = keys()
        while (iterator.hasNext()) add(iterator.next())
    }
}
