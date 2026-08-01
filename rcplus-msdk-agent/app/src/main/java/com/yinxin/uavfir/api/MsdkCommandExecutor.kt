package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamCommandExecutor
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.DJIGimbalKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.RtkMobileStationKey
import dji.sdk.keyvalue.value.camera.CameraNightSceneMode
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.FlyToMode
import dji.sdk.keyvalue.value.flightcontroller.FlyToOperationType
import dji.sdk.keyvalue.value.flightcontroller.FlyToPointInfo
import dji.sdk.keyvalue.value.flightcontroller.FlyToResult
import dji.sdk.keyvalue.value.flightcontroller.LEDsSettings
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.CtrlInfo
import dji.sdk.keyvalue.value.gimbal.GimbalSpeedRotation
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.KeyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class MsdkCommandExecutionResult(
    val status: String,
    val message: String? = null,
)

interface MsdkCommandExecutor {
    suspend fun execute(
        aircraftSn: String,
        command: MsdkCommandResponse,
    ): MsdkCommandExecutionResult
}

interface FlightControlActionClient {
    suspend fun startTakeoff()

    suspend fun startGoHome()

    suspend fun stopGoHome()

    suspend fun startAutoLanding()

    suspend fun stopAutoLanding()

    suspend fun emergencyStop()

    suspend fun hover()

    suspend fun stopFlyToPoint()

    suspend fun sendVirtualStick(
        key: String,
        durationMs: Long,
    )

    suspend fun flyToPoint(
        latitude: Double,
        longitude: Double,
        height: Double,
        speed: Double,
    )

    suspend fun setNavigationLight(enabled: Boolean)
}

interface GimbalActionClient {
    suspend fun resetGimbal()

    suspend fun rotateGimbal(
        pitch: Double,
        yaw: Double,
        roll: Double,
    )

    /** Angle-relative rotation (RELATIVE_ANGLE): pitchDelta/yawDelta are degrees; signs indicate direction. */
    suspend fun rotateGimbalBy(pitchDelta: Double, yawDelta: Double)

    suspend fun rotateGimbalToPitch(pitch: Double)
}

interface CameraActionClient {
    suspend fun startShootPhoto()

    suspend fun startRecord()

    suspend fun stopRecord()

    suspend fun setStreamSource(source: String)

    suspend fun setZoom(ratio: Double)

    suspend fun setNightScene(enabled: Boolean)

    suspend fun setLaserFillLight(enabled: Boolean)
}

class DjiFlightControlActionClient : FlightControlActionClient, GimbalActionClient, CameraActionClient {
    private val keyManager: KeyManager
        get() = KeyManager.getInstance()

    override suspend fun startTakeoff() {
        performEmptyAction(FlightControllerKey.KeyStartTakeoff.create())
    }

    override suspend fun startGoHome() {
        performEmptyAction(FlightControllerKey.KeyStartGoHome.create())
    }

    override suspend fun stopGoHome() {
        performEmptyAction(FlightControllerKey.KeyStopGoHome.create())
    }

    override suspend fun startAutoLanding() {
        performEmptyAction(FlightControllerKey.KeyStartAutoLanding.create())
    }

    override suspend fun stopAutoLanding() {
        performEmptyAction(FlightControllerKey.KeyStopAutoLanding.create())
    }

    override suspend fun emergencyStop() {
        performEmptyAction(FlightControllerKey.KeyEmergencyStop.create())
    }

    override suspend fun hover() {
        sendVirtualStick("hover", MIN_VIRTUAL_STICK_DURATION_MS)
    }

    override suspend fun stopFlyToPoint() {
        hover()
    }

