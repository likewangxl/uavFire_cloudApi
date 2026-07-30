package com.yinxin.uavfir.benchmark

import java.security.MessageDigest
import org.json.JSONArray
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
        "benchmarkSigningCertificateSha256",
        "instrumentationApkSha256",
        "agentPackage",
        "agentVersionName",
        "agentVersionCode",
        "agentApkSha256",
        "agentSigningCertificateSha256",
        "agentBuildId",
        "agentRealUxsdk",
        "agentHealthContract",
        "agentRunning",
    )
    private val sessionFields = setOf(
        "gateSessionNonce",
        "bootId",
        "sessionStartedAtEpochMillis",
        "sessionExpiresAtEpochMillis",
        "sessionStartedElapsedRealtimeMillis",
        "sessionExpiresElapsedRealtimeMillis",
    )
    private val provenanceFields = staticProvenanceFields + sessionFields + "provenanceDigest"

    fun beginSession(
        staticProvenance: JSONObject,
        nonce: String,
        bootId: String,
        startedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
        startedElapsedRealtimeMillis: Long,
        expiresElapsedRealtimeMillis: Long,
    ): JSONObject {
        validateStaticProvenance(staticProvenance)
        check(nonce.isNotBlank()) { "Harness-generated session nonce is required" }
        check(bootId.isNotBlank()) { "Device boot identity is required" }
        check(expiresAtEpochMillis - startedAtEpochMillis == SESSION_TTL_MILLIS) {
            "Benchmark gate session must use the fixed six-hour TTL"
        }
        check(expiresElapsedRealtimeMillis - startedElapsedRealtimeMillis == SESSION_TTL_MILLIS) {
            "Benchmark gate session must use the fixed six-hour monotonic TTL"
        }
        return JSONObject(staticProvenance.toString())
            .put("gateSessionNonce", nonce)
            .put("bootId", bootId)
            .put("sessionStartedAtEpochMillis", startedAtEpochMillis)
            .put("sessionExpiresAtEpochMillis", expiresAtEpochMillis)
            .put("sessionStartedElapsedRealtimeMillis", startedElapsedRealtimeMillis)
            .put("sessionExpiresElapsedRealtimeMillis", expiresElapsedRealtimeMillis)
            .also { it.put("provenanceDigest", digest(it)) }
    }

    fun sealReport(engine: Engine, provenance: JSONObject, report: JSONObject): JSONObject {
        validateProvenance(provenance)
        return JSONObject(report.toString())
            .put("engine", engine.name.lowercase())
            .put("adapterTarget", engine.name.lowercase())
            .put("provenanceDigest", provenance.getString("provenanceDigest"))
            .also { it.put("reportDigest", reportDigest(it)) }
    }

    fun merge(current: JSONObject?, provenance: JSONObject, engine: Engine, report: JSONObject): JSONObject {
        validateProvenance(provenance)
        check(
            report.optString("engine") == engine.name.lowercase() &&
                report.optString("adapterTarget") == engine.name.lowercase(),
        ) {
            "Benchmark report engine does not match the adapter target"
        }
        check(report.optString("provenanceDigest") == provenance.getString("provenanceDigest")) {
            "Benchmark report provenance digest does not match this gate session"
        }
        check(report.optString("reportDigest") == reportDigest(report)) {
            "Benchmark report digest does not match its contents"
        }
        validatePersistedReport(engine, report, provenance)
        val document = current ?: JSONObject()
            .put("provenance", JSONObject(provenance.toString()))
            .put("engines", JSONObject())
        requireMatchingProvenance(document, provenance)
        document.getJSONObject("engines").put(engine.name.lowercase(), JSONObject(report.toString()))
        return document
    }

    fun requireFreshSession(
        provenance: JSONObject,
        nowEpochMillis: Long,
        currentBootId: String,
        nowElapsedRealtimeMillis: Long,
    ) {
        validateProvenance(provenance)
        check(provenance.getString("bootId") == currentBootId) {
            "Benchmark gate evidence belongs to a different device boot"
        }
        check(nowEpochMillis >= provenance.getLong("sessionStartedAtEpochMillis")) {
            "Benchmark gate wall-clock audit is before the session start"
        }
        check(nowElapsedRealtimeMillis in provenance.getLong("sessionStartedElapsedRealtimeMillis")..
            provenance.getLong("sessionExpiresElapsedRealtimeMillis")
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
        val engines = document.getJSONObject("engines")
        val keys = engines.keysSet()
        check(keys.all { key -> Engine.entries.any { it.name.lowercase() == key } }) {
            "Benchmark partial contains an unknown engine"
        }
        keys.forEach { key ->
            validatePersistedReport(Engine.valueOf(key.uppercase()), engines.getJSONObject(key), actual)
        }
    }

    fun requireCompleteReports(document: JSONObject, expected: JSONObject): Map<String, JSONObject> {
        requireMatchingProvenance(document, expected)
        val engines = document.getJSONObject("engines")
        check(engines.keysSet() == Engine.entries.map { it.name.lowercase() }.toSet()) {
            "Formal visible-960 result requires all three engine reports"
        }
        return Engine.entries.associate { it.name.lowercase() to engines.getJSONObject(it.name.lowercase()) }
    }

    fun sealFinalResult(
        provenance: JSONObject,
        pytorchRecall: Double,
        pytorchFalsePositives: Int,
        reports: Map<String, JSONObject>,
    ): JSONObject {
        val document = JSONObject()
            .put("provenance", JSONObject(provenance.toString()))
            .put("engines", JSONObject().also { engines ->
                reports.forEach { (key, value) -> engines.put(key, JSONObject(value.toString())) }
            })
        val complete = requireCompleteReports(document, provenance)
        val selected = EngineSelectionPolicy.select(pytorchRecall, complete.values.map(::selectionInput))
        check(selected?.engine == Engine.NCNN) { "Formal visible-960 gate did not select NCNN" }
        return JSONObject()
            .put("schemaVersion", 3)
            .put("gateStatus", "VISIBLE_960_GATE_PASSED")
            .put("provenance", JSONObject(provenance.toString()))
            .put("pytorchRecall", pytorchRecall)
            .put("pytorchFalsePositives", pytorchFalsePositives)
            .put("selectedEngine", "ncnn")
            .put("engines", JSONObject(document.getJSONObject("engines").toString()))
            .also { it.put("finalResultDigest", finalResultDigest(it)) }
    }

    fun validateFinalResult(
        result: JSONObject,
        nowEpochMillis: Long,
        currentBootId: String,
        nowElapsedRealtimeMillis: Long,
    ) {
        check(
            result.keysSet() == setOf(
                "schemaVersion",
                "gateStatus",
                "provenance",
                "pytorchRecall",
                "pytorchFalsePositives",
                "selectedEngine",
                "engines",
                "finalResultDigest",
            ),
        ) { "Malformed formal visible-960 result" }
        check(result.getInt("schemaVersion") == 3 && result.getString("gateStatus") == "VISIBLE_960_GATE_PASSED") {
            "Only a formal visible-960 gate result can be exported"
        }
        check(result.getString("finalResultDigest") == finalResultDigest(result)) {
            "Final benchmark result digest does not match its contents"
        }
        val provenance = result.getJSONObject("provenance")
        requireFreshSession(provenance, nowEpochMillis, currentBootId, nowElapsedRealtimeMillis)
        val document = JSONObject()
            .put("provenance", JSONObject(provenance.toString()))
            .put("engines", JSONObject(result.getJSONObject("engines").toString()))
        val reports = requireCompleteReports(document, provenance)
        val selected = EngineSelectionPolicy.select(
            result.getDouble("pytorchRecall"),
            reports.values.map(::selectionInput),
        )
        check(selected?.engine == Engine.NCNN && result.getString("selectedEngine") == "ncnn") {
            "Persisted final selection cannot be reproduced"
        }
    }

    private fun validatePersistedReport(engine: Engine, report: JSONObject, provenance: JSONObject) {
        check(
            report.optString("engine") == engine.name.lowercase() &&
                report.optString("adapterTarget") == engine.name.lowercase(),
        ) { "Persisted benchmark report target is inconsistent" }
        check(report.optString("provenanceDigest") == provenance.getString("provenanceDigest")) {
            "Persisted benchmark report belongs to another session"
        }
        check(report.optString("reportDigest") == reportDigest(report)) {
            "Persisted benchmark report digest does not match its contents"
        }
        val evidence = selectionInput(report)
        check(evidence.engine == engine && EngineSelectionPolicy.hasCompleteEvidence(evidence)) {
            "Persisted benchmark report evidence is incomplete"
        }
        if (engine == Engine.NCNN) {
            check(
                evidence.executingBenchmarkApkSha256 ==
                    provenance.getString("benchmarkApkSha256"),
            ) { "NCNN execution APK does not match the benchmark APK in session provenance" }
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
        val startedElapsed = provenance.getLong("sessionStartedElapsedRealtimeMillis")
        val expiresElapsed = provenance.getLong("sessionExpiresElapsedRealtimeMillis")
        check(expiresElapsed - startedElapsed == SESSION_TTL_MILLIS) {
            "Benchmark gate session has an invalid monotonic TTL"
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
            "benchmarkSigningCertificateSha256",
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
        check(
            provenance.getString("agentHealthContract") == "agent-sdk-health-v1" &&
                provenance.getString("agentBuildId") ==
                "uavfire-agent-${provenance.getString("agentVersionName")}-${provenance.getLong("agentVersionCode")}",
        ) {
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

    private fun reportDigest(report: JSONObject): String {
        val copy = JSONObject(report.toString())
        copy.remove("reportDigest")
        return BenchmarkEvidenceDigest.sha256(copy)
    }

    private fun finalResultDigest(result: JSONObject): String {
        val copy = JSONObject(result.toString())
        copy.remove("finalResultDigest")
        return BenchmarkEvidenceDigest.sha256(copy)
    }

    private fun selectionInput(json: JSONObject): EngineBenchmark = EngineBenchmark(
        engine = Engine.valueOf(json.getString("engine").uppercase()),
        recall = json.getDouble("recall"),
        p95Millis = json.getDouble("p95InferenceMillis"),
        firstWindowP95Millis = json.getDouble("firstFiveMinuteP95Millis"),
        finalWindowP95Millis = json.getDouble("finalFiveMinuteP95Millis"),
        apkDeltaBytes = json.getLong("apkDeltaBytes"),
        stabilityDurationMillis = json.getLong("stabilityDurationMillis"),
        falsePositives = json.getInt("falsePositives"),
        inferenceSampleCount = json.getJSONArray("inferenceSamples").length(),
        firstWindowSampleCount = json.getInt("firstFiveMinuteSampleCount"),
        finalWindowSampleCount = json.getInt("finalFiveMinuteSampleCount"),
        agentHealthCheckCount = json.getInt("agentHealthCheckCount"),
        candidateApkSha256 = json.getString("candidateApkSha256"),
        runtimeSha256 = json.getJSONArray("runtimeEntries").let { entries ->
            buildMap {
                for (index in 0 until entries.length()) {
                    val entry = entries.getJSONObject(index)
                    put(entry.getString("path"), entry.getString("sha256"))
                }
            }
        },
        ncnnPackageVersion = json.optString("ncnnPackageVersion").takeIf(String::isNotBlank),
        ncnnPackageArchiveSha256 = json.optString("ncnnPackageArchiveSha256").takeIf(String::isNotBlank),
        ncnnBridgeSourceSha256 = json.optString("ncnnBridgeSourceSha256").takeIf(String::isNotBlank),
        ncnnBridgeSha256 = json.optString("ncnnBridgeSha256").takeIf(String::isNotBlank),
        executingBenchmarkApkSha256 = json.optString("executingBenchmarkApkSha256").takeIf(String::isNotBlank),
        executingNcnnRuntimeSha256 = json.optString("executingNcnnRuntimeSha256").takeIf(String::isNotBlank),
        executingNcnnBridgeSha256 = json.optString("executingNcnnBridgeSha256").takeIf(String::isNotBlank),
        reviewedNcnnBridgeSourceSha256 = json.optString("reviewedNcnnBridgeSourceSha256").takeIf(String::isNotBlank),
    )

    private fun valuesMatch(first: JSONObject, second: JSONObject, field: String): Boolean = when (field) {
        "agentVersionCode", "sessionStartedAtEpochMillis", "sessionExpiresAtEpochMillis",
        "sessionStartedElapsedRealtimeMillis", "sessionExpiresElapsedRealtimeMillis" ->
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
