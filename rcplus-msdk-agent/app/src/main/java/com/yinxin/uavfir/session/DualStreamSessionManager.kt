package com.yinxin.uavfir.session

import com.yinxin.uavfir.api.AgentReporter
import com.yinxin.uavfir.api.FireConfirmationRequest
import com.yinxin.uavfir.api.FireConfirmationResult
import com.yinxin.uavfir.api.VisibleFireLaserLocator
import com.yinxin.uavfir.firedetection.VisibleDetectorControl
import com.yinxin.uavfir.firedetection.VisibleDetectorStatus
import com.yinxin.uavfir.sdk.DjiDeviceSession
import com.yinxin.uavfir.sdk.DjiDeviceState
import com.yinxin.uavfir.stream.BoundStreamState
import com.yinxin.uavfir.stream.StreamProvider
import com.yinxin.uavfir.stream.StreamStartResult
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import com.yinxin.uavfir.stream.ThermalMeasuredPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DualStreamSessionManager(
    private val streamProvider: StreamProvider,
    visibleFireLaserLocator: VisibleFireLaserLocator? = null,
    private val visibleDetectorControl: VisibleDetectorControl? = null,
    private val fireConfirmationRunner: (suspend (FireConfirmationRequest) -> FireConfirmationResult)? = null,
) : DualStreamCommandExecutor {
    @Volatile
    private var visibleFireLaserLocator: VisibleFireLaserLocator? = visibleFireLaserLocator

    fun attachVisibleFireLaserLocator(locator: VisibleFireLaserLocator) {
        visibleFireLaserLocator = locator
    }

    data class CommandExecutionResult(
        val status: String,
        val message: String? = null,
        val visibleState: BoundStreamState? = null,
        val thermalState: BoundStreamState? = null,
        val playbackStatus: String? = null,
        val thermalCenterTemperatureC: Double? = null,
        val thermalMeasureRegion: ThermalMeasureRegion? = null,
        val thermalMeasurements: List<ThermalMeasuredPoint> = emptyList(),
        val thermalSnapshotPath: String? = null,
        val visibleSnapshotPath: String? = null,
        val eventId: String? = null,
        val fireLat: Double? = null,
        val fireLng: Double? = null,
        val fireAlt: Double? = null,
        val geoMethod: String? = null,
        val geoQuality: String? = null,
        val geoErrorRadiusM: Double? = null,
        val sourceTs: Long? = null,
    )

    private val _state = MutableStateFlow(DualStreamSessionState.INIT)
    val state: StateFlow<DualStreamSessionState> = _state.asStateFlow()
    override val sessionState: DualStreamSessionState
        get() = state.value
    private var lastFailureMessage: String? = null
    private var lastStartResult: StreamStartResult? = null
    private var lastPlaybackStatus: String? = null
    private var lastThermalCenterTemperatureC: Double? = null

    // 火情监测（红外热区探测）开关：仅当后端 FireDetectionService 启动监测时由命令置为 true。
    // 默认 false —— 否则 ThermalHotspotMonitor 会在 session RUNNING 期间每 10s 自发 focusThermal 测温，
    // 把单路共享流不断切到红外，即便用户没开火情监测。M4T 单云台要取红外帧测温必须切红外源，
    // 因此唯一正确的做法是“未开监测就别探测”。
    @Volatile
    var thermalMonitoringEnabled: Boolean = false

    // focus-visible（后端可见光二次确认窗）期间暂停热点监视器，防止它 10s 一次的
    // focusThermal 抢回红外画面让确认窗永远凑不满；focus-thermal 时恢复。
    @Volatile
    private var thermalMonitoringPausedByVisibleFocus: Boolean = false

    // 最近一次起流用的 SN（决定 ZLM 流名）。飞机关机窗口里流可能起在占位身份下，
    // 真机身份回归时 AppServices 靠它判断是否需要换名重推。
    @Volatile
    var activeStreamDroneSn: String? = null
        private set

    suspend fun start(droneSn: String) {
        _state.value = DualStreamSessionState.STARTING
        activeStreamDroneSn = droneSn
        lastFailureMessage = null
        lastStartResult = null
        lastPlaybackStatus = null
        lastThermalCenterTemperatureC = null
        runCatching {
            streamProvider.start(droneSn)
        }.onSuccess { result ->
            lastStartResult = result
            lastPlaybackStatus = result.playbackStatus
            lastThermalCenterTemperatureC = result.thermalCenterTemperatureC
            if (result.isApplied) {
                _state.value = DualStreamSessionState.RUNNING
                lastFailureMessage = result.thermalFailureMessage
            } else {
                _state.value = DualStreamSessionState.FAILED
                lastFailureMessage = result.thermalFailureMessage ?: "visible-stream-not-bound"
            }
        }.onFailure {
            _state.value = DualStreamSessionState.FAILED
            lastFailureMessage = it.message ?: it::class.simpleName ?: "unknown-start-failure"
        }
    }

    suspend fun stop() {
        _state.value = DualStreamSessionState.STOPPING
        lastFailureMessage = null
        runCatching {
            streamProvider.stop()
        }.onSuccess {
            _state.value = DualStreamSessionState.STOPPED
            lastPlaybackStatus = "awaiting-media-url"
            lastThermalCenterTemperatureC = null
        }.onFailure {
            _state.value = DualStreamSessionState.FAILED
            lastFailureMessage = it.message ?: it::class.simpleName ?: "unknown-stop-failure"
        }
    }

    suspend fun initializeAndReport(
        droneSn: String,
        deviceSession: DjiDeviceSession,
        reporter: AgentReporter,
    ): DjiDeviceState {
        val deviceState = deviceSession.initialize()
        deviceState.capability?.let { reporter.reportCapability(droneSn, it) }
        reporter.reportStatus(droneSn, deviceState.connectionState, "device-session-ready", runtimeStatus())
        return deviceState
    }

    suspend fun measureThermalHotspot(
        droneSn: String,
        seedRegion: ThermalMeasureRegion? = null,
    ): CommandExecutionResult = runCatching {
        streamProvider.measureThermalHotspot(droneSn, seedRegion)
    }.fold(
        onSuccess = { result ->
            lastStartResult = result
            lastPlaybackStatus = result.playbackStatus
            lastFailureMessage = result.thermalFailureMessage
            lastThermalCenterTemperatureC = result.thermalCenterTemperatureC
            CommandExecutionResult(
                status = "applied",
                message = result.thermalFailureMessage,
                visibleState = result.visibleState,
                thermalState = result.thermalState,
                playbackStatus = result.playbackStatus,
                thermalCenterTemperatureC = result.thermalCenterTemperatureC,
                thermalMeasureRegion = result.thermalMeasureRegion,
                thermalMeasurements = result.thermalMeasurements,
                thermalSnapshotPath = result.thermalSnapshotPath,
            )
        },
        onFailure = {
            lastFailureMessage = it.message ?: "thermal-hotspot-measurement-failed"
            CommandExecutionResult(
                status = "failed",
                message = lastFailureMessage,
                visibleState = lastStartResult?.visibleState,
                thermalState = lastStartResult?.thermalState,
                playbackStatus = lastPlaybackStatus,
            )
        },
    )

    suspend fun captureVisibleSnapshot(droneSn: String): CommandExecutionResult = runCatching {
        streamProvider.captureVisibleSnapshot(droneSn)
    }.fold(
        onSuccess = { result ->
            lastStartResult = result
            lastPlaybackStatus = result.playbackStatus
            lastFailureMessage = result.thermalFailureMessage
            CommandExecutionResult(
                status = "applied",
                message = result.thermalFailureMessage,
                visibleState = result.visibleState,
                thermalState = result.thermalState,
                playbackStatus = result.playbackStatus,
                visibleSnapshotPath = result.visibleSnapshotPath,
            )
        },
        onFailure = {
            lastFailureMessage = it.message ?: "visible-snapshot-capture-failed"
            CommandExecutionResult(
                status = "failed",
                message = lastFailureMessage,
                visibleState = lastStartResult?.visibleState,
                thermalState = lastStartResult?.thermalState,
                playbackStatus = lastPlaybackStatus,
            )
        },
    )

    override fun runtimeStatus(): DualStreamCommandExecutor.RuntimeStatus = DualStreamCommandExecutor.RuntimeStatus(
        sessionState = state.value,
        visibleState = lastStartResult?.visibleState,
        thermalState = lastStartResult?.thermalState,
        failureReason = lastFailureMessage,
        playbackStatus = lastPlaybackStatus,
        thermalCenterTemperatureC = lastThermalCenterTemperatureC,
    )

    override fun detectorStatus(): VisibleDetectorStatus =
        visibleDetectorControl?.snapshot() ?: VisibleDetectorStatus.disarmed()

    override suspend fun executeCommand(
        droneSn: String,
        action: String,
    ): CommandExecutionResult = executeCommand(droneSn, action, null)

    override suspend fun executeCommand(
        droneSn: String,
        action: String,
        thermalMeasureRegion: ThermalMeasureRegion?,
    ): CommandExecutionResult = executeCommand(
        droneSn = droneSn,
        action = action,
        thermalMeasureRegion = thermalMeasureRegion,
        fireConfirmationRequest = null,
    )

    override suspend fun executeCommand(
        droneSn: String,
        action: String,
        params: Map<String, Any?>,
    ): CommandExecutionResult = executeCommand(droneSn, action)

    suspend fun executeCommand(
        droneSn: String,
        action: String,
        thermalMeasureRegion: ThermalMeasureRegion?,
        fireConfirmationRequest: FireConfirmationRequest?,
    ): CommandExecutionResult = when (action.lowercase()) {
        "visible-detector-arm" -> {
            val status = visibleDetectorControl?.arm()
                ?: return CommandExecutionResult(status = "failed", message = "visible-detector-control-not-wired")
            CommandExecutionResult(status = "applied", message = "detector-state=${status.state}:${status.reason.orEmpty()}")
        }

        "visible-detector-disarm" -> {
            val status = visibleDetectorControl?.disarm()
                ?: return CommandExecutionResult(status = "failed", message = "visible-detector-control-not-wired")
            CommandExecutionResult(status = "applied", message = "detector-state=${status.state}")
        }

        "start" -> {
            start(droneSn)
            if (state.value == DualStreamSessionState.RUNNING) {
                CommandExecutionResult(
                    status = "applied",
                    message = lastFailureMessage,
                    visibleState = lastStartResult?.visibleState,
                    thermalState = lastStartResult?.thermalState,
                    playbackStatus = lastPlaybackStatus,
                )
            } else {
                CommandExecutionResult(
                    status = "failed",
                    message = lastFailureMessage ?: "session-state=${state.value.name}",
                    visibleState = lastStartResult?.visibleState,
                    thermalState = lastStartResult?.thermalState,
                    playbackStatus = lastPlaybackStatus,
                )
            }
        }

        "stop" -> {
            stop()
            if (state.value == DualStreamSessionState.STOPPED) {
                CommandExecutionResult(status = "applied")
            } else {
                CommandExecutionResult(
                    status = "failed",
                    message = lastFailureMessage ?: "session-state=${state.value.name}",
                )
            }
        }

        "thermal-monitor-on" -> {
            thermalMonitoringEnabled = true
            thermalMonitoringPausedByVisibleFocus = false
            CommandExecutionResult(status = "applied", message = "thermal-monitoring-enabled")
        }

        "thermal-monitor-off" -> {
            thermalMonitoringEnabled = false
            thermalMonitoringPausedByVisibleFocus = false
            CommandExecutionResult(status = "applied", message = "thermal-monitoring-disabled")
        }

        "focus-visible" -> runCatching {
            streamProvider.focusVisible(droneSn)
        }.fold(
            onSuccess = { result ->
                if (thermalMonitoringEnabled) {
                    thermalMonitoringPausedByVisibleFocus = true
                    thermalMonitoringEnabled = false
                }
                lastStartResult = result
                lastPlaybackStatus = result.playbackStatus
                lastFailureMessage = null
                lastThermalCenterTemperatureC = null
                CommandExecutionResult(
                    status = "applied",
                    visibleState = result.visibleState,
                    thermalState = result.thermalState,
                    playbackStatus = result.playbackStatus,
                )
            },
            onFailure = {
                lastFailureMessage = it.message ?: "focus-visible-failed"
                CommandExecutionResult(
                    status = "failed",
                    message = lastFailureMessage,
                    visibleState = lastStartResult?.visibleState,
                    thermalState = lastStartResult?.thermalState,
                    playbackStatus = lastPlaybackStatus,
                )
            },
        )

        "focus-thermal" -> runCatching {
            streamProvider.focusThermal(droneSn, null)
        }.fold(
            onSuccess = { result ->
                if (thermalMonitoringPausedByVisibleFocus) {
                    thermalMonitoringPausedByVisibleFocus = false
                    thermalMonitoringEnabled = true
                }
                lastStartResult = result
                lastPlaybackStatus = result.playbackStatus
                lastFailureMessage = result.thermalFailureMessage
                lastThermalCenterTemperatureC = result.thermalCenterTemperatureC
                CommandExecutionResult(
                    status = "applied",
                    message = result.thermalFailureMessage,
                    visibleState = result.visibleState,
                    thermalState = result.thermalState,
                    playbackStatus = result.playbackStatus,
                    thermalCenterTemperatureC = result.thermalCenterTemperatureC,
                    thermalMeasureRegion = result.thermalMeasureRegion,
                    thermalSnapshotPath = result.thermalSnapshotPath,
                )
            },
            onFailure = {
                lastFailureMessage = it.message ?: "focus-thermal-failed"
                CommandExecutionResult(
                    status = "failed",
                    message = lastFailureMessage,
                    visibleState = lastStartResult?.visibleState,
                    thermalState = lastStartResult?.thermalState,
                    playbackStatus = lastPlaybackStatus,
                )
            },
        )

        "measure-thermal-region" -> runCatching {
            val region = thermalMeasureRegion ?: error("thermal-measure-region-required")
            streamProvider.measureThermalRegion(droneSn, region)
        }.fold(
            onSuccess = { result ->
                lastStartResult = result
                lastPlaybackStatus = result.playbackStatus
                lastFailureMessage = result.thermalFailureMessage
                lastThermalCenterTemperatureC = result.thermalCenterTemperatureC
                CommandExecutionResult(
                    status = "applied",
                    message = result.thermalFailureMessage,
                    visibleState = result.visibleState,
                    thermalState = result.thermalState,
                    playbackStatus = result.playbackStatus,
                    thermalCenterTemperatureC = result.thermalCenterTemperatureC,
                    thermalMeasureRegion = result.thermalMeasureRegion,
                    thermalSnapshotPath = result.thermalSnapshotPath,
                )
            },
            onFailure = {
                lastFailureMessage = it.message ?: "focus-thermal-failed"
                CommandExecutionResult(
                    status = "failed",
                    message = lastFailureMessage,
                    visibleState = lastStartResult?.visibleState,
                    thermalState = lastStartResult?.thermalState,
                    playbackStatus = lastPlaybackStatus,
                )
            },
        )

        else -> CommandExecutionResult(
            status = "ignored",
            message = "unsupported-action:$action",
        )
    }
}

