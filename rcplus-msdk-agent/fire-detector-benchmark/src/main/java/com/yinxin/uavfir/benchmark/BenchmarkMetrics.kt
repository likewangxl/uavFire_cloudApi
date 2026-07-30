package com.yinxin.uavfir.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

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
                    .filter { !matched[it] && sample.expected[it].classIndex == detection.classIndex }
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
    fun rssKilobytes(): Long {
        val line = File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") } }
            ?: error("VmRSS is unavailable")
        return line.substringAfter(':').trim().substringBefore(' ').toLong()
    }

    fun temperatureCelsius(context: Context): Double? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val deciCelsius = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return deciCelsius.takeUnless { it == Int.MIN_VALUE }?.div(10.0)
    }
}

internal object BaselineNormalizer {
    fun normalize(xyxy: FloatArray, sourceWidth: Int, sourceHeight: Int, confidence: Float, classIndex: Int): Detection {
        require(xyxy.size == 4 && sourceWidth > 0 && sourceHeight > 0)
        return Detection(
            left = (xyxy[0] / sourceWidth).coerceIn(0f, 1f),
            top = (xyxy[1] / sourceHeight).coerceIn(0f, 1f),
            right = (xyxy[2] / sourceWidth).coerceIn(0f, 1f),
            bottom = (xyxy[3] / sourceHeight).coerceIn(0f, 1f),
            confidence = confidence,
            classIndex = classIndex,
        )
    }
}
