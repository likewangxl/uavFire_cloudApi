package com.yinxin.uavfir.benchmark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal const val APK_DELTA_ABI = "arm64-v8a"
internal const val APPROVED_NCNN_VERSION = "20260526"
internal const val APPROVED_NCNN_ARCHIVE_SHA256 =
    "eb205b332274974511890903828451ae7a4c19c309f21431536e0a8c9f3dd0c1"

internal data class MeasuredApkDelta(
    val apkDeltaBytes: Long,
    val candidateApkSha256: String,
    val runtimeEntries: Map<String, String>,
    val ncnnPackageVersion: String? = null,
    val ncnnPackageArchiveSha256: String? = null,
    val ncnnBridgeSourceSha256: String? = null,
    val ncnnBridgeSha256: String? = null,
)

/** Immutable provenance from separately built, production-like arm64 measurement APKs. */
internal object ApkDeltaMetadata {
    fun load(context: Context, manifest: ModelManifest): Map<Engine, MeasuredApkDelta> = parse(
        json = context.assets.open("apk-delta.json").bufferedReader().use { it.readText() },
        manifest = manifest,
    )

    fun parse(json: String, manifest: ModelManifest): Map<Engine, MeasuredApkDelta> {
        val root = JSONObject(json)
        check(root.getString("abi") == APK_DELTA_ABI) { "APK delta metadata must be arm64-v8a" }
        check(root.getString("modelCandidatesSha256") == manifest.sha256) { "APK delta metadata is stale for the current model manifest" }
        val baseline = root.getLong("baselineApkBytes")
        check(baseline > 0L) { "APK delta metadata has no runtime-free baseline" }
        return Engine.entries.associateWith { engine ->
            val entry = root.getJSONObject("candidates").getJSONObject(engine.name.lowercase())
            check(entry.getLong("apkBytes") >= baseline) { "Candidate APK is smaller than the baseline" }
            val delta = entry.getLong("apkDeltaBytes")
            check(delta == entry.getLong("apkBytes") - baseline) { "APK delta metadata is not reproducible" }
            val provenance = entry.getJSONObject("provenance")
            val apkSha256 = provenance.getString("apkSha256")
            check(apkSha256.matches(SHA256)) { "Candidate APK provenance lacks SHA-256" }
            val modelEntries = provenance.getJSONArray("modelEntries").modelEntries()
            check(modelEntries == manifest.artifact(engine).associate { it.path to it.sha256 }) { "Candidate model entries do not match the current manifest" }
            val runtimes = provenance.getJSONArray("runtimeEntries").hashedEntries()
            check(runtimes.keys == expectedRuntimeEntries(engine)) { "Candidate runtime entries are mislabeled" }
            check(runtimes.values.all { it.matches(SHA256) }) { "Candidate runtime provenance lacks SHA-256" }
            if (engine == Engine.NCNN) {
                val ncnnBuild = provenance.getJSONObject("ncnnBuild")
                val version = ncnnBuild.getString("version")
                val archiveSha256 = ncnnBuild.getString("packageArchiveSha256")
                val bridgeSourceSha256 = ncnnBuild.getString("bridgeSourceSha256")
                val runtimeSha256 = ncnnBuild.getString("runtimeSha256")
                val bridgeSha256 = ncnnBuild.getString("bridgeSha256")
                check(
                    version == APPROVED_NCNN_VERSION &&
                        archiveSha256 == APPROVED_NCNN_ARCHIVE_SHA256,
                ) { "NCNN package version/archive hash is not approved" }
                check(listOf(archiveSha256, bridgeSourceSha256, runtimeSha256, bridgeSha256).all { it.matches(SHA256) }) {
                    "NCNN build identity is incomplete"
                }
                check(runtimeSha256 == runtimes.getValue("lib/arm64-v8a/libncnn.so")) {
                    "NCNN runtime hash does not match the candidate APK"
                }
                check(bridgeSha256 == runtimes.getValue("lib/arm64-v8a/libfire_detector_ncnn.so")) {
                    "NCNN bridge hash does not match the candidate APK"
                }
                MeasuredApkDelta(
                    delta,
                    apkSha256,
                    runtimes,
                    ncnnPackageVersion = version,
                    ncnnPackageArchiveSha256 = archiveSha256,
                    ncnnBridgeSourceSha256 = bridgeSourceSha256,
                    ncnnBridgeSha256 = bridgeSha256,
                )
            } else {
                check(!provenance.has("ncnnBuild")) { "Non-NCNN candidate cannot claim NCNN build identity" }
                MeasuredApkDelta(delta, apkSha256, runtimes)
            }
        }
    }

    fun expectedRuntimeEntries(engine: Engine): Set<String> = when (engine) {
        Engine.ONNX -> setOf("lib/arm64-v8a/libonnxruntime4j_jni.so")
        Engine.TFLITE -> setOf("lib/arm64-v8a/libtensorflowlite_jni.so")
        Engine.NCNN -> setOf("lib/arm64-v8a/libncnn.so", "lib/arm64-v8a/libfire_detector_ncnn.so")
    }
}

private val SHA256 = Regex("[0-9a-f]{64}")

private fun JSONArray.hashedEntries(): Map<String, String> = buildMap {
    for (index in 0 until length()) {
        val item = getJSONObject(index)
        check(put(item.getString("path"), item.getString("sha256")) == null) {
            "Duplicate hashed APK entry"
        }
    }
}
private fun JSONArray.modelEntries(): Map<String, String> = buildMap {
    for (index in 0 until length()) {
        val item = getJSONObject(index)
        put(item.getString("path"), item.getString("sha256"))
    }
}
