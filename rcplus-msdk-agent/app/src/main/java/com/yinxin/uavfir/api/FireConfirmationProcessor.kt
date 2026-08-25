package com.yinxin.uavfir.api

import android.util.Log
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.ThermalMeasuredPoint
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.LaserMeasureInformation
import dji.sdk.keyvalue.value.camera.LaserMeasureState
import dji.sdk.keyvalue.value.camera.LaserWorkMode
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import com.yinxin.uavfir.sdk.PayloadSelectionRegistry
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.WindDirection
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.KeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
    val orbitFix: RobustLaserFix? = null,
    val orbitPointsVisited: Int = 0,
    val legsCompleted: Int = 0,
)

data class LaserRangefinderResult(
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double? = null,
    val distanceM: Double?,
    val state: String,
    val targetX: Double? = null,
    val targetY: Double? = null,
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

    suspend fun disable() = Unit
}

object NoopLaserRangefinderClient : LaserRangefinderClient {
    override suspend fun measure(): LaserRangefinderResult? = null
}

data class WindReading(
    val speedMps: Double,
    val sourceBearingDeg: Double?,
)

interface WindProvider {
    suspend fun currentWind(): WindReading?
}

object NoopWindProvider : WindProvider {
    override suspend fun currentWind(): WindReading? = null
}

interface BatteryProvider {
    fun currentPercent(): Int?
}

object NoopBatteryProvider : BatteryProvider {
    override fun currentPercent(): Int? = null
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
    private val snapshotUploader: ThermalSnapshotUploader = AiServiceThermalSnapshotUploader(),
    private val laserRangefinder: LaserRangefinderClient = DjiLaserRangefinderClient(),
    private val windProvider: WindProvider = DjiWindProvider(),
    private val batteryProvider: BatteryProvider = DjiBatteryProvider(),
    private val approachLegsM: List<Double> = DEFAULT_APPROACH_LEGS,
    private val orbitBearingCount: Int = DEFAULT_ORBIT_BEARING_COUNT,
    private val orbitPerPointLaserSamples: Int = DEFAULT_ORBIT_PER_POINT_LASER_SAMPLES,
    private val orbitRadiusM: Double = approachLegsM.lastOrNull() ?: DEFAULT_STANDOFF_M,
    private val upwindMinWindSpeedMps: Double = DEFAULT_UPWIND_MIN_WIND_SPEED_MPS,
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
    private val maxFixDisplacementM: Double = DEFAULT_MAX_FIX_DISPLACEMENT_M,
    private val minBatteryPercentToStart: Int = DEFAULT_MIN_BATTERY_PERCENT_TO_START,
    private val maxMissionDurationMs: Long = DEFAULT_MAX_MISSION_DURATION_MS,
    private val divergenceAbortM: Double = DEFAULT_DIVERGENCE_ABORT_M,
    private val visibleConfirmAtUpwind: Boolean = true,
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
        var orbitFix: RobustLaserFix? = null
        var orbitPointsVisited = 0
        var legsCompleted = 0
        var thermalReported = false
        var resetCompleted = false
        val missionStartMs = clockMs()

