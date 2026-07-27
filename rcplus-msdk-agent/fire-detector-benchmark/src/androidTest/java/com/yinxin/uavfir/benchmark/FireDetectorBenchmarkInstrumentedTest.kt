package com.yinxin.uavfir.benchmark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FireDetectorBenchmarkInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun benchmarkAndSelectProductionEngine() {
        val assets = BenchmarkAssets(context)
        assets.verifyIntegrity()
        val samples = assets.samples()
        BenchmarkRunContract.validateSampleCount(samples.size)
        val apkDeltas = ApkDeltaMetadata.load(context, assets.modelManifest)

        val pytorchMetrics = CorrectnessEvaluator.evaluate(
            samples.map { sample -> sample to assets.pytorchDetections(sample) },
        )
        val adapters = listOf(
            OnnxEngineAdapter(context, assets.modelManifest),
            TfliteEngineAdapter(context, assets.modelManifest),
            NcnnEngineAdapter(context, assets.modelManifest),
        )
        val reports = adapters.map { adapter -> runEngine(adapter, samples, apkDeltas.getValue(adapter.engine).apkDeltaBytes) }
        val selected = EngineSelectionPolicy.select(pytorchMetrics.recall, reports.map(EngineReport::selectionInput))
        writeResult(pytorchMetrics, reports, selected?.engine)
        assertNotNull("No engine passed the immutable production gate", selected)
    }

    private fun runEngine(adapter: EngineAdapter, samples: List<BenchmarkSample>, apkDeltaBytes: Long): EngineReport = adapter.use {
        repeat(BenchmarkRunContract.WARM_UP_FRAMES) { index -> adapter.infer(decode(samples[index % samples.size])) }
        val correctness = samples.map { sample -> sample to adapter.infer(decode(sample)) }
        val correctnessMetrics = CorrectnessEvaluator.evaluate(correctness)
        val observations = mutableListOf<InferenceObservation>()
        val firstWindow = mutableListOf<Double>()
        val finalWindow = mutableListOf<Double>()
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + BenchmarkRunContract.RUN_DURATION_MILLIS
        var sampleIndex = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val frame = decode(samples[sampleIndex % samples.size])
            val inferenceStartedAt = SystemClock.elapsedRealtime()
            adapter.infer(frame)
            val inferenceEndedAt = SystemClock.elapsedRealtime()
            val inferenceMillis = (inferenceEndedAt - inferenceStartedAt).toDouble()
            val elapsed = inferenceEndedAt - startedAt
            if (elapsed <= BenchmarkRunContract.WINDOW_MILLIS) firstWindow += inferenceMillis
            if (elapsed >= BenchmarkRunContract.RUN_DURATION_MILLIS - BenchmarkRunContract.WINDOW_MILLIS) finalWindow += inferenceMillis
            observations += InferenceObservation(
                inferenceMillis = inferenceMillis,
                sourceFrameAgeMillis = inferenceEndedAt - frame.capturedAtMs,
                rssKilobytes = DeviceMetrics.rssKilobytes(),
                temperatureCelsius = DeviceMetrics.temperatureCelsius(context),
            )
            sampleIndex += 1
        }
        check(firstWindow.isNotEmpty() && finalWindow.isNotEmpty()) { "Incomplete 30-minute stability windows" }
        EngineReport(
            engine = adapter.engine,
            correctness = correctnessMetrics,
            p95Millis = percentile(observations.map(InferenceObservation::inferenceMillis)),
            firstWindowP95Millis = percentile(firstWindow),
            finalWindowP95Millis = percentile(finalWindow),
            apkDeltaBytes = apkDeltaBytes,
            observations = observations,
        )
    }

    private fun decode(sample: BenchmarkSample): RgbaFrame {
        val bitmap = assetsOpen(sample.imageAsset).use(BitmapFactory::decodeStream)
            ?: error("Unable to decode ${sample.imageAsset}")
        val rgba = ByteArray(bitmap.width * bitmap.height * 4)
        val width = bitmap.width
        val height = bitmap.height
        bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(rgba))
        bitmap.recycle()
        return RgbaFrame(rgba, width, height, SystemClock.elapsedRealtime())
    }

    private fun assetsOpen(asset: String) = context.assets.open(asset)

    private fun writeResult(
        pytorch: CorrectnessMetrics,
        reports: List<EngineReport>,
        selectedEngine: Engine?,
    ) {
        val result = JSONObject()
            .put("pytorchRecall", pytorch.recall)
            .put("pytorchFalsePositives", pytorch.falsePositives)
            .put("selectedEngine", selectedEngine?.name?.lowercase())
            .put("engines", JSONArray(reports.map(EngineReport::toJson)))
        val output = File(context.getExternalFilesDir(null), "fire-detector-benchmark.json")
        output.writeText(result.toString(2))
    }

    private fun percentile(values: List<Double>): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * 0.95).toInt()]
    }

    private data class InferenceObservation(
        val inferenceMillis: Double,
        val sourceFrameAgeMillis: Long,
        val rssKilobytes: Long,
        val temperatureCelsius: Double?,
    ) {
        fun toJson() = JSONObject()
            .put("inferenceMillis", inferenceMillis)
            .put("sourceFrameAgeMillis", sourceFrameAgeMillis)
            .put("rssKilobytes", rssKilobytes)
            .put("temperatureCelsius", temperatureCelsius)
    }

    private data class EngineReport(
        val engine: Engine,
        val correctness: CorrectnessMetrics,
        val p95Millis: Double,
        val firstWindowP95Millis: Double,
        val finalWindowP95Millis: Double,
        val apkDeltaBytes: Long,
        val observations: List<InferenceObservation>,
    ) {
        fun selectionInput() = EngineBenchmark(
            engine = engine,
            recall = correctness.recall,
            p95Millis = p95Millis,
            firstWindowP95Millis = firstWindowP95Millis,
            finalWindowP95Millis = finalWindowP95Millis,
            apkDeltaBytes = apkDeltaBytes,
        )

        fun toJson() = JSONObject()
            .put("engine", engine.name.lowercase())
            .put("recall", correctness.recall)
            .put("falsePositives", correctness.falsePositives)
            .put("p95InferenceMillis", p95Millis)
            .put("firstFiveMinuteP95Millis", firstWindowP95Millis)
            .put("finalFiveMinuteP95Millis", finalWindowP95Millis)
            .put("apkDeltaBytes", apkDeltaBytes)
            .put("inferenceSamples", JSONArray(observations.map(InferenceObservation::toJson)))
    }
}
