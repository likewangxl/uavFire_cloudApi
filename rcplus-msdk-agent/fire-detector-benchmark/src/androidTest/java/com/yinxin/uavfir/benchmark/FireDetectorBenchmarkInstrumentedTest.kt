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
        // DJI 的 Android 11 连 run-as 都读不到外部应用目录;exportOnly 模式把结果
        // 镜像到内部 filesDir(run-as 可读),供宿主提取,几秒完成。
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("exportOnly") == "true") {
            val externalDir = context.getExternalFilesDir(null)
            val listing = buildString {
                append("externalDir=").append(externalDir?.absolutePath).append('\n')
                externalDir?.listFiles()?.forEach { append(it.name).append(' ').append(it.length()).append('\n') }
            }
            File(context.filesDir, "export-log.txt").writeText(listing)
            for (name in listOf("fire-detector-benchmark.json", "fire-detector-benchmark-partial.json")) {
                val source = File(externalDir, name)
                if (source.exists()) source.copyTo(File(context.filesDir, name), overwrite = true)
            }
            return
        }
        val assets = BenchmarkAssets(context)
        assets.verifyIntegrity()
        val samples = assets.samples()
        BenchmarkRunContract.validateSampleCount(samples.size)
        val apkDeltas = ApkDeltaMetadata.load(context, assets.modelManifest)

        val pytorchMetrics = CorrectnessEvaluator.evaluate(
            samples.map { sample -> sample to assets.pytorchDetections(sample) },
        )
        // 三引擎背靠背 30 分钟满载浸泡会让 RC Plus 2 热重启;支持 -e engine 单引擎运行,
        // 每个引擎完成即落盘,三份齐了才计算选型。门槛本身不变。
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val engineArgument = arguments.getString("engine")
        // dryRun 只做预热+正确性评估并把召回写到 files/dry-run-<engine>.txt,
        // 不写浸泡成绩、不参与选型——纯诊断通道。
        val dryRun = arguments.getString("dryRun") == "true"
        val targets = if (engineArgument == null) Engine.values().toList()
            else listOf(Engine.valueOf(engineArgument.uppercase()))
        if (engineArgument == null && !dryRun) partialFile().delete()
        for (target in targets) {
            val adapter = when (target) {
                Engine.ONNX -> OnnxEngineAdapter(context, assets.modelManifest)
                Engine.TFLITE -> TfliteEngineAdapter(context, assets.modelManifest)
                Engine.NCNN -> NcnnEngineAdapter(context, assets.modelManifest)
            }
            if (dryRun) {
                val metrics = adapter.use {
                    repeat(BenchmarkRunContract.WARM_UP_FRAMES) { index -> adapter.infer(decode(samples[index % samples.size])) }
                    CorrectnessEvaluator.evaluate(samples.map { sample -> sample to adapter.infer(decode(sample)) })
                }
                File(context.filesDir, "dry-run-${target.name.lowercase()}.txt")
                    .writeText("recall=${metrics.recall} falsePositives=${metrics.falsePositives} pytorchRecall=${pytorchMetrics.recall}")
                continue
            }
            val report = runEngine(adapter, samples, apkDeltas.getValue(target).apkDeltaBytes)
            mergePartial(report)
        }
        if (dryRun) return
        val partials = loadPartials()
        if (partials.size < Engine.values().size) return
        val selected = EngineSelectionPolicy.select(
            pytorchMetrics.recall,
            partials.values.map(::selectionInput),
        )
        writeResult(pytorchMetrics, partials, selected?.engine)
        assertNotNull("No engine passed the immutable production gate", selected)
    }

    private fun partialFile() = File(context.getExternalFilesDir(null), "fire-detector-benchmark-partial.json")

    private fun mergePartial(report: EngineReport) {
        val current = if (partialFile().exists()) JSONObject(partialFile().readText()) else JSONObject()
        current.put(report.engine.name.lowercase(), report.toJson())
        val serialized = current.toString(2)
        partialFile().writeText(serialized)
        // 双写内部存储:外部目录 adb/run-as 均不可读,且 gradle 跑完会随卸载被清。
        File(context.filesDir, partialFile().name).writeText(serialized)
    }

    private fun loadPartials(): Map<String, JSONObject> {
        if (!partialFile().exists()) return emptyMap()
        val current = JSONObject(partialFile().readText())
        return Engine.values().mapNotNull { engine ->
            val key = engine.name.lowercase()
            current.optJSONObject(key)?.let { key to it }
        }.toMap()
    }

    private fun selectionInput(json: JSONObject) = EngineBenchmark(
        engine = Engine.valueOf(json.getString("engine").uppercase()),
        recall = json.getDouble("recall"),
        p95Millis = json.getDouble("p95InferenceMillis"),
        firstWindowP95Millis = json.getDouble("firstFiveMinuteP95Millis"),
        finalWindowP95Millis = json.getDouble("finalFiveMinuteP95Millis"),
        apkDeltaBytes = json.getLong("apkDeltaBytes"),
    )

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
        partials: Map<String, JSONObject>,
        selectedEngine: Engine?,
    ) {
        val result = JSONObject()
            .put("pytorchRecall", pytorch.recall)
            .put("pytorchFalsePositives", pytorch.falsePositives)
            .put("selectedEngine", selectedEngine?.name?.lowercase())
            .put("engines", JSONArray(Engine.values().map { partials.getValue(it.name.lowercase()) }))
        val serialized = result.toString(2)
        File(context.getExternalFilesDir(null), "fire-detector-benchmark.json").writeText(serialized)
        File(context.filesDir, "fire-detector-benchmark.json").writeText(serialized)
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
