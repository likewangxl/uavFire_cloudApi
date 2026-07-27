package com.yinxin.uavfir.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import java.util.zip.ZipFile

internal data class CorrectnessMetrics(
    val recall: Double,
    val falsePositives: Int,
)

internal object CorrectnessEvaluator {
    fun evaluate(samples: List<Pair<BenchmarkSample, List<Detection>>>): CorrectnessMetrics {
        var expectedCount = 0
        var matchedExpected = 0
        var falsePositives = 0
        for ((sample, detections) in samples) {
            expectedCount += sample.expected.size
            val matched = BooleanArray(sample.expected.size)
            for (detection in detections) {
                val matchIndex = sample.expected.indices
                    .filter { !matched[it] }
                    .maxByOrNull { YoloPostprocessor.intersectionOverUnion(detection, sample.expected[it]) }
                if (matchIndex != null && YoloPostprocessor.intersectionOverUnion(detection, sample.expected[matchIndex]) >= 0.5f) {
                    matched[matchIndex] = true
                    matchedExpected += 1
                } else {
                    falsePositives += 1
                }
            }
        }
        return CorrectnessMetrics(
            recall = if (expectedCount == 0) 0.0 else matchedExpected.toDouble() / expectedCount,
            falsePositives = falsePositives,
        )
    }
}

internal object DeviceMetrics {
    fun rssKilobytes(): Long = Debug.getPss()

    fun temperatureCelsius(context: Context): Double? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val deciCelsius = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return deciCelsius.takeUnless { it == Int.MIN_VALUE }?.div(10.0)
    }
}

internal object ApkDeltaCalculator {
    fun bytesFor(context: Context, engine: Engine, modelAssets: List<String>): Long {
        val nativeName = when (engine) {
            Engine.ONNX -> "onnxruntime"
            Engine.TFLITE -> "tensorflowlite"
            Engine.NCNN -> "ncnn"
        }
        val nativeBytes = ZipFile(context.applicationInfo.sourceDir).use { apk ->
            apk.entries().asSequence()
                .filter { it.name.startsWith("lib/") && it.name.contains(nativeName, ignoreCase = true) }
                .sumOf { it.size.coerceAtLeast(0L) }
        }
        val modelBytes = modelAssets.sumOf { asset -> context.assets.openFd(asset).use { it.length } }
        return nativeBytes + modelBytes
    }
}
