package com.yinxin.uavfir.api

import android.util.Log
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.session.DualStreamSessionState
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ThermalHotspotMonitor(
    private val client: AgentBackendClient,
    private val sessionManager: DualStreamSessionManager,
    private val snapshotUploader: ThermalSnapshotUploader = AiServiceThermalSnapshotUploader(),
    private val visibleSnapshotConfirmer: VisibleSnapshotConfirmer = AiServiceVisibleSnapshotConfirmer(),
    private val visibleConfirmationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val taskIdFactory: (String) -> String = { droneSn -> "fire-$droneSn" },
    private val reportThresholdC: Double = DEFAULT_REPORT_THRESHOLD_C,
    private val probeIntervalMs: Long = DEFAULT_PROBE_INTERVAL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : CommandPoller {
    private var lastProbeAtMs: Long = 0L
    private var lastReportedRegion: ThermalMeasureRegion? = null

    override suspend fun pollOnce(droneSn: String) {
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

        val result = sessionManager.measureThermalHotspot(
            droneSn = droneSn,
            seedRegion = lastReportedRegion,
        )
        if (!result.status.equals("applied", ignoreCase = true)) {
            Log.w(TAG, "thermal hotspot probe failed drone=$droneSn message=${result.message}")
            return
        }

        var temperature = result.thermalCenterTemperatureC ?: return
        var region = result.thermalMeasureRegion ?: return
        var measurements = result.thermalMeasurements
        if (temperature < reportThresholdC) {
            return
        }

        val taskId = taskIdFactory(droneSn)
        val eventId = "$taskId-$now"
        var thermalImageUrl = uploadThermalSnapshotIfPresent(eventId, result.thermalSnapshotPath)
        repeat(THERMAL_SNAPSHOT_REMEASURE_ATTEMPTS) { attempt ->
            if (!thermalImageUrl.isNullOrBlank()) {
                return@repeat
            }
            delay(THERMAL_REMEASURE_DELAY_MS)
            val retry = sessionManager.measureThermalHotspot(
                droneSn = droneSn,
                seedRegion = region,
            )
            if (retry.status.equals("applied", ignoreCase = true)
                && retry.thermalCenterTemperatureC != null
                && retry.thermalMeasureRegion != null
                && retry.thermalCenterTemperatureC >= reportThresholdC
            ) {
                temperature = retry.thermalCenterTemperatureC
                region = retry.thermalMeasureRegion
                measurements = retry.thermalMeasurements
                thermalImageUrl = uploadThermalSnapshotIfPresent(eventId, retry.thermalSnapshotPath)
            } else {
                warn("thermal snapshot remeasure did not produce reportable hotspot event=$eventId attempt=${attempt + 1}")
            }
        }
        if (thermalImageUrl.isNullOrBlank()) {
            warn("thermal hotspot event skipped after thermal snapshot unavailable event=$eventId")
            return
        }
        lastReportedRegion = region
        client.recordThermalHotspotEvent(
            taskId = taskId,
            droneSn = droneSn,
            sourceTs = now,
            temperatureC = temperature,
            thermalMeasureRoi = region.toApiMap(),
            thermalMeasurements = measurements.map {
                ThermalMeasurementPayload(
                    temperatureC = it.temperatureC,
                    roi = it.region.toApiMap(),
                )
            },
            thermalImageUrl = thermalImageUrl,
        )
        launchVisibleSnapshotConfirmation(
            taskId = taskId,
            eventId = eventId,
            droneSn = droneSn,
            sourceTs = now,
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

    private suspend fun uploadThermalSnapshotIfPresent(eventId: String, snapshotPath: String?): String? {
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

    companion object {
        private const val TAG = "ThermalHotspotMonitor"
        const val DEFAULT_REPORT_THRESHOLD_C: Double = 45.0
        const val DEFAULT_PROBE_INTERVAL_MS: Long = 10_000L
        private const val THERMAL_SNAPSHOT_UPLOAD_ATTEMPTS = 2
        private const val THERMAL_SNAPSHOT_UPLOAD_RETRY_DELAY_MS = 250L
        private const val THERMAL_SNAPSHOT_REMEASURE_ATTEMPTS = 4
        private const val THERMAL_REMEASURE_DELAY_MS = 500L
        private const val VISIBLE_CONFIRMATION_ATTEMPTS = 3
        private const val VISIBLE_CONFIRMATION_RETRY_DELAY_MS = 300L
        private const val VISIBLE_PENDING = "VISIBLE_PENDING"
        private const val VISIBLE_CAPTURE_FAILED = "VISIBLE_CAPTURE_FAILED"
        private const val VISIBLE_CONFIRM_FAILED = "VISIBLE_CONFIRM_FAILED"
    }
}

private fun ThermalMeasureRegion.toApiMap(): Map<String, Double> = mapOf(
    "x" to x,
    "y" to y,
    "width" to width,
    "height" to height,
)
