package com.yinxin.uavfir.benchmark

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.os.Build
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FireDetectorBenchmarkInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val formalAgentTrust by lazy { FormalAgentTrust.load(context) }
    private val formalAgentHealthClient by lazy { FormalAgentHealthClient(context, formalAgentTrust) }

    @Test
    fun benchmarkAndSelectProductionEngine() {
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        // DJI 的 Android 11 连 run-as 都读不到外部应用目录;exportOnly 模式把结果
        // 镜像到内部 filesDir(run-as 可读),供宿主提取,几秒完成。
        if (arguments.getString("exportOnly") == "true") {
            val externalDir = context.getExternalFilesDir(null)
            val source = checkNotNull(File(externalDir, "fire-detector-benchmark.json").takeIf(File::isFile)) {
                "No completed formal visible-960 result is available"
            }
            val result = JSONObject(source.readText())
            BenchmarkPartialResults.validateFinalResult(
                result,
                System.currentTimeMillis(),
                bootId(),
                SystemClock.elapsedRealtime(),
            )
            source.copyTo(File(context.filesDir, source.name), overwrite = true)
            return
        }
        val engineArgument = arguments.getString("engine")
        val dryRun = arguments.getString("dryRun") == "true"
        val targets = if (engineArgument == null) Engine.values().toList()
            else listOf(Engine.valueOf(engineArgument.uppercase()))
        val sessionAction = arguments.getString("sessionAction")
        if (!dryRun && (engineArgument == null || sessionAction == SESSION_START)) {
            if (engineArgument != null) {
                check(targets.single() == Engine.ONNX) {
                    "A split gate session must start with the ONNX engine"
                }
            }
            clearPriorGateEvidence()
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
        // dryRun 只做预热+正确性评估并把召回写到 files/dry-run-<engine>.txt,
        // 不写浸泡成绩、不参与选型——纯诊断通道。
        val provenance = if (dryRun) {
            null
        } else if (engineArgument == null || sessionAction == SESSION_START) {
            val startedAt = System.currentTimeMillis()
            val startedElapsed = SystemClock.elapsedRealtime()
            BenchmarkPartialResults.beginSession(
                captureStaticProvenance(assets),
                nonce = UUID.randomUUID().toString(),
                bootId = bootId(),
                startedAtEpochMillis = startedAt,
                expiresAtEpochMillis = startedAt + BenchmarkPartialResults.SESSION_TTL_MILLIS,
                startedElapsedRealtimeMillis = startedElapsed,
                expiresElapsedRealtimeMillis = startedElapsed + BenchmarkPartialResults.SESSION_TTL_MILLIS,
            )
        } else {
            check(sessionAction == SESSION_CONTINUE) {
                "Split gate runs require -e sessionAction start for ONNX, then continue"
            }
            val partial = checkNotNull(partialFile().takeIf(File::exists)) {
                "No current gate session exists to continue"
            }
            val currentProvenance = JSONObject(partial.readText()).getJSONObject("provenance")
            BenchmarkPartialResults.requireFreshSession(
                currentProvenance,
                System.currentTimeMillis(),
                bootId(),
                SystemClock.elapsedRealtime(),
            )
            BenchmarkPartialResults.requireMatchingStaticProvenance(
                currentProvenance,
                captureStaticProvenance(assets),
            )
            currentProvenance
        }
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
            val report = runEngine(
                adapter,
                samples,
                apkDeltas.getValue(target),
                checkNotNull(provenance).getString("provenanceDigest"),
                if (target == Engine.NCNN) NcnnExecutionIdentityLoader.load(context) else null,
            )
            mergePartial(report, checkNotNull(provenance))
        }
        if (dryRun) return
        val partials = loadPartials(checkNotNull(provenance))
        if (partials.size < Engine.values().size) return
        val selected = EngineSelectionPolicy.select(
            pytorchMetrics.recall,
            partials.values.map(::selectionInput),
        )
        writeResult(pytorchMetrics, partials, selected?.engine, provenance)
        assertNotNull("No engine passed the immutable production gate", selected)
        assertEquals("NCNN is the approved production target", Engine.NCNN, selected?.engine)
    }

    private fun partialFile() = File(context.getExternalFilesDir(null), "fire-detector-benchmark-partial.json")

    private fun mergePartial(report: EngineReport, provenance: JSONObject) {
        BenchmarkPartialResults.requireFreshSession(
            provenance,
            System.currentTimeMillis(),
            bootId(),
            SystemClock.elapsedRealtime(),
        )
        val current = partialFile().takeIf(File::exists)?.let { JSONObject(it.readText()) }
        val sealed = BenchmarkPartialResults.sealReport(report.engine, provenance, report.toJson())
        val merged = BenchmarkPartialResults.merge(current, provenance, report.engine, sealed)
        val serialized = merged.toString(2)
        partialFile().writeText(serialized)
        // 双写内部存储:外部目录 adb/run-as 均不可读,且 gradle 跑完会随卸载被清。
        File(context.filesDir, partialFile().name).writeText(serialized)
    }

    private fun loadPartials(provenance: JSONObject): Map<String, JSONObject> {
        BenchmarkPartialResults.requireFreshSession(
            provenance,
            System.currentTimeMillis(),
            bootId(),
            SystemClock.elapsedRealtime(),
        )
        if (!partialFile().exists()) return emptyMap()
        val current = JSONObject(partialFile().readText())
        BenchmarkPartialResults.requireMatchingProvenance(current, provenance)
        val engines = current.getJSONObject("engines")
        return Engine.values().mapNotNull { engine ->
            val key = engine.name.lowercase()
            engines.optJSONObject(key)?.let { key to it }
        }.toMap()
    }

    private fun selectionInput(json: JSONObject) = EngineBenchmark(
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

    private fun runEngine(
        adapter: EngineAdapter,
        samples: List<BenchmarkSample>,
        apkDelta: MeasuredApkDelta,
        provenanceDigest: String,
        ncnnExecutionIdentity: NcnnExecutionIdentity?,
    ): EngineReport = adapter.use {
        var agentHealthCheckCount = 0
        requireFormalAgentHealthy()
        agentHealthCheckCount += 1
        repeat(BenchmarkRunContract.WARM_UP_FRAMES) { index -> adapter.infer(decode(samples[index % samples.size])) }
        val correctness = samples.map { sample -> sample to adapter.infer(decode(sample)) }
        val correctnessMetrics = CorrectnessEvaluator.evaluate(correctness)
        val observations = mutableListOf<InferenceObservation>()
        val firstWindow = mutableListOf<Double>()
        val finalWindow = mutableListOf<Double>()
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + BenchmarkRunContract.RUN_DURATION_MILLIS
        var nextAgentHealthCheckAt = startedAt + AGENT_HEALTH_CHECK_INTERVAL_MILLIS
        var sampleIndex = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            if (SystemClock.elapsedRealtime() >= nextAgentHealthCheckAt) {
                requireFormalAgentHealthy()
                agentHealthCheckCount += 1
                nextAgentHealthCheckAt += AGENT_HEALTH_CHECK_INTERVAL_MILLIS
            }
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
        val stabilityDurationMillis = SystemClock.elapsedRealtime() - startedAt
        check(stabilityDurationMillis >= BenchmarkRunContract.RUN_DURATION_MILLIS) {
            "Incomplete 30-minute stability evidence"
        }
        requireFormalAgentHealthy()
        agentHealthCheckCount += 1
        EngineReport(
            engine = adapter.engine,
            correctness = correctnessMetrics,
            p95Millis = percentile(observations.map(InferenceObservation::inferenceMillis)),
            firstWindowP95Millis = percentile(firstWindow),
            finalWindowP95Millis = percentile(finalWindow),
            apkDelta = apkDelta,
            stabilityDurationMillis = stabilityDurationMillis,
            firstWindowSampleCount = firstWindow.size,
            finalWindowSampleCount = finalWindow.size,
            agentHealthCheckCount = agentHealthCheckCount,
            provenanceDigest = provenanceDigest,
            ncnnExecutionIdentity = ncnnExecutionIdentity,
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
        provenance: JSONObject,
    ) {
        BenchmarkPartialResults.requireFreshSession(
            provenance,
            System.currentTimeMillis(),
            bootId(),
            SystemClock.elapsedRealtime(),
        )
        check(selectedEngine == Engine.NCNN) { "Only a passed formal NCNN gate can write a final result" }
        val result = BenchmarkPartialResults.sealFinalResult(
            provenance,
            pytorch.recall,
            pytorch.falsePositives,
            partials,
        )
        val serialized = result.toString(2)
        File(context.getExternalFilesDir(null), "fire-detector-benchmark.json").writeText(serialized)
        File(context.filesDir, "fire-detector-benchmark.json").writeText(serialized)
    }

    private fun captureStaticProvenance(assets: BenchmarkAssets): JSONObject {
        val packageInfo = context.packageManager.getPackageInfo(
            FORMAL_AGENT_PACKAGE,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA,
        )
        val agentApplication = checkNotNull(packageInfo.applicationInfo) { "Formal Agent application info is unavailable" }
        val agentApkSha256 = sha256(File(agentApplication.sourceDir))
        check(agentApkSha256 == formalAgentTrust.apkSha256) {
            "Installed Agent APK does not match the build-bound formal Agent APK"
        }
        val signers = checkNotNull(packageInfo.signingInfo) { "Formal Agent signing info is unavailable" }
            .apkContentsSigners
        check(signers.size == 1) { "Formal Agent APK must have one current signing certificate" }
        val signingCertificateSha256 = sha256(signers.single().toByteArray())
        check(signingCertificateSha256 == formalAgentTrust.signingCertificateSha256) {
            "Installed Agent signing certificate does not match the build-bound formal Agent APK"
        }
        check(
            packageInfo.versionName == formalAgentTrust.versionName &&
                packageInfo.longVersionCode == formalAgentTrust.versionCode,
        ) { "Installed Agent version does not match the repository-approved release" }
        val benchmarkPackage = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val benchmarkSigners = checkNotNull(benchmarkPackage.signingInfo).apkContentsSigners
        check(benchmarkSigners.size == 1) { "Benchmark APK must have one current signing certificate" }
        val benchmarkSigningCertificateSha256 = sha256(benchmarkSigners.single().toByteArray())
        check(benchmarkSigningCertificateSha256 == signingCertificateSha256) {
            "Benchmark and Formal Agent must share the repository-approved signer"
        }
        val health = requireFormalAgentHealthy()
        val androidId = checkNotNull(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID),
        ) { "Stable Android device ID is unavailable" }
        check(androidId.isNotBlank()) { "Stable Android device ID is unavailable" }
        val instrumentationContext = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .context
        return JSONObject()
            .put("modelManifestSha256", assets.modelManifest.sha256)
            .put("benchmarkManifestSha256", assets.benchmarkManifestSha256)
            .put("pytorchBaselineSha256", assets.pytorchBaselineSha256)
            .put("deviceFingerprint", Build.FINGERPRINT)
            .put("deviceIdSha256", sha256(androidId.toByteArray()))
            .put("benchmarkApkSha256", sha256(File(context.applicationInfo.sourceDir)))
            .put("benchmarkSigningCertificateSha256", benchmarkSigningCertificateSha256)
            .put("instrumentationApkSha256", sha256(File(instrumentationContext.applicationInfo.sourceDir)))
            .put("agentPackage", FORMAL_AGENT_PACKAGE)
            .put("agentVersionName", formalAgentTrust.versionName)
            .put("agentVersionCode", packageInfo.longVersionCode)
            .put("agentApkSha256", agentApkSha256)
            .put("agentSigningCertificateSha256", signingCertificateSha256)
            .put("agentRealUxsdk", true)
            .put("agentHealthContract", "agent-sdk-health-v1")
            .put("agentBuildId", health.buildId)
            .put("agentRunning", true)
    }

    private fun clearPriorGateEvidence() {
        for (directory in listOfNotNull(context.getExternalFilesDir(null), context.filesDir)) {
            for (name in listOf("fire-detector-benchmark.json", "fire-detector-benchmark-partial.json")) {
                check(!File(directory, name).exists() || File(directory, name).delete()) {
                    "Unable to clear prior gate evidence: ${File(directory, name)}"
                }
            }
        }
    }

    private fun bootId(): String {
        val id = File("/proc/sys/kernel/random/boot_id").readText().trim()
        check(id.isNotBlank()) { "Device boot identity is unavailable" }
        return id
    }

    private fun requireFormalAgentHealthy(): FormalAgentHealth {
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        val visibleToActivityManager = activityManager.runningAppProcesses
            ?.any { it.processName == FORMAL_AGENT_PACKAGE }
            ?: false
        val visibleToShell = runCatching {
            val descriptor = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .executeShellCommand("pidof $FORMAL_AGENT_PACKAGE")
            descriptor.use {
                FileInputStream(it.fileDescriptor).bufferedReader().use { output ->
                    output.readText().trim().isNotBlank()
                }
            }
        }.getOrDefault(false)
        check(visibleToActivityManager || visibleToShell) { "Formal Agent process is not running" }
        return formalAgentHealthClient.requireHealthy()
    }

    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

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
        val apkDelta: MeasuredApkDelta,
        val stabilityDurationMillis: Long,
        val firstWindowSampleCount: Int,
        val finalWindowSampleCount: Int,
        val agentHealthCheckCount: Int,
        val provenanceDigest: String,
        val ncnnExecutionIdentity: NcnnExecutionIdentity?,
        val observations: List<InferenceObservation>,
    ) {
        fun toJson() = JSONObject()
            .put("engine", engine.name.lowercase())
            .put("recall", correctness.recall)
            .put("falsePositives", correctness.falsePositives)
            .put("p95InferenceMillis", p95Millis)
            .put("firstFiveMinuteP95Millis", firstWindowP95Millis)
            .put("finalFiveMinuteP95Millis", finalWindowP95Millis)
            .put("apkDeltaBytes", apkDelta.apkDeltaBytes)
            .put("candidateApkSha256", apkDelta.candidateApkSha256)
            .put(
                "runtimeEntries",
                JSONArray(apkDelta.runtimeEntries.map { (path, sha256) ->
                    JSONObject().put("path", path).put("sha256", sha256)
                }),
            )
            .put("stabilityDurationMillis", stabilityDurationMillis)
            .put("firstFiveMinuteSampleCount", firstWindowSampleCount)
            .put("finalFiveMinuteSampleCount", finalWindowSampleCount)
            .put("agentHealthCheckCount", agentHealthCheckCount)
            .put("provenanceDigest", provenanceDigest)
            .put("inferenceSamples", JSONArray(observations.map(InferenceObservation::toJson)))
            .also { report ->
                apkDelta.ncnnPackageVersion?.let { report.put("ncnnPackageVersion", it) }
                apkDelta.ncnnPackageArchiveSha256?.let { report.put("ncnnPackageArchiveSha256", it) }
                apkDelta.ncnnBridgeSourceSha256?.let { report.put("ncnnBridgeSourceSha256", it) }
                apkDelta.ncnnBridgeSha256?.let { report.put("ncnnBridgeSha256", it) }
                ncnnExecutionIdentity?.let {
                    report.put("executingBenchmarkApkSha256", it.executingBenchmarkApkSha256)
                    report.put("executingNcnnRuntimeSha256", it.executingRuntimeSha256)
                    report.put("executingNcnnBridgeSha256", it.executingBridgeSha256)
                    report.put("reviewedNcnnBridgeSourceSha256", it.reviewedBridgeSourceSha256)
                }
            }
    }

    private companion object {
        const val FORMAL_AGENT_PACKAGE = "com.yinxin.uavfir"
        const val AGENT_HEALTH_CHECK_INTERVAL_MILLIS = 30_000L
        const val SESSION_START = "start"
        const val SESSION_CONTINUE = "continue"
    }
}
