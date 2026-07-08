package com.yinxin.uavfir.api

import android.util.Log
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.ThermalMeasuredPoint
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.LaserMeasureInformation
import dji.sdk.keyvalue.value.camera.LaserMeasureState
import dji.sdk.keyvalue.value.camera.LaserWorkMode
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.KeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

enum class FireConfirmationPhase {
    IDLE,
    FLY_TO,
    MEASURE_CLOSE,
    VISIBLE_CONFIRM,
    RESET,
}

data class AircraftLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,
)

data class FireConfirmationRequest(
    val droneSn: String,
    val taskId: String,
    val fireLat: Double,
    val fireLng: Double,
    val fireAlt: Double?,
)

data class FireConfirmationResult(
    val phaseReached: FireConfirmationPhase,
    val success: Boolean,
    val failureReason: String?,
    val closeMeasureTemperatureC: Double?,
    val preciseLat: Double?,
    val preciseLng: Double?,
    val resetCompleted: Boolean,
    val geoMethod: String? = null,
    val laserFix: RobustLaserFix? = null,
)

data class LaserRangefinderResult(
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double? = null,
    val distanceM: Double?,
    val state: String,
)

data class RobustLaserFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val distanceM: Double?,
    val normalSampleCount: Int,
    val centered: Boolean,
    val confidence: String,
)

interface LaserRangefinderClient {
    suspend fun measure(): LaserRangefinderResult?
}

object NoopLaserRangefinderClient : LaserRangefinderClient {
    override suspend fun measure(): LaserRangefinderResult? = null
}