private fun Map<String, Any?>.toFireConfirmationRequest(droneSn: String): FireConfirmationRequest? {
    val lat = this["lat"].asDoubleOrNull() ?: this["latitude"].asDoubleOrNull() ?: this["fireLat"].asDoubleOrNull()
    val lng = this["lng"].asDoubleOrNull() ?: this["longitude"].asDoubleOrNull() ?: this["fireLng"].asDoubleOrNull()
    if (lat == null || lng == null) {
        return null
    }
    return FireConfirmationRequest(
        droneSn = droneSn,
        taskId = this["taskId"]?.toString()
            ?: this["task_id"]?.toString()
            ?: "fire-$droneSn",
        fireLat = lat,
        fireLng = lng,
        fireAlt = this["alt"].asDoubleOrNull()
            ?: this["altitude"].asDoubleOrNull()
            ?: this["fireAlt"].asDoubleOrNull(),
    )
}

private fun Any?.asDoubleOrNull(): Double? = when (this) {
    is Number -> toDouble()
    is String -> toDoubleOrNull()
    else -> null
}

private fun Any?.asVisibleRoi(): Map<String, Double>? {
    val raw = this as? Map<*, *> ?: return null
    val result = linkedMapOf<String, Double>()
    for (key in listOf("x", "y", "width", "height")) {
        val value = raw[key].asDoubleOrNull() ?: return null
        result[key] = value
    }
    return result
}

interface DualStreamCommandExecutor {
    data class RuntimeStatus(
        val sessionState: DualStreamSessionState,
        val visibleState: BoundStreamState? = null,
        val thermalState: BoundStreamState? = null,
        val failureReason: String? = null,
        val playbackStatus: String? = null,
        val thermalCenterTemperatureC: Double? = null,
    )

    val sessionState: DualStreamSessionState

    fun runtimeStatus(): RuntimeStatus = RuntimeStatus(sessionState = sessionState)

    fun detectorStatus(): VisibleDetectorStatus = VisibleDetectorStatus.disarmed()

    suspend fun executeCommand(
        droneSn: String,
        action: String,
    ): DualStreamSessionManager.CommandExecutionResult

    suspend fun executeCommand(
        droneSn: String,
        action: String,
        thermalMeasureRegion: ThermalMeasureRegion?,
    ): DualStreamSessionManager.CommandExecutionResult = executeCommand(droneSn, action)

    suspend fun executeCommand(
        droneSn: String,
        action: String,
        params: Map<String, Any?>,
    ): DualStreamSessionManager.CommandExecutionResult = executeCommand(droneSn, action)
}