    override suspend fun sendVirtualStick(
        key: String,
        durationMs: Long,
    ) {
        val param = buildVirtualStickParam(key)
        val safeDurationMs = durationMs.coerceIn(MIN_VIRTUAL_STICK_DURATION_MS, MAX_VIRTUAL_STICK_DURATION_MS)
        withTimeout(safeDurationMs + MSDK_ACTION_TIMEOUT_MS) {
            enableVirtualStick()
            VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
            val startedAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - startedAt < safeDurationMs) {
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                delay(VIRTUAL_STICK_SEND_INTERVAL_MS)
            }
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(buildVirtualStickParam("hover"))
        }
    }

    override suspend fun flyToPoint(
        latitude: Double,
        longitude: Double,
        height: Double,
        speed: Double,
    ) {
        // 固件默认 SMART_HEIGHT 转场剖面会自行爬升到返航高度（2026-07-26 实飞 20m→90m），
        // 必须先锁 SET_HEIGHT + 巡航高度；两个 set 失败则中止，否则又是失控剖面。
        setValue(FlightControllerKey.KeyFlyToMode.create(), FlyToMode.SET_HEIGHT)
        setValue(FlightControllerKey.KeyFlyToHeight.create(), height)
        // KeyFlyToPointEx 的目标高度是椭球绝对高：官方 FlyToMissionIndustryDelegate
        // 用 相对高 + RTK 起飞点海拔 换算后下发，这里对齐同一口径。
        val takeoffAltitudeM = readTakeoffAltitudeM()
            ?: throw IllegalStateException("takeoff-altitude-unavailable")
        val target = FlyToPointInfo(
            FlyToOperationType.NEW_ORDER,
            LocationCoordinate3D(latitude, longitude, height + takeoffAltitudeM),
            speed.coerceIn(MIN_FLY_TO_SPEED_MPS, MAX_FLY_TO_SPEED_MPS),
            height,
        )
        performFlyToAction(FlightControllerKey.KeyFlyToPointEx.create(), target)
    }

    private suspend fun readTakeoffAltitudeM(): Double? {
        val rtkAltitude = runCatching {
            getValue(RtkMobileStationKey.KeyRTKTakeoffAltitudeInfo.create())?.altitude
        }.getOrNull()
        if (rtkAltitude != null && rtkAltitude.isFinite()) {
            return rtkAltitude
        }
        return runCatching { getValue(FlightControllerKey.KeyTakeoffLocationAltitude.create()) }
            .getOrNull()
            ?.takeIf { it.isFinite() }
    }

    override suspend fun setNavigationLight(enabled: Boolean) {
        // 只控制航行灯（夜航灯），其余 LED 字段保持 null = 不变
        val settings = LEDsSettings().also { it.navigationLEDsOn = enabled }
        setValue(FlightControllerKey.KeyLEDsSettings.create(), settings)
    }

    override suspend fun resetGimbal() {
        // 用绝对角度归零（pitch=0、yaw=0），保证无论当前姿态都强制回到正前方水平位、有可见动作；
        // RECENTER 在 M4 上常出现“返回成功但云台不动”的情况。roll 不可控，忽略。
        val rotation = GimbalAngleRotation(
            GimbalAngleRotationMode.ABSOLUTE_ANGLE,
            0.0,
            0.0,
            0.0,
            false,
            true,
            false,
            GIMBAL_RECENTER_DURATION_SEC,
            false,
            GIMBAL_RECENTER_TIMEOUT_SEC,
        )
        performAction(
            KeyTools.createKey(DJIGimbalKey.KeyRotateByAngle, ComponentIndexType.LEFT_OR_MAIN),
            rotation,
        )
    }

    override suspend fun rotateGimbal(
        pitch: Double,
        yaw: Double,
        roll: Double,
    ) {
        val rotation = GimbalSpeedRotation(
            pitch.coerceIn(-30.0, 30.0),
            yaw.coerceIn(-30.0, 30.0),
            roll.coerceIn(-30.0, 30.0),
            CtrlInfo(false, false),
        )
        performAction(
            KeyTools.createKey(DJIGimbalKey.KeyRotateBySpeed, ComponentIndexType.LEFT_OR_MAIN),
            rotation,
        )
    }

    override suspend fun rotateGimbalBy(pitchDelta: Double, yawDelta: Double) {
        // 对中修正需要 pitch 和 yaw 同时生效：仅忽略不可控的 roll（对照 resetGimbal 的标志位）。
        val rotation = GimbalAngleRotation(
            GimbalAngleRotationMode.RELATIVE_ANGLE,
            pitchDelta,
            0.0,
            yawDelta,
            false,
            true,
            false,
            GIMBAL_RECENTER_DURATION_SEC,
            false,
            GIMBAL_RECENTER_TIMEOUT_SEC,
        )
        performAction(
            KeyTools.createKey(DJIGimbalKey.KeyRotateByAngle, ComponentIndexType.LEFT_OR_MAIN),
            rotation,
        )
    }

    override suspend fun rotateGimbalToPitch(pitch: Double) {
        val rotation = GimbalAngleRotation(
            GimbalAngleRotationMode.ABSOLUTE_ANGLE,
            pitch.coerceIn(-90.0, 30.0),
            0.0,
            0.0,
            false,
            true,
            true,
            GIMBAL_NADIR_ROTATION_DURATION_SEC,
            false,
            GIMBAL_NADIR_ROTATION_TIMEOUT_SEC,
        )
        performAction(
            KeyTools.createKey(DJIGimbalKey.KeyRotateByAngle, ComponentIndexType.LEFT_OR_MAIN),
            rotation,
        )
    }

    override suspend fun startShootPhoto() {
        performEmptyAction(
            KeyTools.createCameraKey(
                DJICameraKey.KeyStartShootPhoto,
                ComponentIndexType.LEFT_OR_MAIN,
                CameraLensType.CAMERA_LENS_WIDE,
            ),
        )
    }

    override suspend fun startRecord() {
        performEmptyAction(
            KeyTools.createCameraKey(
                DJICameraKey.KeyStartRecord,
                ComponentIndexType.LEFT_OR_MAIN,
                CameraLensType.CAMERA_LENS_WIDE,
            ),
        )
    }

    override suspend fun stopRecord() {
        performEmptyAction(
            KeyTools.createCameraKey(
                DJICameraKey.KeyStopRecord,
                ComponentIndexType.LEFT_OR_MAIN,
                CameraLensType.CAMERA_LENS_WIDE,
            ),
        )
    }

    override suspend fun setStreamSource(source: String) {
        setValue(
            KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, ComponentIndexType.LEFT_OR_MAIN),
            when (source.lowercase(Locale.US)) {
                "thermal", "infrared", "ir" -> CameraVideoStreamSourceType.INFRARED_CAMERA
                "zoom" -> CameraVideoStreamSourceType.ZOOM_CAMERA
                "wide", "visible", "default" -> CameraVideoStreamSourceType.WIDE_CAMERA
                else -> throw IllegalArgumentException("unsupported-camera-stream-source:$source")
            },
        )
    }

    override suspend fun setZoom(ratio: Double) {
        setValue(
            KeyTools.createKey(CameraKey.KeyCameraZoomRatios, ComponentIndexType.LEFT_OR_MAIN),
            ratio.coerceIn(1.0, 200.0),
        )
    }

    override suspend fun setNightScene(enabled: Boolean) {
        setValue(
            KeyTools.createCameraKey(
                DJICameraKey.KeyCameraNightSceneMode,
                ComponentIndexType.LEFT_OR_MAIN,
                CameraLensType.CAMERA_LENS_WIDE,
            ),
            if (enabled) CameraNightSceneMode.ENABLE else CameraNightSceneMode.DISABLE,
        )
    }

    override suspend fun setLaserFillLight(enabled: Boolean) {
        setValue(
            KeyTools.createCameraKey(
                DJICameraKey.KeyLaserFillLightEnabled,
                ComponentIndexType.LEFT_OR_MAIN,
                CameraLensType.CAMERA_LENS_WIDE,
            ),
            enabled,
        )
    }

    private suspend fun performEmptyAction(key: DJIKey.ActionKey<EmptyMsg, EmptyMsg>) {
        performAction(key, EmptyMsg())
    }

    private suspend fun <T> performAction(key: DJIKey.ActionKey<T, EmptyMsg>, value: T) {
        withTimeout(MSDK_ACTION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                keyManager.performAction(
                    key,
                    value,
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

    private suspend fun <T> getValue(key: DJIKey<T>): T? {
        return withTimeout(MSDK_ACTION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
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

    private suspend fun <T> setValue(key: DJIKey<T>, value: T) {
        withTimeout(MSDK_ACTION_TIMEOUT_MS) {
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
    }

    private suspend fun performFlyToAction(
        key: DJIKey.ActionKey<FlyToPointInfo, FlyToResult>,
        value: FlyToPointInfo,
    ) {
        withTimeout(MSDK_ACTION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                keyManager.performAction(
                    key,
                    value,
                    object : CommonCallbacks.CompletionCallbackWithParam<FlyToResult> {
                        override fun onSuccess(result: FlyToResult?) {
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

    private suspend fun enableVirtualStick() {
        suspendCancellableCoroutine<Unit> { continuation ->
            VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
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

    private fun buildVirtualStickParam(key: String): VirtualStickFlightControlParam {
        val normalized = key.lowercase(Locale.US)
        val verticalVelocity = when (normalized) {
            "arrowup", "up" -> 1.0
            "arrowdown", "down" -> -0.7
            else -> 0.0
        }
        val pitchVelocity = when (normalized) {
            "keyw", "w", "forward" -> 2.0
            "keys", "s", "backward" -> -2.0
            else -> 0.0
        }
        val rollVelocity = when (normalized) {
            "keyd", "d", "right" -> 2.0
            "keya", "a", "left" -> -2.0
            else -> 0.0
        }
        if (normalized !in SUPPORTED_VIRTUAL_STICK_KEYS) {
            throw IllegalArgumentException("unsupported-virtual-stick-key:$key")
        }
        return VirtualStickFlightControlParam(
            rollVelocity,
            pitchVelocity,
            0.0,
            verticalVelocity,
            VerticalControlMode.VELOCITY,
            RollPitchControlMode.VELOCITY,
            YawControlMode.ANGULAR_VELOCITY,
            FlightCoordinateSystem.BODY,
        )
    }

    companion object {
        private const val MSDK_ACTION_TIMEOUT_MS: Long = 8_000
        private const val MIN_VIRTUAL_STICK_DURATION_MS: Long = 100
        private const val MAX_VIRTUAL_STICK_DURATION_MS: Long = 160_000
        private const val VIRTUAL_STICK_SEND_INTERVAL_MS: Long = 100
        private const val MIN_FLY_TO_SPEED_MPS: Double = 1.0
        private const val MAX_FLY_TO_SPEED_MPS: Double = 15.0
        private const val GIMBAL_NADIR_ROTATION_DURATION_SEC: Double = 2.0
        private const val GIMBAL_NADIR_ROTATION_TIMEOUT_SEC: Int = 5
        private const val GIMBAL_RECENTER_DURATION_SEC: Double = 1.5
        private const val GIMBAL_RECENTER_TIMEOUT_SEC: Int = 5
        private val SUPPORTED_VIRTUAL_STICK_KEYS = setOf(
            "arrowup",
            "arrowdown",
            "keyw",
            "keya",
            "keys",
            "keyd",
            "up",
            "down",
            "forward",
            "backward",
            "left",
            "right",
            "hover",
        )
    }
}

class DualStreamMsdkCommandExecutor(
    private val dualStreamExecutor: DualStreamCommandExecutor,
    private val flightControlClient: FlightControlActionClient? = null,
    private val gimbalClient: GimbalActionClient? = flightControlClient as? GimbalActionClient,
    private val cameraClient: CameraActionClient? = flightControlClient as? CameraActionClient,
) : MsdkCommandExecutor {

    override suspend fun execute(
        aircraftSn: String,
        command: MsdkCommandResponse,
    ): MsdkCommandExecutionResult {
        when (val flightAction = normalizeFlightAction(command.command)) {
            "takeoff" -> return executeFlightAction(flightAction) { startTakeoff() }
            "return_home" -> return executeFlightAction(flightAction) { startGoHome() }
            "cancel_return_home" -> return executeFlightAction(flightAction) { stopGoHome() }
            "land" -> return executeFlightAction(flightAction) { startAutoLanding() }
            "stop_landing" -> return executeFlightAction(flightAction) { stopAutoLanding() }
            "emergency_stop" -> return executeFlightAction(flightAction) { emergencyStop() }
            "hover" -> return executeFlightAction(flightAction) { hover() }
            "stop_fly_to_point" -> return executeFlightAction(flightAction) { stopFlyToPoint() }
            "virtual_stick" -> return executeVirtualStick(command)
            "fly_to_point" -> return executeFlyToPoint(command)
            "navigation_light" -> return executeNavigationLight(command)
        }

        when (val payloadAction = normalizePayloadAction(command.command)) {
            "gimbal_reset" -> return executeGimbalAction(payloadAction) { resetGimbal() }
            "gimbal_rotate" -> return executeGimbalRotate(command)
            "camera_start_photo" -> return executeCameraAction(payloadAction) { startShootPhoto() }
            "camera_start_record" -> return executeCameraAction(payloadAction) { startRecord() }
            "camera_stop_record" -> return executeCameraAction(payloadAction) { stopRecord() }
            "camera_stream_source" -> return executeCameraStreamSource(command)
            "camera_zoom" -> return executeCameraZoom(command)
            "night_scene" -> return executeNightScene(command)
            "laser_fill_light" -> return executeLaserFillLight(command)
        }

        val dualStreamAction = when (command.command.lowercase(Locale.US)) {
            "start", "start_stream", "start-stream" -> "start"
            "stop", "stop_stream", "stop-stream" -> "stop"
            "focus_visible", "focus-visible" -> "focus-visible"
            "focus_thermal", "focus-thermal" -> "focus-thermal"
            else -> return MsdkCommandExecutionResult(
                status = "FAILED",
                message = "unsupported-msdk-command:${command.command}:executor-not-wired",
            )
        }

        val result = dualStreamExecutor.executeCommand(aircraftSn, dualStreamAction)
        return MsdkCommandExecutionResult(
            status = result.status.uppercase(Locale.US),
            message = result.message,
        )
    }

    private suspend fun executeGimbalAction(
        action: String,
        block: suspend GimbalActionClient.() -> Unit,
    ): MsdkCommandExecutionResult {
        val client = gimbalClient ?: return MsdkCommandExecutionResult(
            status = "FAILED",
            message = "unsupported-msdk-command:$action:gimbal-executor-not-wired",
        )
        client.block()
        return MsdkCommandExecutionResult(
            status = "APPLIED",
            message = "$action applied",
        )
    }

    private suspend fun executeCameraAction(
        action: String,
        block: suspend CameraActionClient.() -> Unit,
    ): MsdkCommandExecutionResult {
        val client = cameraClient ?: return MsdkCommandExecutionResult(
            status = "FAILED",
            message = "unsupported-msdk-command:$action:camera-executor-not-wired",
        )
        client.block()
        return MsdkCommandExecutionResult(
            status = "APPLIED",
            message = "$action applied",
        )
    }

    private suspend fun executeFlightAction(
        action: String,
        block: suspend FlightControlActionClient.() -> Unit,
    ): MsdkCommandExecutionResult {
        val client = flightControlClient ?: return MsdkCommandExecutionResult(
            status = "FAILED",
            message = "unsupported-msdk-command:$action:flight-executor-not-wired",
        )
        client.block()
        return MsdkCommandExecutionResult(
            status = "APPLIED",
            message = "$action applied",
        )
    }

    private suspend fun executeVirtualStick(command: MsdkCommandResponse): MsdkCommandExecutionResult {
        val client = flightControlClient ?: return MsdkCommandExecutionResult(
            status = "FAILED",
            message = "unsupported-msdk-command:virtual_stick:flight-executor-not-wired",
        )
        val key = command.params?.get("key")?.toString()
            ?: return MsdkCommandExecutionResult(
                status = "FAILED",
                message = "virtual-stick-key-required",
            )
        val durationMs = command.params["duration_ms"].asLongOrNull()
            ?: command.params["durationMs"].asLongOrNull()
            ?: 800L
        client.sendVirtualStick(key, durationMs)
        return MsdkCommandExecutionResult(
            status = "APPLIED",
            message = "virtual_stick applied",
        )
    }

    private suspend fun executeFlyToPoint(command: MsdkCommandResponse): MsdkCommandExecutionResult {
        val client = flightControlClient ?: return MsdkCommandExecutionResult(
            status = "FAILED",
            message = "unsupported-msdk-command:fly_to_point:flight-executor-not-wired",
        )
        val params = command.params.orEmpty()
        val latitude = params["latitude"].asDoubleOrNull()
            ?: params["targetLatitude"].asDoubleOrNull()
            ?: return MsdkCommandExecutionResult(status = "FAILED", message = "fly-to-point-latitude-required")
        val longitude = params["longitude"].asDoubleOrNull()
            ?: params["targetLongitude"].asDoubleOrNull()
            ?: return MsdkCommandExecutionResult(status = "FAILED", message = "fly-to-point-longitude-required")
        val height = params["height"].asDoubleOrNull()
            ?: params["targetHeight"].asDoubleOrNull()
            ?: return MsdkCommandExecutionResult(status = "FAILED", message = "fly-to-point-height-required")
        val speed = params["speed"].asDoubleOrNull()
            ?: params["maxSpeed"].asDoubleOrNull()
            ?: 5.0
        client.flyToPoint(latitude, longitude, height, speed)
        return MsdkCommandExecutionResult(
            status = "APPLIED",
            message = "fly_to_point applied",
        )
    }

    private suspend fun executeNavigationLight(command: MsdkCommandResponse): MsdkCommandExecutionResult {
        val client = flightControlClient ?: return MsdkCommandExecutionResult(
            status = "FAILED",
            message = "unsupported-msdk-command:navigation_light:flight-executor-not-wired",
        )
        val enabled = command.params?.get("enabled").asBooleanOrNull() ?: true
        client.setNavigationLight(enabled)
        return MsdkCommandExecutionResult(
            status = "APPLIED",
            message = "navigation_light applied",
        )
    }

    private suspend fun executeGimbalRotate(command: MsdkCommandResponse): MsdkCommandExecutionResult {
        val params = command.params.orEmpty()
        val pitch = params["pitch"].asDoubleOrNull() ?: 0.0
        val yaw = params["yaw"].asDoubleOrNull() ?: 0.0
        val roll = params["roll"].asDoubleOrNull() ?: 0.0
        return executeGimbalAction("gimbal_rotate") {
            rotateGimbal(pitch, yaw, roll)
        }
    }

    private suspend fun executeCameraStreamSource(command: MsdkCommandResponse): MsdkCommandExecutionResult {
        val source = command.params?.get("source")?.toString()
            ?: command.params?.get("streamSource")?.toString()
            ?: return MsdkCommandExecutionResult(status = "FAILED", message = "camera-stream-source-required")
        return executeCameraAction("camera_stream_source") {
            setStreamSource(source)
        }
    }

    private suspend fun executeCameraZoom(command: MsdkCommandResponse): MsdkCommandExecutionResult {
        val ratio = command.params?.get("ratio").asDoubleOrNull()
            ?: command.params?.get("zoomRatio").asDoubleOrNull()
            ?: return MsdkCommandExecutionResult(status = "FAILED", message = "camera-zoom-ratio-required")
        return executeCameraAction("camera_zoom") {
            setZoom(ratio)
        }
    }

    private suspend fun executeNightScene(command: MsdkCommandResponse): MsdkCommandExecutionResult {
        val enabled = command.params?.get("enabled").asBooleanOrNull() ?: true
        return executeCameraAction("night_scene") {
            setNightScene(enabled)
        }
    }

    private suspend fun executeLaserFillLight(command: MsdkCommandResponse): MsdkCommandExecutionResult {
        val enabled = command.params?.get("enabled").asBooleanOrNull() ?: true
        return executeCameraAction("laser_fill_light") {
            setLaserFillLight(enabled)
        }
    }

    private fun normalizeFlightAction(command: String): String? {
        return when (command.lowercase(Locale.US)) {
            "takeoff", "start_takeoff", "start-takeoff" -> "takeoff"
            "return_home", "return-home", "go_home", "go-home", "start_go_home", "start-go-home" -> "return_home"
            "cancel_return_home", "cancel-return-home", "cancel_go_home", "cancel-go-home", "stop_go_home", "stop-go-home" -> "cancel_return_home"
            "land", "landing", "start_landing", "start-landing", "start_auto_landing", "start-auto-landing" -> "land"
            "stop_landing", "stop-landing", "cancel_landing", "cancel-landing", "stop_auto_landing", "stop-auto-landing" -> "stop_landing"
            "emergency_stop", "emergency-stop", "stop", "stop_flight", "stop-flight" -> "emergency_stop"
            "hover" -> "hover"
            "stop_fly_to_point", "stop-fly-to-point" -> "stop_fly_to_point"
            "virtual_stick", "virtual-stick" -> "virtual_stick"
            "fly_to_point", "fly-to-point" -> "fly_to_point"
            "navigation_light", "navigation-light", "aircraft_light", "aircraft-light" -> "navigation_light"
            else -> null
        }
    }

    private fun normalizePayloadAction(command: String): String? {
        return when (command.lowercase(Locale.US)) {
            "gimbal_reset", "gimbal-reset", "gimbal_recenter", "gimbal-recenter" -> "gimbal_reset"
            "gimbal_rotate", "gimbal-rotate" -> "gimbal_rotate"
            "camera_start_photo", "camera-start-photo", "start_photo", "start-photo", "shoot_photo", "shoot-photo" -> "camera_start_photo"
            "camera_start_record", "camera-start-record", "start_record", "start-record" -> "camera_start_record"
            "camera_stop_record", "camera-stop-record", "stop_record", "stop-record" -> "camera_stop_record"
            "camera_stream_source", "camera-stream-source", "stream_source", "stream-source" -> "camera_stream_source"
            "camera_zoom", "camera-zoom" -> "camera_zoom"
            "night_scene", "night-scene" -> "night_scene"
            "laser_fill_light", "laser-fill-light" -> "laser_fill_light"
            else -> null
        }
    }

    private fun Any?.asLongOrNull(): Long? {
        return when (this) {
            is Number -> toLong()
            is String -> toLongOrNull()
            else -> null
        }
    }

    private fun Any?.asDoubleOrNull(): Double? {
        return when (this) {
            is Number -> toDouble()
            is String -> toDoubleOrNull()
            else -> null
        }
    }

    private fun Any?.asBooleanOrNull(): Boolean? {
        return when (this) {
            is Boolean -> this
            is String -> when (lowercase(Locale.US)) {
                "true", "1", "yes", "on", "enabled" -> true
                "false", "0", "no", "off", "disabled" -> false
                else -> null
            }
            else -> null
        }
    }
}
