package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamCommandExecutor
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.FlyToOperationType
import dji.sdk.keyvalue.value.flightcontroller.FlyToPointInfo
import dji.sdk.keyvalue.value.flightcontroller.FlyToResult
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
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
}

class DjiFlightControlActionClient : FlightControlActionClient {
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
        val target = FlyToPointInfo(
            FlyToOperationType.NEW_ORDER,
            LocationCoordinate3D(latitude, longitude, height),
            speed.coerceIn(MIN_FLY_TO_SPEED_MPS, MAX_FLY_TO_SPEED_MPS),
            height,
        )
        performFlyToAction(FlightControllerKey.KeyFlyToPointEx.create(), target)
    }

    private suspend fun performEmptyAction(key: DJIKey.ActionKey<EmptyMsg, EmptyMsg>) {
        withTimeout(MSDK_ACTION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                keyManager.performAction(
                    key,
                    EmptyMsg(),
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
            pitchVelocity,
            rollVelocity,
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
        private const val MAX_VIRTUAL_STICK_DURATION_MS: Long = 5_000
        private const val VIRTUAL_STICK_SEND_INTERVAL_MS: Long = 100
        private const val MIN_FLY_TO_SPEED_MPS: Double = 1.0
        private const val MAX_FLY_TO_SPEED_MPS: Double = 15.0
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
            "hover" -> return executeFlightAction(flightAction) { emergencyStop() }
            "stop_fly_to_point" -> return executeFlightAction(flightAction) { emergencyStop() }
            "virtual_stick" -> return executeVirtualStick(command)
            "fly_to_point" -> return executeFlyToPoint(command)
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
}
