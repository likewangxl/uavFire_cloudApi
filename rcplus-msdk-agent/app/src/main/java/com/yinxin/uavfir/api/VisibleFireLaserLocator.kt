package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamSessionManager
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.TapZoomMode
import dji.sdk.keyvalue.value.camera.ZoomTargetPointInfo
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.KeyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

data class VelocitySample(
    val horizontalMps: Double,
    val verticalMps: Double,
)

fun interface AircraftVelocityProvider {
    fun current(): VelocitySample?
}

interface VisibleFireTime {
    fun nowMs(): Long
    suspend fun delayMs(durationMs: Long)
}

fun interface VisibleTargetAimer {
    suspend fun align(taskId: String, visibleRoi: Map<String, Double>): Boolean
}

object UnsupportedVisibleTargetAimer : VisibleTargetAimer {
    override suspend fun align(taskId: String, visibleRoi: Map<String, Double>): Boolean = false
}

fun interface TapZoomClient {
    suspend fun tap(x: Double, y: Double)
}

fun interface VisibleRoiProvider {
    suspend fun latest(taskId: String, afterSourceTs: Long): VisibleRoiSnapshotResponse?
}

class DjiTapZoomClient(
    private val keyManager: KeyManager = KeyManager.getInstance(),
) : TapZoomClient {
    override suspend fun tap(x: Double, y: Double) {
        val key: DJIKey.ActionKey<ZoomTargetPointInfo, EmptyMsg> =
            KeyTools.createCameraKey(
                DJICameraKey.KeyTapZoomAtTarget,
                ComponentIndexType.LEFT_OR_MAIN,
                CameraLensType.CAMERA_LENS_ZOOM,
            )
        val target = ZoomTargetPointInfo(
            x.coerceIn(0.0, 1.0),
            y.coerceIn(0.0, 1.0),
            true,
            TapZoomMode.GIMBAL_FOLLOW,
        )
        suspendCancellableCoroutine<Unit> { continuation ->
            keyManager.performAction(
                key,
                target,
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        continuation.takeIf { it.isActive }?.resume(Unit)
                    }

                    override fun onFailure(error: IDJIError) {
                        continuation.takeIf { it.isActive }
                            ?.resumeWithException(IllegalStateException(error.description()))
                    }
                },
            )
        }
    }
}

class BackendVisibleTargetAimer(
    private val visibleRoiProvider: VisibleRoiProvider,
    private val tapZoomClient: TapZoomClient,
    private val laserRangefinder: LaserRangefinderClient,
    private val time: VisibleFireTime = SystemVisibleFireTime,
    private val maxIterations: Int = 3,
    private val settleMs: Long = 500L,
) : VisibleTargetAimer {
    override suspend fun align(taskId: String, visibleRoi: Map<String, Double>): Boolean {
        var roi = visibleRoi
        repeat(maxIterations) {
            if (!roi.isNormalizedRoi()) {
                return false
            }
            tapZoomClient.tap(
                roi.getValue("x") + roi.getValue("width") / 2.0,
                roi.getValue("y") + roi.getValue("height") / 2.0,
            )
            val movedAt = System.currentTimeMillis()
            time.delayMs(settleMs)
            val fresh = visibleRoiProvider.latest(taskId, movedAt) ?: return false
            val laser = laserRangefinder.measure() ?: return false
            val targetX = laser.targetX ?: return false
            val targetY = laser.targetY ?: return false
            if (fresh.visibleRoi.containsPoint(targetX, targetY)) {
                return true
            }
            roi = fresh.visibleRoi
        }
        return false
    }
}

object SystemVisibleFireTime : VisibleFireTime {
    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    override suspend fun delayMs(durationMs: Long) {
        delay(durationMs)
    }
}

class DjiAircraftVelocityProvider : AircraftVelocityProvider {
    override fun current(): VelocitySample? {
        val velocity = runCatching {
            FlightControllerKey.KeyAircraftVelocity.create()
                .get(Velocity3D(Double.NaN, Double.NaN, Double.NaN))
        }.getOrNull() ?: return null
        if (!velocity.x.isFinite() || !velocity.y.isFinite() || !velocity.z.isFinite()) {
            return null
        }
        return VelocitySample(
            horizontalMps = hypot(velocity.x, velocity.y),
            verticalMps = abs(velocity.z),
        )
    }
}

