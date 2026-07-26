package com.yinxin.uavfir.api

import com.yinxin.uavfir.sdk.CameraCapability
import com.yinxin.uavfir.sdk.DjiDeviceIdentity
import com.yinxin.uavfir.sdk.DjiDeviceSessionAdapter
import com.yinxin.uavfir.session.AgentConnectionState
import com.yinxin.uavfir.session.DualStreamCommandExecutor
import com.yinxin.uavfir.session.DualStreamSessionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AgentRuntimeLoop(
    private val deviceSession: DjiDeviceSessionAdapter,
    private val reporter: AgentReporter,
    private val commandPoller: CommandPoller,
    private val sessionManager: DualStreamCommandExecutor,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val urgentCommandPoller: CommandPoller? = null,
    private val urgentCommandIntervalMs: Long = DEFAULT_URGENT_COMMAND_INTERVAL_MS,
    private val gatewaySn: String = DEFAULT_GATEWAY_SN,
    private val onIdentityActivated: suspend (DjiDeviceIdentity) -> Unit = {},
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
    private val sessionRetryIntervalMs: Long = DEFAULT_SESSION_RETRY_INTERVAL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private var loopJob: Job? = null
    private var urgentCommandJob: Job? = null
    private var lastReportedCapability: CameraCapability? = null
    // 哨兵取半个 Long 负区间：保证首次判定必然超过退避窗，且减法不溢出
    private var lastSessionRetryAtMs: Long = Long.MIN_VALUE / 2
    @Volatile
    private var activeIdentity: DjiDeviceIdentity? = null

    fun start(droneSn: String) {
        if (loopJob?.isActive == true) {
            debug("start skipped, runtime loop already active for $droneSn")
            return
        }
        debug("starting runtime loop for $droneSn")
        loopJob = scope.launch(dispatcher) {
            while (isActive) {
                tickOnce(droneSn)
                delay(intervalMs)
            }
        }
        startUrgentCommandLoop(droneSn)
    }

    fun stop() {
        debug("stopping runtime loop")
        loopJob?.cancel()
        loopJob = null
        urgentCommandJob?.cancel()
        urgentCommandJob = null
    }

    private fun startUrgentCommandLoop(droneSn: String) {
        val poller = urgentCommandPoller ?: return
        if (urgentCommandJob?.isActive == true) {
            return
        }
        urgentCommandJob = scope.launch(dispatcher) {
            while (isActive) {
                val pollSn = activeIdentity?.aircraftSn ?: droneSn.takeIf { it.isNotBlank() }
                if (pollSn != null) {
                    runCatching { poller.pollOnce(pollSn) }
                        .onFailure { onError("urgent-command-poll", it) }
                }
                delay(urgentCommandIntervalMs)
            }
        }
    }

    suspend fun tickOnce(droneSn: String) {
        debug("tick start for $droneSn")
        val deviceState = runCatching { deviceSession.initialize() }
            .getOrElse { throwable ->
                onError("device-session", throwable)
                val fallbackSn = droneSn.takeIf { it.isNotBlank() }
                if (fallbackSn == null) {
                    return
                }
                safeReportStatus(
                    droneSn = fallbackSn,
                    connectionState = AgentConnectionState.ERROR,
                    message = "runtime-loop-device-session-error:${throwable.message ?: throwable::class.simpleName}",
                    runtimeStatus = sessionManager.runtimeStatus(),
                )
                return
            }

        val identity = resolveIdentity(deviceState.identity, droneSn)
        if (identity == null) {
            debug("identity unavailable, skip SN-scoped reporting connection=${deviceState.connectionState}")
            handleIdentityUnavailable(deviceState.connectionState)
            return
        }
        handleIdentityChange(identity)
        val activeDroneSn = identity.aircraftSn

        safeReportHeartbeat(
            droneSn = activeDroneSn,
            connectionState = deviceState.connectionState,
        )
        debug("heartbeat reported for $activeDroneSn state=${deviceState.connectionState}")
        safeReportStatus(
            droneSn = activeDroneSn,
            connectionState = deviceState.connectionState,
            message = buildStatusMessage(deviceState.connectionState),
            runtimeStatus = sessionManager.runtimeStatus(),
        )
        debug("status reported for $activeDroneSn session=${sessionManager.sessionState}")
        deviceState.capability?.let { capability ->
            if (capability != lastReportedCapability) {
                safeReportCapability(activeDroneSn, capability)
                lastReportedCapability = capability
                debug("capability reported for $activeDroneSn visible=${capability.visibleSupported} thermal=${capability.thermalSupported}")
            }
        }
        safeReportMsdkDeviceState(identity, deviceState)
        maybeRestartFailedSession(activeDroneSn, deviceState.connectionState)
        runCatching { commandPoller.pollOnce(activeDroneSn) }
            .onFailure { onError("command-poll", it) }
    }

    /**
     * 起流失败自愈：飞机比 agent 晚上电时，identity 激活后的一次性自动起流常撞上
     * 图传链路未就绪而 FAILED，且没人重试——需手工重启 App 才能恢复直播。
     * 这里在会话 FAILED 且链路非 ERROR 时按固定退避重试 start。
     */
    private suspend fun maybeRestartFailedSession(
        droneSn: String,
        connectionState: AgentConnectionState,
    ) {
        if (sessionManager.sessionState != DualStreamSessionState.FAILED) {
            return
        }
        if (connectionState == AgentConnectionState.ERROR) {
            return
        }
        // 占位身份不起流：流名会挂 UNKNOWN-AIRCRAFT 前缀，前端按真机 SN 永远取不到。
        if (droneSn.startsWith(DjiDeviceIdentity.UNKNOWN_AIRCRAFT_PREFIX)) {
            return
        }
        val now = clockMs()
        if (now - lastSessionRetryAtMs < sessionRetryIntervalMs) {
            return
        }
        lastSessionRetryAtMs = now
        debug("session FAILED, retrying stream start for $droneSn")
        runCatching { sessionManager.executeCommand(droneSn, "start") }
            .onSuccess { result ->
                debug("session retry for $droneSn status=${result.status} message=${result.message ?: "(ok)"}")
            }
            .onFailure { onError("session-retry", it) }
    }

    private fun resolveIdentity(
        discovered: DjiDeviceIdentity?,
        configuredDroneSn: String,
    ): DjiDeviceIdentity? {
        if (discovered?.isValid() == true) {
            return discovered
        }
        return configuredDroneSn
            .takeIf { it.isNotBlank() }
            ?.let {
                DjiDeviceIdentity(
                    gatewaySn = gatewaySn,
                    aircraftSn = it,
                )
            }
    }

    private suspend fun handleIdentityChange(identity: DjiDeviceIdentity) {
        val previous = activeIdentity
        if (previous == identity) {
            return
        }
        if (previous != null && previous.aircraftSn != identity.aircraftSn) {
            safeReportDisconnected(previous)
            lastReportedCapability = null
        }
        activeIdentity = identity
        runCatching { onIdentityActivated(identity) }
            .onFailure { onError("identity-activated", it) }
    }

    private suspend fun handleIdentityUnavailable(connectionState: AgentConnectionState) {
        if (connectionState != AgentConnectionState.SDK_READY && connectionState != AgentConnectionState.ERROR) {
            return
        }
        activeIdentity?.let { previous ->
            safeReportDisconnected(previous)
            activeIdentity = null
            lastReportedCapability = null
        }
    }

    private fun debug(message: String) {
        println("$TAG: $message")
    }

    private suspend fun safeReportHeartbeat(
        droneSn: String,
        connectionState: AgentConnectionState,
    ) {
        runCatching {
            reporter.reportHeartbeat(
                droneSn = droneSn,
                connectionState = connectionState,
                sessionState = sessionManager.sessionState,
            )
        }.onFailure { onError("heartbeat", it) }
    }

    private suspend fun safeReportStatus(
        droneSn: String,
        connectionState: AgentConnectionState,
        message: String,
        runtimeStatus: DualStreamCommandExecutor.RuntimeStatus,
    ) {
        runCatching {
            reporter.reportStatus(
                droneSn = droneSn,
                connectionState = connectionState,
                message = message,
                runtimeStatus = runtimeStatus,
            )
        }.onFailure { onError("status", it) }
    }

    private suspend fun safeReportCapability(
        droneSn: String,
        capability: CameraCapability,
    ) {
        runCatching { reporter.reportCapability(droneSn, capability) }
            .onFailure { onError("capability", it) }
    }

    private suspend fun safeReportMsdkDeviceState(
        identity: DjiDeviceIdentity,
        deviceState: com.yinxin.uavfir.sdk.DjiDeviceState,
    ) {
        val capability = deviceState.capability
        val telemetry = deviceState.telemetry
        runCatching {
            reporter.reportMsdkDeviceState(
                MsdkDeviceStateRequest(
                    gatewaySn = identity.gatewaySn,
                    aircraftSn = identity.aircraftSn,
                    online = deviceState.connectionState != AgentConnectionState.ERROR,
                    connectionState = deviceState.connectionState.name,
                    deviceName = deviceState.aircraftModel,
                    model = deviceState.aircraftModel,
                    latitude = telemetry?.latitude,
                    longitude = telemetry?.longitude,
                    height = telemetry?.height,
                    elevation = telemetry?.elevation,
                    horizontalSpeed = telemetry?.horizontalSpeed,
                    verticalSpeed = telemetry?.verticalSpeed,
                    batteryPercent = telemetry?.batteryPercent,
                    gpsCount = telemetry?.gpsCount,
                    rtkCount = telemetry?.rtkCount,
                    positionFixed = telemetry?.positionFixed,
                    capabilities = mapOf(
                        "takeoff" to true,
                        "land" to true,
                        "returnHome" to true,
                        "cancelReturnHome" to true,
                        "emergencyStop" to true,
                        "hover" to true,
                        "virtualStick" to true,
                        "flyToPoint" to true,
                        "gimbal" to true,
                        "gimbalReset" to true,
                        "gimbalRotate" to true,
                        "camera" to true,
                        "cameraPhoto" to true,
                        "cameraRecord" to true,
                        "cameraStreamSource" to true,
                        "cameraZoom" to true,
                        "nightScene" to true,
                        "navigationLight" to true,
                        "laserFillLight" to true,
                        "visibleStream" to (capability?.visibleSupported == true),
                        "thermalFocus" to (capability?.thermalSupported == true),
                        "thermalSecondStream" to false,
                    ),
                ),
            )
        }.onFailure { onError("msdk-device-state", it) }
    }

    private suspend fun safeReportDisconnected(identity: DjiDeviceIdentity) {
        runCatching {
            reporter.reportMsdkDeviceState(
                MsdkDeviceStateRequest(
                    gatewaySn = identity.gatewaySn,
                    aircraftSn = identity.aircraftSn,
                    online = false,
                    connectionState = "DISCONNECTED",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.onFailure { onError("msdk-device-disconnect", it) }
    }

    private fun buildStatusMessage(connectionState: AgentConnectionState): String {
        return "runtime-loop connection=$connectionState session=${sessionManager.sessionState.name}"
    }

    companion object {
        private const val TAG = "AgentRuntimeLoop"
        const val DEFAULT_GATEWAY_SN: String = "RC_PLUS_LOCAL"
        const val DEFAULT_INTERVAL_MS: Long = 5_000
        const val DEFAULT_URGENT_COMMAND_INTERVAL_MS: Long = 500
        const val DEFAULT_SESSION_RETRY_INTERVAL_MS: Long = 15_000
    }
}

interface CommandPoller {
    suspend fun pollOnce(droneSn: String)
}
