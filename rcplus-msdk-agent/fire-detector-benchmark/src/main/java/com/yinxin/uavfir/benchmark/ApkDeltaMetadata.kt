package com.yinxin.uavfir.benchmark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal const val APK_DELTA_ABI = "arm64-v8a"

internal data class MeasuredApkDelta(val apkDeltaBytes: Long, val apkSha256: String)

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
            check(apkSha256.matches(Regex("[0-9a-f]{64}"))) { "Candidate APK provenance lacks SHA-256" }
            val modelEntries = provenance.getJSONArray("modelEntries").modelEntries()
            check(modelEntries == manifest.artifact(engine).associate { it.path to it.sha256 }) { "Candidate model entries do not match the current manifest" }
            val runtimes = provenance.getJSONArray("runtimeEntries").strings().toSet()
            check(runtimes == expectedRuntimeEntries(engine)) { "Candidate runtime entries are mislabeled" }
            MeasuredApkDelta(delta, apkSha256)
        }
    }

    fun expectedRuntimeEntries(engine: Engine): Set<String> = when (engine) {
        Engine.ONNX -> setOf("lib/arm64-v8a/libonnxruntime4j_jni.so")
        Engine.TFLITE -> setOf("lib/arm64-v8a/libtensorflowlite_jni.so")
        Engine.NCNN -> setOf("lib/arm64-v8a/libncnn.so", "lib/arm64-v8a/libfire_detector_ncnn.so")
    }
}

private fun JSONArray.strings(): List<String> = buildList { for (index in 0 until length()) add(getString(index)) }
private fun JSONArray.modelEntries(): Map<String, String> = buildMap {
    for (index in 0 until length()) {
        val item = getJSONObject(index)
        put(item.getString("path"), item.getString("sha256"))
    }
}