        val coreResult = try {
            sessionManager.thermalMonitoringEnabled = false
            runCatching { missionHold.holdForConfirmation() }
                .onFailure { warn("mission hold failed task=${request.taskId} message=${it.message}", it) }

            val batteryPercent = runCatching { batteryProvider.currentPercent() }
                .onFailure { warn("battery read failed task=${request.taskId} message=${it.message}", it) }
                .getOrNull()
            if (batteryPercent != null && batteryPercent < minBatteryPercentToStart) {
                failureReason = "battery-below-threshold"
                throw FireConfirmationEarlyAbort()
            }

            val legs = approachLegsM.filter { it > 0.0 }.ifEmpty { listOf(standoffHorizontalM) }
            var target = AircraftLocation(request.fireLat, request.fireLng, request.fireAlt ?: 0.0)
            var finalCloseMeasure: CloseMeasureResult? = null
            var finalThermalResult: DualStreamSessionManager.CommandExecutionResult? = null
            var aborted = false
            var skipOrbit = false

            for ((index, legM) in legs.withIndex()) {
                if (missionDurationExceeded(missionStartMs)) {
                    failureReason = "mission-duration-exceeded"
                    if (finalThermalResult != null) {
                        skipOrbit = true
                    } else {
                        aborted = true
                    }
                    break
                }
                phaseReached = FireConfirmationPhase.FLY_TO
                val flyToTarget = flyToStandoffPoint(target, legM)
                if (flyToTarget == null) {
                    failureReason = "aircraft-location-unavailable"
                    aborted = true
                    break
                }
                preciseLat = flyToTarget.latitude
                preciseLng = flyToTarget.longitude
                geoMethod = "standoff-hover-point-fallback"

                val arrived = runCatching {
                    withTimeoutOrNull(flyToTimeoutMs) {
                        flightControl.flyToPoint(flyToTarget.latitude, flyToTarget.longitude, flyToTarget.altitudeM, DEFAULT_FLY_TO_SPEED_MPS)
                        waitUntilArrived(flyToTarget)
                    } == true
                }.getOrElse {
                    warn("fly-to failed task=${request.taskId} leg=${index + 1} message=${it.message}", it)
                    false
                }
                if (!arrived) {
                    runCatching { flightControl.stopFlyToPoint() }
                        .onFailure { warn("stop fly-to failed task=${request.taskId} message=${it.message}", it) }
                    failureReason = "fly-to-timeout"
                    aborted = true
                    break
                }

                legsCompleted += 1
                phaseReached = FireConfirmationPhase.MEASURE_CLOSE
                val closeMeasure = measureClose(request)
                val measureResult = closeMeasure.result
                closeTemperature = measureResult.thermalCenterTemperatureC ?: closeTemperature
                val finalLeg = index == legs.lastIndex
                if (!measureResult.status.equals("applied", ignoreCase = true) ||
                    measureResult.thermalCenterTemperatureC == null ||
                    measureResult.thermalMeasureRegion == null
                ) {
                    if (finalLeg && finalThermalResult != null) {
                        warn("final-leg-measure-degraded task=${request.taskId} leg=${index + 1}")
                        failureReason = "final-leg-measure-degraded"
                        skipOrbit = true
                        break
                    } else if (finalLeg) {
                        failureReason = "thermal-close-measure-failed"
                        aborted = true
                        break
                    } else {
                        warn("intermediate close measure failed task=${request.taskId} leg=${index + 1}")
                        continue
                    }
                }

                finalCloseMeasure = closeMeasure
                finalThermalResult = measureResult
                runCatching { measureRobustLaserFix(request, closeMeasure.centered, legM) }
                    .onSuccess { fix ->
                        if (fix != null) {
                            laserFix = fix
                            target = AircraftLocation(fix.latitude, fix.longitude, target.altitudeM)
                            preciseLat = fix.latitude
                            preciseLng = fix.longitude
                            geoMethod = "laser-rangefinder"
                            warn(
                                "laser fix task=${request.taskId} leg=${index + 1} " +
                                    "lat=${fix.latitude} lng=${fix.longitude} samples=${fix.normalSampleCount}",
                            )
                        } else {
                            warn("laser fix unavailable task=${request.taskId} leg=${index + 1}")
                        }
                    }
                    .onFailure { warn("laser measure failed task=${request.taskId} leg=${index + 1} message=${it.message}", it) }
            }

            if (!aborted && finalCloseMeasure != null && finalThermalResult != null) {
                val orbitResult = if (skipOrbit) {
                    null
                } else if (missionDurationExceeded(missionStartMs)) {
                    failureReason = "mission-duration-exceeded"
                    null
                } else {
                    runCatching { measureOrbitFix(request, target, missionStartMs) }
                        .onFailure { warn("orbit measure failed task=${request.taskId} message=${it.message}", it) }
                        .getOrNull()
                }
                if (orbitResult != null) {
                    orbitFix = orbitResult.fix
                    orbitPointsVisited = orbitResult.pointsVisited
                    if (orbitResult.failureReason != null && failureReason == null) {
                        failureReason = orbitResult.failureReason
                    }
                    if (orbitFix != null) {
                        preciseLat = orbitFix.latitude
                        preciseLng = orbitFix.longitude
                        geoMethod = "laser-rangefinder"
                    }
                }

                val sourceTs = clockMs()
                val thermalEventId = "${request.taskId}-$sourceTs"
                val reportLaserFix = (orbitFix ?: laserFix)?.takeIf { it.confidence == "HIGH" }
                val measureResult = finalThermalResult
                val thermalImageUrl = uploadThermalSnapshotWithRetries(thermalEventId, measureResult.thermalSnapshotPath)
                if (thermalImageUrl.isNullOrBlank()) {
                    warn(
                        "approach-report-missing-thermal-image task=${request.taskId} " +
                            "event=$thermalEventId path=${measureResult.thermalSnapshotPath}",
                    )
                }
                client.recordThermalHotspotEvent(
                    taskId = request.taskId,
                    droneSn = request.droneSn,
                    sourceTs = sourceTs,
                    temperatureC = measureResult.thermalCenterTemperatureC!!,
                    thermalMeasureRoi = measureResult.thermalMeasureRegion!!.toApiMap(),
                    thermalMeasurements = measureResult.thermalMeasurements.toPayload(),
                    thermalImageUrl = thermalImageUrl,
                    fireLat = reportLaserFix?.latitude,
                    fireLng = reportLaserFix?.longitude,
                    fireAlt = reportLaserFix?.altitude,
                    geoMethod = reportLaserFix?.let { LASER_GEO_METHOD },
                    geoErrorRadiusM = reportLaserFix?.let { laserGeoErrorRadiusM },
                )
                thermalReported = true

                if (visibleConfirmAtUpwind && orbitResult != null) {
                    returnToUpwindVisiblePointIfNeeded(request, orbitResult)
                }
                phaseReached = FireConfirmationPhase.VISIBLE_CONFIRM
                val visibleFailure = runCatching {
                    confirmVisible(request, thermalEventId, sourceTs)
                }.exceptionOrNull()
                if (visibleFailure != null) {
                    failureReason = "visible-confirm-failed"
                }
            } else if (!aborted) {
                failureReason = "thermal-close-measure-failed"
            }

            buildResult(
                phaseReached,
                thermalReported,
                failureReason,
                closeTemperature,
                preciseLat,
                preciseLng,
                resetCompleted,
                geoMethod,
                laserFix,
                orbitFix,
                orbitPointsVisited,
                legsCompleted,
            )
        } catch (throwable: Throwable) {
            if (failureReason == null) {
                failureReason = throwable.message ?: throwable::class.simpleName ?: "fire-confirmation-failed"
            }
            buildResult(
                phaseReached,
                thermalReported,
                failureReason,
                closeTemperature,
                preciseLat,
                preciseLng,
                resetCompleted,
                geoMethod,
                laserFix,
                orbitFix,
                orbitPointsVisited,
                legsCompleted,
            )
        } finally {
            // NonCancellable：协程被取消时 finally 里的挂起调用会立刻抛 CancellationException，
            // 复位（含监测开关恢复）必须在取消场景下也完整执行
            resetCompleted = withContext(NonCancellable) { reset(previousMonitoring) }
            running.set(false)
        }
        return coreResult.copy(resetCompleted = resetCompleted)
    }

    private suspend fun flyToStandoffPoint(target: AircraftLocation, standoffM: Double): AircraftLocation? {
        val current = aircraftLocationProvider() ?: return null
        return pointAtBearing(
            center = target,
            bearingDeg = selectApproachBearingDeg(target, current),
            radiusM = standoffM,
            altitudeM = current.altitudeM,
        )
    }

    private suspend fun waitUntilArrived(target: AircraftLocation): Boolean {
        var minDistanceM = Double.POSITIVE_INFINITY
        while (true) {
            val current = aircraftLocationProvider() ?: return false
            // 高度失控看门狗：固件转场剖面若无视 SET_HEIGHT 自行爬升（2026-07-26 实飞 20m→90m），
            // 立即中止而不是等 fly-to 超时。
            if (current.altitudeM > target.altitudeM + ALTITUDE_RUNAWAY_ABORT_M) {
                warn(
                    "altitude-runaway currentAltM=${current.altitudeM} targetAltM=${target.altitudeM} " +
                        "thresholdM=$ALTITUDE_RUNAWAY_ABORT_M",
                )
                return false
            }
            val distanceM = horizontalDistanceM(current, target)
            if (distanceM <= ARRIVAL_RADIUS_M) {
                return true
            }
            if (distanceM.isFinite()) {
                if (distanceM < minDistanceM) {
                    minDistanceM = distanceM
                } else if (minDistanceM.isFinite() && distanceM > minDistanceM + divergenceAbortM) {
                    warn(
                        "diverging-from-target targetLat=${target.latitude} targetLng=${target.longitude} " +
                            "distanceM=$distanceM minDistanceM=$minDistanceM thresholdM=$divergenceAbortM",
                    )
                    return false
                }
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
        horizontalDistanceM: Double = standoffHorizontalM,
        sampleCount: Int = laserSampleCount,
    ): RobustLaserFix? {
        val samples = collectLaserSamples(request, horizontalDistanceM, sampleCount)
        return samples.toRobustFix(centered, laserMinNormalSamples, laserScatterLimitM)
            ?.takeIfWithinFixDisplacement(request, "leg")
    }

    private suspend fun collectLaserSamples(
        request: FireConfirmationRequest,
        horizontalDistanceM: Double,
        sampleCount: Int,
    ): List<LaserFixSample> {
        val expectedDistanceM = expectedLaserDistanceM(request, horizontalDistanceM)
        val samples = mutableListOf<LaserFixSample>()
        val safeSampleCount = sampleCount.coerceAtLeast(0)
        repeat(safeSampleCount) { index ->
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
            if (index < safeSampleCount - 1) {
                delay(laserSampleIntervalMs)
            }
        }
        return samples
    }

    private fun expectedLaserDistanceM(request: FireConfirmationRequest, horizontalDistanceM: Double): Double {
        val aircraft = aircraftLocationProvider()
        val heightDeltaM = if (aircraft != null && request.fireAlt != null) {
            abs(aircraft.altitudeM - request.fireAlt)
        } else {
            horizontalDistanceM
        }
        return hypot(horizontalDistanceM, heightDeltaM)
    }

    private suspend fun measureOrbitFix(
        request: FireConfirmationRequest,
        center: AircraftLocation,
        missionStartMs: Long,
    ): OrbitMeasureResult {
        val count = orbitBearingCount.coerceAtLeast(0)
        if (count == 0) {
            return OrbitMeasureResult(fix = null, pointsVisited = 0)
        }
        val startBearing = selectApproachBearingDeg(center, aircraftLocationProvider() ?: center)
        val stepDeg = 360.0 / count
        val samples = mutableListOf<LaserFixSample>()
        var visited = 0
        var firstVisitedPoint: AircraftLocation? = null
        var lastVisitedPoint: AircraftLocation? = null
        repeat(count) { index ->
            if (missionDurationExceeded(missionStartMs)) {
                warn("mission duration exceeded during orbit task=${request.taskId} point=${index + 1}")
                return OrbitMeasureResult(
                    fix = samples.toRobustFix(centered = true, laserMinNormalSamples, laserScatterLimitM)
                        ?.takeIfWithinFixDisplacement(request, "orbit"),
                    pointsVisited = visited,
                    firstVisitedPoint = firstVisitedPoint,
                    lastVisitedPoint = lastVisitedPoint,
                    failureReason = "mission-duration-exceeded",
                )
            }
            val current = aircraftLocationProvider()
            val target = pointAtBearing(
                center = center,
                bearingDeg = startBearing + stepDeg * index,
                radiusM = orbitRadiusM,
                altitudeM = current?.altitudeM ?: center.altitudeM,
            )
            val arrived = runCatching {
                if (current != null && horizontalDistanceM(current, target) <= ARRIVAL_RADIUS_M) {
                    true
                } else withTimeoutOrNull(flyToTimeoutMs) {
                    flightControl.flyToPoint(target.latitude, target.longitude, target.altitudeM, DEFAULT_FLY_TO_SPEED_MPS)
                    waitUntilArrived(target)
                } == true
            }.getOrElse {
                warn("orbit fly-to failed task=${request.taskId} point=${index + 1} message=${it.message}", it)
                false
            }
            if (!arrived) {
                runCatching { flightControl.stopFlyToPoint() }
                    .onFailure { warn("stop orbit fly-to failed task=${request.taskId} message=${it.message}", it) }
                warn("orbit point skipped task=${request.taskId} point=${index + 1}")
                return@repeat
            }
            visited += 1
            if (firstVisitedPoint == null) {
                firstVisitedPoint = target
            }
            lastVisitedPoint = target
            val closeMeasure = measureClose(request)
            val measureResult = closeMeasure.result
            if (!measureResult.status.equals("applied", ignoreCase = true) ||
                measureResult.thermalCenterTemperatureC == null ||
                measureResult.thermalMeasureRegion == null
            ) {
                warn("orbit close measure skipped task=${request.taskId} point=${index + 1}")
                return@repeat
            }
            val pointSamples = collectLaserSamples(request, orbitRadiusM, orbitPerPointLaserSamples)
            samples += pointSamples
        }
        return OrbitMeasureResult(
            fix = samples.toRobustFix(centered = true, laserMinNormalSamples, laserScatterLimitM)
                ?.takeIfWithinFixDisplacement(request, "orbit"),
            pointsVisited = visited,
            firstVisitedPoint = firstVisitedPoint,
            lastVisitedPoint = lastVisitedPoint,
        )
    }

    private suspend fun returnToUpwindVisiblePointIfNeeded(
        request: FireConfirmationRequest,
        orbitResult: OrbitMeasureResult,
    ) {
        val firstPoint = orbitResult.firstVisitedPoint ?: return
        val lastPoint = orbitResult.lastVisitedPoint ?: return
        if (orbitResult.pointsVisited < 1 || horizontalDistanceM(firstPoint, lastPoint) <= ARRIVAL_RADIUS_M) {
            return
        }
        val arrived = runCatching {
            withTimeoutOrNull(flyToTimeoutMs) {
                flightControl.flyToPoint(firstPoint.latitude, firstPoint.longitude, firstPoint.altitudeM, DEFAULT_FLY_TO_SPEED_MPS)
                waitUntilArrived(firstPoint)
            } == true
        }.getOrElse {
            warn("visible upwind return failed task=${request.taskId} message=${it.message}", it)
            false
        }
        if (!arrived) {
            warn("visible upwind return skipped confirmation task=${request.taskId}")
            runCatching { flightControl.stopFlyToPoint() }
                .onFailure { warn("stop visible upwind return failed task=${request.taskId} message=${it.message}", it) }
        }
    }

    private suspend fun selectApproachBearingDeg(target: AircraftLocation, current: AircraftLocation): Double {
        val wind = runCatching { windProvider.currentWind() }
            .onFailure { warn("wind read failed message=${it.message}", it) }
            .getOrNull()
        val windBearing = wind?.sourceBearingDeg
        if (wind != null &&
            wind.speedMps >= upwindMinWindSpeedMps &&
            windBearing != null &&
            windBearing.isFinite()
        ) {
            return windBearing.normalizeDeg()
        }
        return bearingFrom(target, current)
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
        // 监测开关恢复必须是第一步且不可挂起——曾排在三个可失败/可取消的复位步骤之后，
        // 任一步骤异常或协程取消都会让开关永久卡死（2026-07-24 实测：事件断流+确认照缺失）。
        sessionManager.thermalMonitoringEnabled = previousMonitoring
        var completed = true
        completed = retryResetStep("set thermal stream") { cameraControl.setStreamSource("thermal") } && completed
        completed = retryResetStep("reset gimbal") { gimbalControl.resetGimbal() } && completed
        completed = retryResetStep("reset zoom") { cameraControl.setZoom(1.0) } && completed
        completed = retryResetStep("resume mission") { missionHold.resumeAfterConfirmation() } && completed
        return completed
    }

    private fun missionDurationExceeded(missionStartMs: Long): Boolean =
        clockMs() - missionStartMs > maxMissionDurationMs

    private suspend fun uploadThermalSnapshotWithRetries(eventId: String, snapshotPath: String?): String? {
        if (snapshotPath.isNullOrBlank()) {
            return null
        }
        repeat(THERMAL_SNAPSHOT_UPLOAD_ATTEMPTS) { attempt ->
            val url = runCatching { snapshotUploader.upload(eventId, snapshotPath) }
                .onFailure {
                    warn(
                        "thermal snapshot upload failed event=$eventId path=$snapshotPath " +
                            "attempt=${attempt + 1} message=${it.message}",
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

    private fun RobustLaserFix.takeIfWithinFixDisplacement(
        request: FireConfirmationRequest,
        label: String,
    ): RobustLaserFix? {
        val displacementM = horizontalDistanceM(
            AircraftLocation(latitude, longitude, altitude ?: 0.0),
            AircraftLocation(request.fireLat, request.fireLng, request.fireAlt ?: 0.0),
        )
        if (displacementM <= maxFixDisplacementM) {
            return this
        }
        warn(
            "laser fix rejected by displacement task=${request.taskId} source=$label " +
                "displacementM=$displacementM thresholdM=$maxFixDisplacementM",
        )
        return null
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
        orbitFix: RobustLaserFix?,
        orbitPointsVisited: Int,
        legsCompleted: Int,
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
        orbitFix = orbitFix,
        orbitPointsVisited = orbitPointsVisited,
        legsCompleted = legsCompleted,
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
        private const val DEFAULT_MAX_FIX_DISPLACEMENT_M = 80.0
        private const val DEFAULT_MIN_BATTERY_PERCENT_TO_START = 30
        private const val DEFAULT_MAX_MISSION_DURATION_MS = 360_000L
        private const val DEFAULT_DIVERGENCE_ABORT_M = 40.0
        private const val ALTITUDE_RUNAWAY_ABORT_M = 15.0
        private val DEFAULT_APPROACH_LEGS = listOf(100.0, 60.0)
        private const val DEFAULT_ORBIT_BEARING_COUNT = 3
        private const val DEFAULT_ORBIT_PER_POINT_LASER_SAMPLES = 3
        private const val DEFAULT_UPWIND_MIN_WIND_SPEED_MPS = 2.0
        private const val LASER_GEO_METHOD = "LASER_RANGEFINDER"
        private const val DEFAULT_FLY_TO_SPEED_MPS = 5.0
        private const val THERMAL_SNAPSHOT_UPLOAD_ATTEMPTS = 2
        private const val THERMAL_SNAPSHOT_UPLOAD_RETRY_DELAY_MS = 250L
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

private data class OrbitMeasureResult(
    val fix: RobustLaserFix?,
    val pointsVisited: Int,
    val firstVisitedPoint: AircraftLocation? = null,
    val lastVisitedPoint: AircraftLocation? = null,
    val failureReason: String? = null,
)

private class FireConfirmationEarlyAbort : RuntimeException()

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

class DjiWindProvider : WindProvider {
    override suspend fun currentWind(): WindReading? {
        // Verified against DJI MSDK v5.18.0 dji-sdk-v5-aircraft-provided.jar:
        // FlightControllerKey.KeyWindSpeed is DJIKeyInfo<Integer> and
        // KeyWindDirection is DJIKeyInfo<WindDirection>. Official DJI MSDK v5
        // docs state KeyWindSpeed is dm/s, so this converts to m/s.
        // WindDirection values are world-coordinate cardinal enums
        // (WINDLESS/NORTH/NORTH_EAST/EAST/SOUTH_EAST/SOUTH/SOUTH_WEST/WEST/
        // NORTH_WEST/UNKNOWN). The docs do not state source-vs-destination;
        // this provider treats the enum as wind source bearing to satisfy
        // upwind approach semantics. This assumption is pending real-aircraft
        // validation (待实机验证).
        val speedDmps = runCatching {
            FlightControllerKey.KeyWindSpeed.create().get(0)
        }.getOrNull()
        val direction = runCatching {
            FlightControllerKey.KeyWindDirection.create().get(WindDirection.UNKNOWN)
        }.getOrNull()
        if (speedDmps == null && direction == null) {
            return null
        }
        return WindReading(
            speedMps = (speedDmps ?: 0).toDouble() / 10.0,
            sourceBearingDeg = direction?.toSourceBearingDeg(),
        )
    }
}

class DjiBatteryProvider : BatteryProvider {
    override fun currentPercent(): Int? {
        // MSDK v5 battery percentage matches the OSD path already used in
        // OsdReporter and DjiDeviceSession:
        // BatteryKey.KeyChargeRemainingInPercent.create().get(0).
        // The aircraft battery is index 0; failures are tolerated as unknown
        // so the safety guard does not block on disconnected simulator/test rigs.
        return runCatching {
            BatteryKey.KeyChargeRemainingInPercent.create().get(0)
        }.getOrNull()
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

    override suspend fun disable() {
        setValue(laserKey(DJICameraKey.KeyLaserMeasureEnabled), false)
    }

    private fun <T> laserKey(keyInfo: dji.sdk.keyvalue.key.DJIKeyInfo<T>): DJIKey<T> =
        KeyTools.createCameraKey(keyInfo, PayloadSelectionRegistry.selectedComponentIndex(), CameraLensType.CAMERA_LENS_ZOOM)

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
        targetX = targetPoint?.x?.normalizeLaserTargetCoordinate(),
        targetY = targetPoint?.y?.normalizeLaserTargetCoordinate(),
    )
}

private fun Double.normalizeLaserTargetCoordinate(): Double =
    if (this > 1.0) this / 100.0 else this

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

private fun pointAtBearing(
    center: AircraftLocation,
    bearingDeg: Double,
    radiusM: Double,
    altitudeM: Double,
): AircraftLocation {
    val bearingRad = bearingDeg.toRadians()
    val metersPerLng = 111_320.0 * cos(center.latitude.toRadians()).coerceAtLeast(0.01)
    return AircraftLocation(
        latitude = center.latitude + cos(bearingRad) * radiusM / 111_320.0,
        longitude = center.longitude + sin(bearingRad) * radiusM / metersPerLng,
        altitudeM = altitudeM,
    )
}

private fun bearingFrom(origin: AircraftLocation, point: AircraftLocation): Double {
    val metersPerLng = 111_320.0 * cos(origin.latitude.toRadians()).coerceAtLeast(0.01)
    val dx = (point.longitude - origin.longitude) * metersPerLng
    val dy = (point.latitude - origin.latitude) * 111_320.0
    val distanceM = hypot(dx, dy)
    if (distanceM <= 0.1) {
        return 270.0
    }
    return atan2(dx, dy).toDegrees().normalizeDeg()
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

private fun List<LaserFixSample>.toRobustFix(
    centered: Boolean,
    minNormalSamples: Int,
    scatterLimitM: Double,
): RobustLaserFix? {
    if (size < minNormalSamples) {
        return null
    }
    val center = LaserFixSample(
        latitude = map { it.latitude }.median(),
        longitude = map { it.longitude }.median(),
        altitude = null,
        distanceM = null,
    )
    val trimmed = filter { horizontalDistanceM(it, center) <= scatterLimitM }
    if (trimmed.size < minNormalSamples) {
        return null
    }
    return RobustLaserFix(
        latitude = trimmed.map { it.latitude }.median(),
        longitude = trimmed.map { it.longitude }.median(),
        altitude = trimmed.mapNotNull { it.altitude }.medianOrNull(),
        distanceM = trimmed.mapNotNull { it.distanceM }.medianOrNull(),
        normalSampleCount = trimmed.size,
        centered = centered,
        confidence = "HIGH",
    )
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

private fun WindDirection.toSourceBearingDeg(): Double? = when (this) {
    WindDirection.NORTH -> 0.0
    WindDirection.NORTH_EAST -> 45.0
    WindDirection.EAST -> 90.0
    WindDirection.SOUTH_EAST -> 135.0
    WindDirection.SOUTH -> 180.0
    WindDirection.SOUTH_WEST -> 225.0
    WindDirection.WEST -> 270.0
    WindDirection.NORTH_WEST -> 315.0
    WindDirection.WINDLESS,
    WindDirection.UNKNOWN,
    -> null
}

private fun Double.toRadians(): Double = this / 180.0 * PI

private fun Double.toDegrees(): Double = this * 180.0 / PI

private fun Double.normalizeDeg(): Double = ((this % 360.0) + 360.0) % 360.0