class VisibleFireLaserLocator(
    private val missionHold: MissionHoldControl,
    private val flightControl: FlightControlActionClient,
    private val velocityProvider: AircraftVelocityProvider = DjiAircraftVelocityProvider(),
    private val time: VisibleFireTime = SystemVisibleFireTime,
    private val targetAimer: VisibleTargetAimer = UnsupportedVisibleTargetAimer,
    private val laserRangefinder: LaserRangefinderClient = NoopLaserRangefinderClient,
) {
    @Volatile
    private var heldEventId: String? = null

    suspend fun hold(eventId: String): DualStreamSessionManager.CommandExecutionResult {
        if (eventId.isBlank()) {
            return failure("event-id-required")
        }
        val routePaused = runCatching { missionHold.holdForConfirmation() }.getOrDefault(false)
        if (!routePaused) {
            val hoverFailure = runCatching { flightControl.hover() }.exceptionOrNull()
            if (hoverFailure != null) {
                heldEventId = null
                return failure("hover-command-failed")
            }
        }
        heldEventId = eventId

        val startedAt = time.nowMs()
        var stableSince: Long? = null
        while (time.nowMs() - startedAt <= HOVER_TIMEOUT_MS) {
            val sample = velocityProvider.current()
            val stable = sample != null &&
                sample.horizontalMps <= MAX_HORIZONTAL_SPEED_MPS &&
                abs(sample.verticalMps) <= MAX_VERTICAL_SPEED_MPS
            val now = time.nowMs()
            if (stable) {
                if (stableSince == null) {
                    stableSince = now
                }
                if (now - stableSince >= REQUIRED_STABLE_MS) {
                    return DualStreamSessionManager.CommandExecutionResult(
                        status = "applied",
                        message = "HOVER_STABLE",
                        eventId = eventId,
                    )
                }
            } else {
                stableSince = null
            }
            time.delayMs(VELOCITY_POLL_MS)
        }
        return failure("hover-stability-timeout")
    }

    suspend fun measure(
        eventId: String,
        taskId: String,
        visibleRoi: Map<String, Double>,
    ): DualStreamSessionManager.CommandExecutionResult {
        if (eventId != heldEventId) {
            return failureFor(eventId, "event-session-mismatch")
        }
        try {
            if (!isValidRoi(visibleRoi) || !targetAimer.align(taskId, visibleRoi)) {
                return failureFor(eventId, "target-not-aligned")
            }
            val samples = mutableListOf<LaserRangefinderResult>()
            repeat(LASER_SAMPLE_COUNT) { index ->
                val sample = runCatching { laserRangefinder.measure() }.getOrNull()
                if (sample != null &&
                    sample.state.equals("NORMAL", ignoreCase = true) &&
                    sample.latitude?.isFinite() == true &&
                    sample.longitude?.isFinite() == true
                ) {
                    samples += sample
                }
                if (index < LASER_SAMPLE_COUNT - 1) {
                    time.delayMs(LASER_SAMPLE_INTERVAL_MS)
                }
            }
            if (samples.size < LASER_SAMPLE_COUNT || samples.hasScatterBeyond(LASER_SCATTER_LIMIT_M)) {
                return failureFor(eventId, "laser-fix-unavailable")
            }
            return DualStreamSessionManager.CommandExecutionResult(
                status = "applied",
                message = "LASER_LOCATED",
                eventId = eventId,
                fireLat = samples.mapNotNull { it.latitude }.median(),
                fireLng = samples.mapNotNull { it.longitude }.median(),
                fireAlt = samples.mapNotNull { it.altitude }.medianOrNull(),
                geoMethod = "LASER_RANGEFINDER",
                geoQuality = "PRECISE",
                geoErrorRadiusM = LASER_ERROR_RADIUS_M,
                sourceTs = System.currentTimeMillis(),
            )
        } finally {
            runCatching { laserRangefinder.disable() }
            heldEventId = null
        }
    }

    private fun failure(reason: String) = DualStreamSessionManager.CommandExecutionResult(
        status = "failed",
        message = "LASER_FAILED:$reason",
        eventId = heldEventId,
    )

    private fun failureFor(eventId: String, reason: String) =
        DualStreamSessionManager.CommandExecutionResult(
            status = "failed",
            message = "LASER_FAILED:$reason",
            eventId = eventId,
            sourceTs = System.currentTimeMillis(),
        )

    private fun isValidRoi(roi: Map<String, Double>): Boolean {
        return roi.isNormalizedRoi()
    }

    companion object {
        const val MAX_HORIZONTAL_SPEED_MPS = 0.3
        const val MAX_VERTICAL_SPEED_MPS = 0.2
        const val REQUIRED_STABLE_MS = 1_000L
        const val HOVER_TIMEOUT_MS = 8_000L
        const val VELOCITY_POLL_MS = 200L
        const val LASER_SAMPLE_COUNT = 3
        const val LASER_SAMPLE_INTERVAL_MS = 300L
        const val LASER_SCATTER_LIMIT_M = 15.0
        const val LASER_ERROR_RADIUS_M = 5.0
    }
}

private fun Map<String, Double>.isNormalizedRoi(): Boolean {
    val x = this["x"] ?: return false
    val y = this["y"] ?: return false
    val width = this["width"] ?: return false
    val height = this["height"] ?: return false
    return x in 0.0..1.0 && y in 0.0..1.0 &&
        width > 0.0 && height > 0.0 &&
        x + width <= 1.000001 && y + height <= 1.000001
}

private fun Map<String, Double>.containsPoint(x: Double, y: Double): Boolean {
    if (!isNormalizedRoi()) {
        return false
    }
    val left = getValue("x")
    val top = getValue("y")
    return x >= left && x <= left + getValue("width") &&
        y >= top && y <= top + getValue("height")
}

private fun List<LaserRangefinderResult>.hasScatterBeyond(limitM: Double): Boolean {
    for (first in indices) {
        for (second in first + 1 until size) {
            if (laserDistanceM(this[first], this[second]) > limitM) {
                return true
            }
        }
    }
    return false
}

private fun laserDistanceM(a: LaserRangefinderResult, b: LaserRangefinderResult): Double {
    val latitudeA = a.latitude ?: return Double.POSITIVE_INFINITY
    val longitudeA = a.longitude ?: return Double.POSITIVE_INFINITY
    val latitudeB = b.latitude ?: return Double.POSITIVE_INFINITY
    val longitudeB = b.longitude ?: return Double.POSITIVE_INFINITY
    val metersPerLng = 111_320.0 * cos(Math.toRadians((latitudeA + latitudeB) / 2.0))
    return hypot(
        (longitudeA - longitudeB) * metersPerLng,
        (latitudeA - latitudeB) * 111_320.0,
    )
}

private fun List<Double>.median(): Double {
    val sorted = sorted()
    return sorted[sorted.size / 2]
}

private fun List<Double>.medianOrNull(): Double? =
    takeIf { it.isNotEmpty() }?.median()
