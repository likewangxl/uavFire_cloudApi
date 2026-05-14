package com.yinxin.uavfir.api

import com.yinxin.uavfir.sdk.CameraCapability
import com.yinxin.uavfir.sdk.DjiDeviceSessionAdapter
import com.yinxin.uavfir.session.AgentConnectionState
import com.yinxin.uavfir.session.DualStreamCommandExecutor
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
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private var loopJob: Job? = null
    private var lastReportedCapability: CameraCapability? = null

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
    }

    fun stop() {
        debug("stopping runtime loop")
        loopJob?.cancel()
        loopJob = null
    }

    suspend fun tickOnce(droneSn: String) {
        debug("tick start for $droneSn")
        val deviceState = runCatching { deviceSession.initialize() }
            .getOrElse { throwable ->
                onError("device-session", throwable)
                safeReportStatus(
                    droneSn = droneSn,
                    connectionState = AgentConnectionState.ERROR,
                    message = "runtime-loop-device-session-error:${throwable.message ?: throwable::class.simpleName}",
                    runtimeStatus = sessionManager.runtimeStatus(),
                )
                return
            }

        safeReportHeartbeat(
            droneSn = droneSn,
            connectionState = deviceState.connectionState,
        )
        debug("heartbeat reported for $droneSn state=${deviceState.connectionState}")
        safeReportStatus(
            droneSn = droneSn,
            connectionState = deviceState.connectionState,
            message = buildStatusMessage(deviceState.connectionState),
            runtimeStatus = sessionManager.runtimeStatus(),
        )
        debug("status reported for $droneSn session=${sessionManager.sessionState}")
        deviceState.capability?.let { capability ->
            if (capability != lastReportedCapability) {
                safeReportCapability(droneSn, capability)
                lastReportedCapability = capability
                debug("capability reported for $droneSn visible=${capability.visibleSupported} thermal=${capability.thermalSupported}")
            }
        }
        runCatching { commandPoller.pollOnce(droneSn) }
            .onFailure { onError("command-poll", it) }
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

    private fun buildStatusMessage(connectionState: AgentConnectionState): String {
        return "runtime-loop connection=$connectionState session=${sessionManager.sessionState.name}"
    }

    companion object {
        private const val TAG = "AgentRuntimeLoop"
        const val DEFAULT_INTERVAL_MS: Long = 5_000
    }
}

interface CommandPoller {
    suspend fun pollOnce(droneSn: String)
}
