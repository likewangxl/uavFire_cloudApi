package com.yinxin.uavfir.benchmark

import java.security.MessageDigest
import org.json.JSONObject

internal object BenchmarkPartialResults {
    const val SESSION_TTL_MILLIS = 6 * 60 * 60 * 1_000L

    private val staticProvenanceFields = setOf(
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
        "agentRealUxsdk",
        "agentHealthContract",
        "agentRunning",
    )
    private val sessionFields = setOf(
        "gateSessionNonce",
        "bootId",
        "sessionStartedAtEpochMillis",
        "sessionExpiresAtEpochMillis",
    )
    private val provenanceFields = staticProvenanceFields + sessionFields + "provenanceDigest"

    fun beginSession(
        staticProvenance: JSONObject,
        nonce: String,
        bootId: String,
        startedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): JSONObject {
        validateStaticProvenance(staticProvenance)
        check(nonce.isNotBlank()) { "Harness-generated session nonce is required" }
        check(bootId.isNotBlank()) { "Device boot identity is required" }
        check(expiresAtEpochMillis - startedAtEpochMillis == SESSION_TTL_MILLIS) {
            "Benchmark gate session must use the fixed six-hour TTL"
        }
        return JSONObject(staticProvenance.toString())
            .put("gateSessionNonce", nonce)
            .put("bootId", bootId)
            .put("sessionStartedAtEpochMillis", startedAtEpochMillis)
            .put("sessionExpiresAtEpochMillis", expiresAtEpochMillis)
            .also { it.put("provenanceDigest", digest(it)) }
    }

    fun merge(current: JSONObject?, provenance: JSONObject, engine: Engine, report: JSONObject): JSONObject {
        validateProvenance(provenance)
        check(report.optString("engine") == engine.name.lowercase()) {
            "Benchmark report engine does not match the adapter target"
        }
        check(report.optString("provenanceDigest") == provenance.getString("provenanceDigest")) {
            "Benchmark report provenance digest does not match this gate session"
        }
        val document = current ?: JSONObject()
            .put("provenance", JSONObject(provenance.toString()))
            .put("engines", JSONObject())
        requireMatchingProvenance(document, provenance)
        document.getJSONObject("engines").put(engine.name.lowercase(), JSONObject(report.toString()))
        return document
    }

    fun requireFreshSession(provenance: JSONObject, nowEpochMillis: Long, currentBootId: String) {
        validateProvenance(provenance)
        check(provenance.getString("bootId") == currentBootId) {
            "Benchmark gate evidence belongs to a different device boot"
        }
        check(nowEpochMillis in provenance.getLong("sessionStartedAtEpochMillis")..
            provenance.getLong("sessionExpiresAtEpochMillis")
        ) { "Benchmark gate session is not current" }
    }

    fun requireMatchingStaticProvenance(session: JSONObject, current: JSONObject) {
        validateProvenance(session)
        validateStaticProvenance(current)
        check(staticProvenanceFields.all { valuesMatch(session, current, it) }) {
            "Current device/build identity does not match the gate session"
        }
    }

    fun requireMatchingProvenance(document: JSONObject, expected: JSONObject) {
        check(document.keysSet() == setOf("provenance", "engines")) {
            "Legacy or malformed benchmark partial cannot be merged"
        }
        val actual = document.getJSONObject("provenance")
        validateProvenance(actual)
        validateProvenance(expected)
        check(provenanceFields.all { valuesMatch(actual, expected, it) }) {
            "Benchmark partial provenance does not match this visible-960 gate session"
        }
    }

    private fun validateProvenance(provenance: JSONObject) {
        check(provenance.keysSet() == provenanceFields) { "Incomplete benchmark run provenance" }
        validateStaticProvenance(provenance, requireExactKeys = false)
        check(provenance.getString("gateSessionNonce").isNotBlank()) {
            "Harness-generated gate session nonce is required"
        }
        check(provenance.getString("bootId").isNotBlank()) { "Device boot identity is required" }
        val startedAt = provenance.getLong("sessionStartedAtEpochMillis")
        val expiresAt = provenance.getLong("sessionExpiresAtEpochMillis")
        check(expiresAt - startedAt == SESSION_TTL_MILLIS) {
            "Benchmark gate session has an invalid TTL"
        }
        check(provenance.getString("provenanceDigest").matches(SHA256)) {
            "Benchmark provenance digest must be a lowercase SHA-256"
        }
        check(provenance.getString("provenanceDigest") == digest(provenance)) {
            "Benchmark provenance digest does not match its contents"
        }
    }

    private fun validateStaticProvenance(provenance: JSONObject, requireExactKeys: Boolean = true) {
        if (requireExactKeys) {
            check(provenance.keysSet() == staticProvenanceFields) {
                "Incomplete static benchmark provenance"
            }
        }
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
            check(provenance.getString(field).matches(SHA256)) {
                "$field must be a lowercase SHA-256"
            }
        }
        check(provenance.getString("deviceFingerprint").isNotBlank()) { "Device fingerprint is required" }
        check(provenance.getString("agentPackage") == "com.yinxin.uavfir") { "Formal Agent package is required" }
        check(provenance.getString("agentVersionName").isNotBlank()) { "Agent version name is required" }
        check(provenance.getLong("agentVersionCode") > 0) { "Agent version code is required" }
        check(provenance.getBoolean("agentRealUxsdk")) { "Formal Agent must use real UXSDK" }
        check(provenance.getString("agentHealthContract") == "agent-process-v1") {
            "Formal Agent health contract is unsupported"
        }
        check(provenance.getBoolean("agentRunning")) { "Formal Agent must be running" }
    }

    private fun digest(provenance: JSONObject): String {
        val canonical = (staticProvenanceFields + sessionFields)
            .sorted()
            .joinToString(separator = "\n") { field ->
                val value = provenance.get(field)
                val type = when (value) {
                    is Boolean -> "boolean"
                    is Number -> "number"
                    else -> "string"
                }
                "$field:$type:${value.toString().length}:${value}"
            }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun valuesMatch(first: JSONObject, second: JSONObject, field: String): Boolean = when (field) {
        "agentVersionCode", "sessionStartedAtEpochMillis", "sessionExpiresAtEpochMillis" ->
            first.getLong(field) == second.getLong(field)
        "agentRunning", "agentRealUxsdk" -> first.getBoolean(field) == second.getBoolean(field)
        else -> first.getString(field) == second.getString(field)
    }

    private fun JSONObject.keysSet(): Set<String> = buildSet {
        val iterator = keys()
        while (iterator.hasNext()) add(iterator.next())
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