class FireConfirmationProcessor(
    private val sessionManager: DualStreamSessionManager,
    private val flightControl: FlightControlActionClient,
    private val gimbalControl: GimbalActionClient,
    private val cameraControl: CameraActionClient,
    private val missionHold: MissionHoldControl,
    private val client: AgentBackendClient,
    private val aircraftLocationProvider: () -> AircraftLocation?,
    val enabled: Boolean = DEFAULT_ENABLED,
    private val standoffHorizontalM: Double = DEFAULT_STANDOFF_M,
    private val flyToTimeoutMs: Long = DEFAULT_FLY_TO_TIMEOUT_MS,
    private val measureGimbalPitchDeg: Double = DEFAULT_MEASURE_PITCH_DEG,
    private val visibleZoomRatio: Double = DEFAULT_VISIBLE_ZOOM,
    private val resetRetryCount: Int = DEFAULT_RESET_RETRIES,
    private val visibleSnapshotConfirmer: VisibleSnapshotConfirmer = AiServiceVisibleSnapshotConfirmer(),
    private val laserRangefinder: LaserRangefinderClient = DjiLaserRangefinderClient(),
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val aimMaxIterations: Int = DEFAULT_AIM_MAX_ITERATIONS,
    private val aimToleranceFrac: Double = DEFAULT_AIM_TOLERANCE_FRAC,
    private val aimSettleMs: Long = DEFAULT_AIM_SETTLE_MS,
    private val fovHorizontalDeg: Double = DEFAULT_FOV_HORIZONTAL_DEG,
    private val fovVerticalDeg: Double = DEFAULT_FOV_VERTICAL_DEG,
    private val laserSampleCount: Int = DEFAULT_LASER_SAMPLE_COUNT,
    private val laserSampleIntervalMs: Long = DEFAULT_LASER_SAMPLE_INTERVAL_MS,
    private val laserMinNormalSamples: Int = DEFAULT_LASER_MIN_NORMAL_SAMPLES,
    private val laserScatterLimitM: Double = DEFAULT_LASER_SCATTER_LIMIT_M,
    private val laserGeoErrorRadiusM: Double = DEFAULT_LASER_GEO_ERROR_RADIUS_M,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val running = AtomicBoolean(false)

    suspend fun run(request: FireConfirmationRequest): FireConfirmationResult {
        if (!running.compareAndSet(false, true)) {
            return FireConfirmationResult(
                phaseReached = FireConfirmationPhase.IDLE,
                success = false,
                failureReason = "busy",
                closeMeasureTemperatureC = null,
                preciseLat = null,
                preciseLng = null,
                resetCompleted = false,
            )
        }

        val previousMonitoring = sessionManager.thermalMonitoringEnabled
        var phaseReached = FireConfirmationPhase.IDLE
        var failureReason: String? = null
        var closeTemperature: Double? = null
        var preciseLat: Double? = null
        var preciseLng: Double? = null
        var geoMethod: String? = null
        var laserFix: RobustLaserFix? = null
        var thermalReported = false
        var resetCompleted = false

        val coreResult = try {
            sessionManager.thermalMonitoringEnabled = false
            runCatching { missionHold.holdForConfirmation() }
                .onFailure { warn("mission hold failed task=${request.taskId} message=${it.message}", it) }

            phaseReached = FireConfirmationPhase.FLY_TO
            val flyToTarget = flyToStandoffPoint(request)
            if (flyToTarget == null) {
                failureReason = "aircraft-location-unavailable"
                buildResult(phaseReached, thermalReported, failureReason, closeTemperature, preciseLat, preciseLng, resetCompleted, geoMethod, laserFix)
            } else {
                preciseLat = flyToTarget.latitude
                preciseLng = flyToTarget.longitude
                geoMethod = "standoff-hover-point-fallback"

                val arrived = withTimeoutOrNull(flyToTimeoutMs) {
                    flightControl.flyToPoint(flyToTarget.latitude, flyToTarget.longitude, flyToTarget.altitudeM, DEFAULT_FLY_TO_SPEED_MPS)
                    waitUntilArrived(flyToTarget)
                } == true
                if (!arrived) {
                    runCatching { flightControl.stopFlyToPoint() }
                        .onFailure { warn("stop fly-to failed task=${request.taskId} message=${it.message}", it) }
                    failureReason = "fly-to-timeout"
                    buildResult(phaseReached, thermalReported, failureReason, closeTemperature, preciseLat, preciseLng, resetCompleted, geoMethod, laserFix)
                } else {
                    phaseReached = FireConfirmationPhase.MEASURE_CLOSE
                    val closeMeasure = measureClose(request)
                    val measureResult = closeMeasure.result
                    closeTemperature = measureResult.thermalCenterTemperatureC
                    if (!measureResult.status.equals("applied", ignoreCase = true) ||
                        measureResult.thermalCenterTemperatureC == null ||
                        measureResult.thermalMeasureRegion == null
                    ) {
                        failureReason = "thermal-close-measure-failed"
                        buildResult(phaseReached, thermalReported, failureReason, closeTemperature, preciseLat, preciseLng, resetCompleted, geoMethod, laserFix)
                    } else {
                        runCatching { measureRobustLaserFix(request, closeMeasure.centered) }
                            .onSuccess { fix ->
                                if (fix != null) {
                                    laserFix = fix
                                    preciseLat = fix.latitude
                                    preciseLng = fix.longitude
                                    geoMethod = "laser-rangefinder"
                                }
                            }
                            .onFailure { warn("laser measure failed task=${request.taskId} message=${it.message}", it) }

                        val sourceTs = clockMs()
                        val thermalEventId = "${request.taskId}-$sourceTs"
                        val reportLaserFix = laserFix?.takeIf { it.confidence == "HIGH" }
                        client.recordThermalHotspotEvent(
                            taskId = request.taskId,
                            droneSn = request.droneSn,
                            sourceTs = sourceTs,
                            temperatureC = measureResult.thermalCenterTemperatureC,
                            thermalMeasureRoi = measureResult.thermalMeasureRegion.toApiMap(),
                            thermalMeasurements = measureResult.thermalMeasurements.toPayload(),
                            thermalImageUrl = null,
                            fireLat = reportLaserFix?.latitude,
                            fireLng = reportLaserFix?.longitude,
                            fireAlt = reportLaserFix?.altitude,
                            geoMethod = reportLaserFix?.let { LASER_GEO_METHOD },
                            geoErrorRadiusM = reportLaserFix?.let { laserGeoErrorRadiusM },
                        )
                        thermalReported = true

                        phaseReached = FireConfirmationPhase.VISIBLE_CONFIRM
                        val visibleFailure = runCatching {
                            confirmVisible(request, thermalEventId, sourceTs)
                        }.exceptionOrNull()
                        if (visibleFailure != null) {
                            failureReason = "visible-confirm-failed"
                        }

                        buildResult(phaseReached, thermalReported, failureReason, closeTemperature, preciseLat, preciseLng, resetCompleted, geoMethod, laserFix)
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (failureReason == null) {
                failureReason = throwable.message ?: throwable::class.simpleName ?: "fire-confirmation-failed"
            }
            buildResult(phaseReached, thermalReported, failureReason, closeTemperature, preciseLat, preciseLng, resetCompleted, geoMethod, laserFix)
        } finally {
            resetCompleted = reset(previousMonitoring)
            running.set(false)
        }
        return coreResult.copy(resetCompleted = resetCompleted)
    }

    private suspend fun flyToStandoffPoint(request: FireConfirmationRequest): AircraftLocation? {
        val current = aircraftLocationProvider() ?: return null
        val metersPerLat = METERS_PER_LAT_DEG
        val metersPerLng = metersPerLat * cos(request.fireLat.toRadians()).coerceAtLeast(0.01)
        val currentDxM = (current.longitude - request.fireLng) * metersPerLng
        val currentDyM = (current.latitude - request.fireLat) * metersPerLat
        val distanceM = hypot(currentDxM, currentDyM)
        val unitX = if (distanceM > 0.1) currentDxM / distanceM else -1.0
        val unitY = if (distanceM > 0.1) currentDyM / distanceM else 0.0
        val targetLat = request.fireLat + unitY * standoffHorizontalM / metersPerLat
        val targetLng = request.fireLng + unitX * standoffHorizontalM / metersPerLng
        return AircraftLocation(
            latitude = targetLat,
            longitude = targetLng,
            altitudeM = max(current.altitudeM, request.fireAlt ?: current.altitudeM),
        )
    }

    private suspend fun waitUntilArrived(target: AircraftLocation): Boolean {
        while (true) {
            val current = aircraftLocationProvider() ?: return false
            if (horizontalDistanceM(current, target) <= ARRIVAL_RADIUS_M) {
                return true
            }
            delay(pollIntervalMs)
        }
    }

    private suspend fun measureClose(request: FireConfirmationRequest): CloseMeasureResult {
        val focused = sessionManager.executeCommand(request.droneSn, "focus-thermal")
        if (!focused.status.equals("applied", ignoreCase = true)) {
            return CloseMeasureResult(
                result = DualStreamSessionManager.CommandExecutionResult(
                    status = "failed",
                    message = focused.message ?: "focus-thermal-failed",
                ),
                centered = false,
            )
        }
        gimbalControl.rotateGimbalToPitch(measureGimbalPitchDeg)
        val measured = sessionManager.measureThermalHotspot(request.droneSn)
        if (!measured.status.equals("applied", ignoreCase = true) || measured.thermalMeasureRegion == null) {
            return CloseMeasureResult(measured, centered = false)
        }
        return aimAtThermalHotspot(request.droneSn, measured)
    }

    private suspend fun aimAtThermalHotspot(
        droneSn: String,
        initial: DualStreamSessionManager.CommandExecutionResult,
    ): CloseMeasureResult {
        var latest = initial
        repeat(aimMaxIterations.coerceAtLeast(0)) {
            val roi = latest.thermalMeasureRegion ?: return CloseMeasureResult(latest, centered = false)
            if (roi.isCentered(aimToleranceFrac)) {
                return CloseMeasureResult(latest, centered = true)
            }
            val dx = roi.centerX() - FRAME_CENTER_FRAC
            val dy = roi.centerY() - FRAME_CENTER_FRAC
            val yawDelta = dx * fovHorizontalDeg
            val pitchDelta = -dy * fovVerticalDeg
            // RELATIVE_ANGLE applies these degree deltas deterministically before the next ROI measurement.
            gimbalControl.rotateGimbalBy(pitchDelta, yawDelta)
            delay(aimSettleMs)
            val next = sessionManager.measureThermalHotspot(droneSn, roi)
            if (!next.status.equals("applied", ignoreCase = true) || next.thermalMeasureRegion == null) {
                return CloseMeasureResult(latest, centered = false)
            }
            latest = next
        }
        return CloseMeasureResult(latest, centered = latest.thermalMeasureRegion?.isCentered(aimToleranceFrac) == true)
    }

    private suspend fun measureRobustLaserFix(
        request: FireConfirmationRequest,
        centered: Boolean,
    ): RobustLaserFix? {
        val expectedDistanceM = expectedLaserDistanceM(request)
        val samples = mutableListOf<LaserFixSample>()
        repeat(laserSampleCount.coerceAtLeast(0)) { index ->
            val laser = laserRangefinder.measure()
            if (laser != null &&
                laser.state.equals("NORMAL", ignoreCase = true) &&
                laser.latitude != null &&
                laser.longitude != null &&
                laser.distanceM.isPlausibleLaserDistance(expectedDistanceM)
            ) {
                samples += LaserFixSample(
                    latitude = laser.latitude,
                    longitude = laser.longitude,
                    altitude = laser.altitude,
                    distanceM = laser.distanceM,
                )
            }
            if (index < laserSampleCount - 1) {
                delay(laserSampleIntervalMs)
            }
        }
        if (samples.size < laserMinNormalSamples) {
            return null
        }
        if (samples.hasScatterBeyondLimit(laserScatterLimitM)) {
            return null
        }
        return RobustLaserFix(
            latitude = samples.map { it.latitude }.median(),
            longitude = samples.map { it.longitude }.median(),
            altitude = samples.mapNotNull { it.altitude }.medianOrNull(),
            distanceM = samples.mapNotNull { it.distanceM }.medianOrNull(),
            normalSampleCount = samples.size,
            centered = centered,
            confidence = "HIGH",
        )
    }

    private fun expectedLaserDistanceM(request: FireConfirmationRequest): Double {
        val aircraft = aircraftLocationProvider()
        val heightDeltaM = if (aircraft != null && request.fireAlt != null) {
            abs(aircraft.altitudeM - request.fireAlt)
        } else {
            standoffHorizontalM
        }
        return hypot(standoffHorizontalM, heightDeltaM)
    }

    private suspend fun confirmVisible(request: FireConfirmationRequest, thermalEventId: String, sourceTs: Long) {
        val focused = sessionManager.executeCommand(request.droneSn, "focus-visible")
        if (!focused.status.equals("applied", ignoreCase = true)) {
            error(focused.message ?: "focus-visible-failed")
        }
        cameraControl.setZoom(visibleZoomRatio)
        val snapshotPath = sessionManager.captureVisibleSnapshot(request.droneSn).visibleSnapshotPath
        if (snapshotPath.isNullOrBlank()) {
            error("visible-snapshot-missing")
        }
        visibleSnapshotConfirmer.confirm(
            taskId = request.taskId,
            eventId = thermalEventId,
            droneSn = request.droneSn,
            sourceTs = sourceTs,
            snapshotPath = snapshotPath,
            thermalSourceEventId = thermalEventId,
            thermalImageUrl = null,
        )
    }

    private suspend fun reset(previousMonitoring: Boolean): Boolean {
        var completed = true
        completed = retryResetStep("set thermal stream") { cameraControl.setStreamSource("thermal") } && completed
        completed = retryResetStep("reset gimbal") { gimbalControl.resetGimbal() } && completed
        completed = retryResetStep("reset zoom") { cameraControl.setZoom(1.0) } && completed
        sessionManager.thermalMonitoringEnabled = previousMonitoring
        completed = retryResetStep("resume mission") { missionHold.resumeAfterConfirmation() } && completed
        return completed
    }

    private suspend fun retryResetStep(name: String, block: suspend () -> Unit): Boolean {
        repeat(resetRetryCount + 1) { attempt ->
            val result = runCatching { block() }
            if (result.isSuccess) {
                return true
            }
            warn("reset step failed name=$name attempt=${attempt + 1} message=${result.exceptionOrNull()?.message}", result.exceptionOrNull())
        }
        return false
    }

    private fun buildResult(
        phaseReached: FireConfirmationPhase,
        thermalReported: Boolean,
        failureReason: String?,
        closeTemperature: Double?,
        preciseLat: Double?,
        preciseLng: Double?,
        resetCompleted: Boolean,
        geoMethod: String?,
        laserFix: RobustLaserFix?,
    ): FireConfirmationResult = FireConfirmationResult(
        phaseReached = phaseReached,
        success = thermalReported,
        failureReason = failureReason,
        closeMeasureTemperatureC = closeTemperature,
        preciseLat = preciseLat,
        preciseLng = preciseLng,
        resetCompleted = resetCompleted,
        geoMethod = geoMethod,
        laserFix = laserFix,
    )

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

    private companion object {
        private const val TAG = "FireConfirmProcessor"
        const val DEFAULT_ENABLED = false
        const val DEFAULT_STANDOFF_M = 60.0
        const val DEFAULT_FLY_TO_TIMEOUT_MS = 90_000L
        const val DEFAULT_MEASURE_PITCH_DEG = -45.0
        const val DEFAULT_VISIBLE_ZOOM = 5.0
        const val DEFAULT_RESET_RETRIES = 2
        private const val DEFAULT_POLL_INTERVAL_MS = 500L
        private const val DEFAULT_AIM_MAX_ITERATIONS = 3
        private const val DEFAULT_AIM_TOLERANCE_FRAC = 0.05
        private const val DEFAULT_AIM_SETTLE_MS = 500L
        private const val DEFAULT_FOV_HORIZONTAL_DEG = 45.0
        private const val DEFAULT_FOV_VERTICAL_DEG = 37.0
        private const val DEFAULT_LASER_SAMPLE_COUNT = 5
        private const val DEFAULT_LASER_SAMPLE_INTERVAL_MS = 300L
        private const val DEFAULT_LASER_MIN_NORMAL_SAMPLES = 3
        private const val DEFAULT_LASER_SCATTER_LIMIT_M = 15.0
        private const val DEFAULT_LASER_GEO_ERROR_RADIUS_M = 5.0
        private const val LASER_GEO_METHOD = "LASER_RANGEFINDER"
        private const val DEFAULT_FLY_TO_SPEED_MPS = 5.0
        private const val METERS_PER_LAT_DEG = 111_320.0
        private const val ARRIVAL_RADIUS_M = 5.0
        private const val FRAME_CENTER_FRAC = 0.5
    }
}

private data class CloseMeasureResult(
    val result: DualStreamSessionManager.CommandExecutionResult,
    val centered: Boolean,
)

private data class LaserFixSample(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val distanceM: Double?,
)

class FireConfirmationAutoTrigger(
    private val processor: FireConfirmationProcessor,
    private val scope: CoroutineScope,
    private val fireLocationProvider: () -> AircraftLocation?,
) {
    var startedCount: Int = 0
        private set

    fun onConfirmedReport(taskId: String, droneSn: String) {
        if (!processor.enabled) {
            return
        }
        val fireLocation = fireLocationProvider() ?: return
        startedCount += 1
        scope.launch {
            processor.run(
                FireConfirmationRequest(
                    droneSn = droneSn,
                    taskId = taskId,
                    fireLat = fireLocation.latitude,
                    fireLng = fireLocation.longitude,
                    fireAlt = fireLocation.altitudeM,
                ),
            )
        }
    }
}

class DjiLaserRangefinderClient(
    private val keyManager: KeyManager = KeyManager.getInstance(),
    private val settleMs: Long = 500L,
) : LaserRangefinderClient {
    override suspend fun measure(): LaserRangefinderResult? {
        val workModeKey = laserKey(DJICameraKey.KeyLaserWorkMode)
        val enabledKey = laserKey(DJICameraKey.KeyLaserMeasureEnabled)
        val informationKey = laserKey(DJICameraKey.KeyLaserMeasureInformation)
        setValue(workModeKey, LaserWorkMode.OPEN_ON_DEMAND)
        setValue(enabledKey, true)
        delay(settleMs)
        val information = getValue(informationKey) ?: return null
        return information.toResult()
    }

    private fun <T> laserKey(keyInfo: dji.sdk.keyvalue.key.DJIKeyInfo<T>): DJIKey<T> =
        KeyTools.createCameraKey(keyInfo, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_ZOOM)

    private suspend fun <T> setValue(key: DJIKey<T>, value: T) {
        suspendCancellableCoroutine<Unit> { continuation ->
            keyManager.setValue(key, value, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    continuation.takeIf { it.isActive }?.resume(Unit)
                }

                override fun onFailure(error: IDJIError) {
                    continuation.takeIf { it.isActive }
                        ?.resumeWithException(IllegalStateException(error.description()))
                }
            })
        }
    }

    private suspend fun <T> getValue(key: DJIKey<T>): T? {
        return suspendCancellableCoroutine { continuation ->
            keyManager.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(result: T?) {
                    continuation.takeIf { it.isActive }?.resume(result)
                }

                override fun onFailure(error: IDJIError) {
                    continuation.takeIf { it.isActive }
                        ?.resumeWithException(IllegalStateException(error.description()))
                }
            })
        }
    }
}

