package com.yinxin.uavfir.api

import android.util.Log
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.session.DualStreamSessionState
import com.yinxin.uavfir.stream.ThermalMeasuredPoint
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class ThermalHotspotMonitor(
    private val client: AgentBackendClient,
    private val sessionManager: DualStreamSessionManager,
    private val snapshotUploader: ThermalSnapshotUploader = AiServiceThermalSnapshotUploader(),
    private val visibleSnapshotConfirmer: VisibleSnapshotConfirmer = AiServiceVisibleSnapshotConfirmer(),
    private val visibleConfirmationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val monitorScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val taskIdFactory: (String) -> String = { droneSn -> "fire-$droneSn" },
    private val reportThresholdC: Double = DEFAULT_REPORT_THRESHOLD_C,
    private val probeIntervalMs: Long = DEFAULT_PROBE_INTERVAL_MS,
    private val frameTriggerDebounceMs: Long = DEFAULT_FRAME_TRIGGER_DEBOUNCE_MS,
    private val dwellConfirmer: ThermalDwellConfirmer = ThermalDwellConfirmer(
        sessionManager = sessionManager,
        missionHold = NoopMissionHoldControl,
    ),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : CommandPoller {
    private val probeMutex = Mutex()
    @Volatile
    private var lastProbeAtMs: Long = 0L
    @Volatile
    private var lastFrameTriggerCompletedAtMs: Long = 0L
    private var lastReportedRegion: ThermalMeasureRegion? = null

    fun onFrameHotspotCandidate(droneSn: String): Job = monitorScope.launch {
        runFrameTriggeredProbe(droneSn)
    }

    override suspend fun pollOnce(droneSn: String) {
        if (!probeMutex.tryLock()) {
            return
        }
        try {
            pollOnceLocked(droneSn)
        } finally {
            probeMutex.unlock()
        }
    }

    private suspend fun pollOnceLocked(droneSn: String) {
        if (sessionManager.sessionState != DualStreamSessionState.RUNNING) {
            return
        }
        // 仅在火情监测开启时才探测热区。否则探测会周期性 focusThermal 把共享流切到红外，
        // 即便用户没开监测——见 DualStreamSessionManager.thermalMonitoringEnabled。
        if (!sessionManager.thermalMonitoringEnabled) {
            return
        }
        val now = clockMs()
        if (lastProbeAtMs > 0L && now - lastProbeAtMs < probeIntervalMs) {
            return
        }
        lastProbeAtMs = now

        measureAndReportHotspot(droneSn, now)
    }

    private suspend fun runFrameTriggeredProbe(droneSn: String) {
        if (sessionManager.sessionState != DualStreamSessionState.RUNNING) {
            return
        }
        if (!sessionManager.thermalMonitoringEnabled) {
            return
        }
        if (!probeMutex.tryLock()) {
            return
        }
        var processed = false
        try {
            val now = clockMs()
            if (lastFrameTriggerCompletedAtMs > 0L && now - lastFrameTriggerCompletedAtMs < frameTriggerDebounceMs) {
                return
            }
            processed = true
            measureAndReportHotspot(droneSn, now)
        } finally {
            if (processed) {
                lastFrameTriggerCompletedAtMs = clockMs()
            }
            probeMutex.unlock()
        }
    }

    private suspend fun measureAndReportHotspot(droneSn: String, now: Long) {
        val result = sessionManager.measureThermalHotspot(
            droneSn = droneSn,
            seedRegion = lastReportedRegion,
        )
        if (!result.status.equals("applied", ignoreCase = true)) {
            Log.w(TAG, "thermal hotspot probe failed drone=$droneSn message=${result.message}")
            return
        }

        val temperature = result.thermalCenterTemperatureC ?: return
        val region = result.thermalMeasureRegion ?: return
        val measurements = result.thermalMeasurements
        if (temperature < reportThresholdC) {
            return
        }

        val dwellResult = dwellConfirmer.confirm(
            droneSn = droneSn,
            thresholdC = reportThresholdC,
            firstSample = DwellSample(
                temperatureC = temperature,
                region = region,
                atMs = now,
            ),
            seedRegion = region,
        )
        val sampleTemperatures = dwellResult.samples.joinToString(prefix = "[", postfix = "]") {
            "%.1f".format(it.temperatureC)
        }
        if (dwellResult.degraded) {
            warn(
                "dwell-degraded drone=$droneSn samples=$sampleTemperatures " +
                    "min=${dwellResult.minC} max=${dwellResult.maxC} spread=${dwellResult.spreadC}",
            )
        } else {
            info(
                "dwell result drone=$droneSn confirmed=${dwellResult.confirmed} " +
                    "samples=$sampleTemperatures min=${dwellResult.minC} " +
                    "max=${dwellResult.maxC} spread=${dwellResult.spreadC}",
            )
        }

        if (!dwellResult.confirmed && !dwellResult.degraded) {
            info("dwell rejected drone=$droneSn samples=$sampleTemperatures")
            return
        }

        val reportSample = dwellResult.bestSample ?: DwellSample(temperature, region, now)
        val reportTemperature = reportSample.temperatureC
        val reportRegion = reportSample.region
        val taskId = taskIdFactory(droneSn)
        val eventId = "$taskId-$now"
        val thermalImageUrl = uploadThermalSnapshotOnceIfPresent(eventId, result.thermalSnapshotPath)
        lastReportedRegion = reportRegion
        recordThermalHotspotEvent(
            taskId = taskId,
            droneSn = droneSn,
            sourceTs = now,
            temperatureC = reportTemperature,
            thermalMeasureRoi = reportRegion.toApiMap(),
            thermalMeasurements = measurements,
            thermalImageUrl = thermalImageUrl,
        )
        if (thermalImageUrl.isNullOrBlank()) {
            launchThermalSnapshotRetry(
                taskId = taskId,
                eventId = eventId,
                droneSn = droneSn,
                sourceTs = now,
                seedRegion = reportRegion,
                temperatureC = reportTemperature,
                thermalMeasurements = measurements,
                initialSnapshotPath = result.thermalSnapshotPath,
            )
        } else {
            launchVisibleSnapshotConfirmation(
                taskId = taskId,
                eventId = eventId,
                droneSn = droneSn,
                sourceTs = now,
                thermalImageUrl = thermalImageUrl,
            )
        }
    }

    private fun launchThermalSnapshotRetry(
        taskId: String,
        eventId: String,
        droneSn: String,
        sourceTs: Long,
        seedRegion: ThermalMeasureRegion,
        temperatureC: Double,
        thermalMeasurements: List<ThermalMeasuredPoint>,
        initialSnapshotPath: String?,
    ) {
        visibleConfirmationScope.launch {
            retryThermalSnapshotAfterReport(
                taskId = taskId,
                eventId = eventId,
                droneSn = droneSn,
                sourceTs = sourceTs,
                seedRegion = seedRegion,
                temperatureC = temperatureC,
                thermalMeasurements = thermalMeasurements,
                initialSnapshotPath = initialSnapshotPath,
            )
        }
    }

    private suspend fun retryThermalSnapshotAfterReport(
        taskId: String,
        eventId: String,
        droneSn: String,
        sourceTs: Long,
        seedRegion: ThermalMeasureRegion,
        temperatureC: Double,
        thermalMeasurements: List<ThermalMeasuredPoint>,
        initialSnapshotPath: String?,
    ) {
        var thermalImageUrl = uploadThermalSnapshotWithRetriesIfPresent(eventId, initialSnapshotPath)
        var reportTemperatureC = temperatureC
        var reportRegion = seedRegion
        var reportMeasurements = thermalMeasurements
        repeat(THERMAL_SNAPSHOT_REMEASURE_ATTEMPTS) { attempt ->
            if (!thermalImageUrl.isNullOrBlank()) {
                return@repeat
            }
            delay(THERMAL_REMEASURE_DELAY_MS)
            val retry = sessionManager.measureThermalHotspot(
                droneSn = droneSn,
                seedRegion = seedRegion,
            )
            if (retry.status.equals("applied", ignoreCase = true)
                && retry.thermalCenterTemperatureC != null
                && retry.thermalMeasureRegion != null
                && retry.thermalCenterTemperatureC >= reportThresholdC
            ) {
                reportTemperatureC = retry.thermalCenterTemperatureC
                reportRegion = retry.thermalMeasureRegion
                reportMeasurements = retry.thermalMeasurements
                thermalImageUrl = uploadThermalSnapshotWithRetriesIfPresent(eventId, retry.thermalSnapshotPath)
            } else {
                warn("thermal snapshot remeasure did not produce reportable hotspot event=$eventId attempt=${attempt + 1}")
            }
        }
        if (thermalImageUrl.isNullOrBlank()) {
            warn("thermal snapshot unavailable after async retry event=$eventId")
            return
        }
        lastReportedRegion = reportRegion
        recordThermalHotspotEvent(
            taskId = taskId,
            droneSn = droneSn,
            sourceTs = sourceTs,
            temperatureC = reportTemperatureC,
            thermalMeasureRoi = reportRegion.toApiMap(),
            thermalMeasurements = reportMeasurements,
            thermalImageUrl = thermalImageUrl,
        )
        launchVisibleSnapshotConfirmation(
            taskId = taskId,
            eventId = eventId,
            droneSn = droneSn,
            sourceTs = sourceTs,
            thermalImageUrl = thermalImageUrl,
        )
    }

    private suspend fun recordThermalHotspotEvent(
        taskId: String,
        droneSn: String,
        sourceTs: Long,
        temperatureC: Double,
        thermalMeasureRoi: Map<String, Double>,
        thermalMeasurements: List<ThermalMeasuredPoint>,
        thermalImageUrl: String?,
    ) {
        client.recordThermalHotspotEvent(
            taskId = taskId,
            droneSn = droneSn,
            sourceTs = sourceTs,
            temperatureC = temperatureC,
            thermalMeasureRoi = thermalMeasureRoi,
            thermalMeasurements = thermalMeasurements.map {
                ThermalMeasurementPayload(
                    temperatureC = it.temperatureC,
                    roi = it.region.toApiMap(),
                )
            },
            thermalImageUrl = thermalImageUrl,
        )
    }

    private fun launchVisibleSnapshotConfirmation(
        taskId: String,
        eventId: String,
        droneSn: String,
        sourceTs: Long,
        thermalImageUrl: String,
    ) {
        visibleConfirmationScope.launch {
            captureAndConfirmVisibleSnapshot(
                taskId = taskId,
                eventId = eventId,
                droneSn = droneSn,
                sourceTs = sourceTs,
                thermalImageUrl = thermalImageUrl,
            )
        }
    }

    private suspend fun captureAndConfirmVisibleSnapshot(
        taskId: String,
        eventId: String,
        droneSn: String,
        sourceTs: Long,
        thermalImageUrl: String,
    ) {
        recordVisibleStatus(
            taskId = taskId,
            eventId = eventId,
            droneSn = droneSn,
            thermalImageUrl = thermalImageUrl,
            status = VISIBLE_PENDING,
        )
        var capturedAny = false
        repeat(VISIBLE_CONFIRMATION_ATTEMPTS) { attempt ->
            val visibleSnapshotPath = runCatching {
                sessionManager.captureVisibleSnapshot(droneSn).visibleSnapshotPath
            }.onFailure {
                Log.w(TAG, "visible snapshot capture failed event=$eventId attempt=${attempt + 1} message=${it.message}", it)
            }.getOrNull()
            if (visibleSnapshotPath.isNullOrBlank()) {
                if (attempt + 1 < VISIBLE_CONFIRMATION_ATTEMPTS) {
                    delay(VISIBLE_CONFIRMATION_RETRY_DELAY_MS)
                }
                return@repeat
            }
            capturedAny = true
            val visibleUrl = runCatching {
                visibleSnapshotConfirmer.confirm(
                    taskId = taskId,
                    eventId = eventId,
                    droneSn = droneSn,
                    sourceTs = sourceTs,
                    snapshotPath = visibleSnapshotPath,
                    thermalSourceEventId = eventId,
                    thermalImageUrl = thermalImageUrl,
                )
            }.onFailure {
                Log.w(TAG, "visible snapshot confirmation failed event=$eventId path=$visibleSnapshotPath attempt=${attempt + 1} message=${it.message}", it)
            }.getOrNull()
            if (!visibleUrl.isNullOrBlank()) {
                return
            }
            if (attempt + 1 < VISIBLE_CONFIRMATION_ATTEMPTS) {
                delay(VISIBLE_CONFIRMATION_RETRY_DELAY_MS)
            }
        }
        recordVisibleStatus(
            taskId = taskId,
            eventId = eventId,
            droneSn = droneSn,
            thermalImageUrl = thermalImageUrl,
            status = if (capturedAny) VISIBLE_CONFIRM_FAILED else VISIBLE_CAPTURE_FAILED,
        )
    }

    private suspend fun recordVisibleStatus(
        taskId: String,
        eventId: String,
        droneSn: String,
        thermalImageUrl: String,
        status: String,
    ) {
        runCatching {
            client.recordVisibleConfirmationStatus(
                taskId = taskId,
                droneSn = droneSn,
                sourceTs = clockMs(),
                reviewStatus = status,
                thermalSourceEventId = eventId,
                thermalImageUrl = thermalImageUrl,
            )
        }.onFailure {
            Log.w(TAG, "visible status report failed event=$eventId status=$status message=${it.message}", it)
        }
    }

    private suspend fun uploadThermalSnapshotOnceIfPresent(eventId: String, snapshotPath: String?): String? {
        if (snapshotPath.isNullOrBlank()) {
            warn("thermal snapshot unavailable event=$eventId")
            return null
        }
        return runCatching { snapshotUploader.upload(eventId, snapshotPath) }
            .onFailure {
                warn(
                    "thermal snapshot upload failed event=$eventId path=$snapshotPath attempt=1 message=${it.message}",
                    it,
                )
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun uploadThermalSnapshotWithRetriesIfPresent(eventId: String, snapshotPath: String?): String? {
        if (snapshotPath.isNullOrBlank()) {
            warn("thermal snapshot unavailable event=$eventId")
            return null
        }
        return uploadThermalSnapshot(eventId, snapshotPath)
    }

    private suspend fun uploadThermalSnapshot(eventId: String, snapshotPath: String): String? {
        repeat(THERMAL_SNAPSHOT_UPLOAD_ATTEMPTS) { attempt ->
            val url = runCatching { snapshotUploader.upload(eventId, snapshotPath) }
                .onFailure {
                    warn(
                        "thermal snapshot upload failed event=$eventId path=$snapshotPath attempt=${attempt + 1} message=${it.message}",
                        it,
                    )
                }
                .getOrNull()
            if (!url.isNullOrBlank()) {
                return url
            }
            if (attempt + 1 < THERMAL_SNAPSHOT_UPLOAD_ATTEMPTS) {
                delay(THERMAL_SNAPSHOT_UPLOAD_RETRY_DELAY_MS)
            }
        }
        return null
    }

    private fun warn(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable == null) {
                Log.w(TAG, message)
            } else {
                Log.w(TAG, message, throwable)
            }
        }.onFailure {
            println("$TAG: $message")
        }
    }

    private fun info(message: String) {
        runCatching {
            Log.i(TAG, message)
        }.onFailure {
            println("$TAG: $message")
        }
    }

    companion object {
        private const val TAG = "ThermalHotspotMonitor"
        const val DEFAULT_REPORT_THRESHOLD_C: Double = 45.0
        const val DEFAULT_PROBE_INTERVAL_MS: Long = 2_000L
        const val DEFAULT_FRAME_TRIGGER_DEBOUNCE_MS: Long = 2_000L
        private const val THERMAL_SNAPSHOT_UPLOAD_ATTEMPTS = 2
        private const val THERMAL_SNAPSHOT_UPLOAD_RETRY_DELAY_MS = 250L
        private const val THERMAL_SNAPSHOT_REMEASURE_ATTEMPTS = 1
        private const val THERMAL_REMEASURE_DELAY_MS = 500L
        private const val VISIBLE_CONFIRMATION_ATTEMPTS = 3
        private const val VISIBLE_CONFIRMATION_RETRY_DELAY_MS = 300L
        private const val VISIBLE_PENDING = "VISIBLE_PENDING"
        private const val VISIBLE_CAPTURE_FAILED = "VISIBLE_CAPTURE_FAILED"
        private const val VISIBLE_CONFIRM_FAILED = "VISIBLE_CONFIRM_FAILED"
    }
}

private object NoopMissionHoldControl : MissionHoldControl {
    override suspend fun holdForConfirmation(): Boolean = false

    override suspend fun resumeAfterConfirmation() = Unit
}

private fun ThermalMeasureRegion.toApiMap(): Map<String, Double> = mapOf(
    "x" to x,
    "y" to y,
    "width" to width,
    "height" to height,
)
