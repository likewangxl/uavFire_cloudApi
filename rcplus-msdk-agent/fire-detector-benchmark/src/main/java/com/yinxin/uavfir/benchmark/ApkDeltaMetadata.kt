package com.yinxin.uavfir.benchmark

import android.content.Context
import org.json.JSONObject

internal const val APK_DELTA_ABI = "arm64-v8a"

/** Candidate-specific whole-APK deltas generated from arm64-only measurement builds. */
internal object ApkDeltaMetadata {
    fun load(context: Context): Map<Engine, Long> {
        val root = runCatching {
            context.assets.open("apk-delta.json").bufferedReader().use { JSONObject(it.readText()) }
        }.getOrElse { error("Missing generated apk-delta.json; run :fire-detector-benchmark:writeApkDeltaMetadata first") }
        check(root.getString("abi") == APK_DELTA_ABI) { "APK delta metadata must be arm64-v8a" }
        val baseline = root.getLong("baselineApkBytes")
        check(baseline > 0L) { "APK delta metadata has no runtime-free baseline" }
        return Engine.entries.associateWith { engine ->
            val entry = root.getJSONObject("candidates").getJSONObject(engine.name.lowercase())
            check(entry.getLong("apkBytes") >= baseline) { "Candidate APK is smaller than the baseline" }
            val delta = entry.getLong("apkDeltaBytes")
            check(delta == entry.getLong("apkBytes") - baseline) { "APK delta metadata is not reproducible" }
            delta
        }
    }
}