object DjiAircraftLocationProvider {
    fun current(): AircraftLocation? {
        val location: LocationCoordinate3D = runCatching {
            FlightControllerKey.KeyAircraftLocation3D.create()
                .get(LocationCoordinate3D(Double.NaN, Double.NaN, Double.NaN))
        }.getOrNull() ?: return null
        if (!location.latitude.isFinite() || !location.longitude.isFinite() || !location.altitude.isFinite()) {
            return null
        }
        return AircraftLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeM = location.altitude,
        )
    }
}

private fun LaserMeasureInformation.toResult(): LaserRangefinderResult {
    val state = laserMeasureState ?: LaserMeasureState.UNKNOWN
    return LaserRangefinderResult(
        latitude = location3D?.latitude,
        longitude = location3D?.longitude,
        altitude = location3D?.altitude,
        distanceM = distance,
        state = state.name,
    )
}

private fun List<ThermalMeasuredPoint>.toPayload(): List<ThermalMeasurementPayload> = map {
    ThermalMeasurementPayload(
        temperatureC = it.temperatureC,
        roi = it.region.toApiMap(),
    )
}

private fun ThermalMeasureRegion.toApiMap(): Map<String, Double> = mapOf(
    "x" to x,
    "y" to y,
    "width" to width,
    "height" to height,
)

