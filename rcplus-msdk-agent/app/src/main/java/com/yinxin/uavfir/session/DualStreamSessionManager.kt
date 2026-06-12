package com.yinxin.uavfir.session

import com.yinxin.uavfir.api.AgentReporter
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
) : DualStreamCommandExecutor {
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

    suspend fun start(droneSn: String) {
        _state.value = DualStreamSessionState.STARTING
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

    override suspend fun executeCommand(
        droneSn: String,
        action: String,
    ): CommandExecutionResult = executeCommand(droneSn, action, null)

    override suspend fun executeCommand(
        droneSn: String,
        action: String,
        thermalMeasureRegion: ThermalMeasureRegion?,
    ): CommandExecutionResult = when (action.lowercase()) {
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
            CommandExecutionResult(status = "applied", message = "thermal-monitoring-enabled")
        }

        "thermal-monitor-off" -> {
            thermalMonitoringEnabled = false
            CommandExecutionResult(status = "applied", message = "thermal-monitoring-disabled")
        }

        "focus-visible" -> runCatching {
            streamProvider.focusVisible(droneSn)
        }.fold(
            onSuccess = { result ->
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

    suspend fun executeCommand(
        droneSn: String,
        action: String,
    ): DualStreamSessionManager.CommandExecutionResult

    suspend fun executeCommand(
        droneSn: String,
        action: String,
        thermalMeasureRegion: ThermalMeasureRegion?,
    ): DualStreamSessionManager.CommandExecutionResult = executeCommand(droneSn, action)
}