private fun horizontalDistanceM(a: AircraftLocation, b: AircraftLocation): Double {
    val metersPerLng = 111_320.0 * cos(((a.latitude + b.latitude) / 2.0).toRadians()).coerceAtLeast(0.01)
    val dx = (a.longitude - b.longitude) * metersPerLng
    val dy = (a.latitude - b.latitude) * 111_320.0
    return hypot(dx, dy)
}

private fun horizontalDistanceM(a: LaserFixSample, b: LaserFixSample): Double =
    horizontalDistanceM(
        AircraftLocation(a.latitude, a.longitude, a.altitude ?: 0.0),
        AircraftLocation(b.latitude, b.longitude, b.altitude ?: 0.0),
    )

private fun List<LaserFixSample>.hasScatterBeyondLimit(limitM: Double): Boolean {
    for (i in indices) {
        for (j in i + 1..lastIndex) {
            if (horizontalDistanceM(this[i], this[j]) > limitM) {
                return true
            }
        }
    }
    return false
}

private fun Double?.isPlausibleLaserDistance(expectedDistanceM: Double): Boolean =
    this == null || (this >= 3.0 && this <= expectedDistanceM * 3.0)

private fun ThermalMeasureRegion.centerX(): Double = x + width / 2.0

private fun ThermalMeasureRegion.centerY(): Double = y + height / 2.0

private fun ThermalMeasureRegion.isCentered(toleranceFrac: Double): Boolean =
    abs(centerX() - 0.5) < toleranceFrac && abs(centerY() - 0.5) < toleranceFrac

private fun List<Double>.median(): Double {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}

private fun List<Double>.medianOrNull(): Double? = if (isEmpty()) null else median()

private fun Double.toRadians(): Double = this / 180.0 * PI
