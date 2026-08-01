package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.session.AgentConnectionState
import com.yinxin.uavfir.session.DualStreamSessionState
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class CommandPollingCoordinator(
    private val client: AgentBackendClient,
    private val sessionManager: DualStreamSessionManager? = null,
    private val commandExecutor: MsdkCommandExecutor? = sessionManager?.let { DualStreamMsdkCommandExecutor(it) },
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    private val pollLegacyDualStream: Boolean = true,
    private val pollLegacyDualStreamUrgentOnly: Boolean = false,
    private val pollMsdk: Boolean = true,
    private val commandExecutionDeduplicator: LegacyCommandDeduplicator = LegacyCommandDeduplicator(),
) : CommandPoller {
    override suspend fun pollOnce(droneSn: String) {
        if (pollLegacyDualStream) {
            pollLegacyDualStreamCommand(droneSn)
        }
        if (pollMsdk) {
            pollMsdkCommand(droneSn)
        }
    }

    private suspend fun pollLegacyDualStreamCommand(droneSn: String) {
        val sessionManager = sessionManager ?: return
        val command = client.pollCommand(droneSn) ?: return
        if (pollLegacyDualStreamUrgentOnly && command.urgent != true) {
            return
        }
        if (!command.droneSn.equals(droneSn, ignoreCase = true)) {
            client.ackCommand(
                droneSn = droneSn,
                commandId = command.commandId,
                status = "ignored",
                message = "drone-sn-mismatch:${command.droneSn}",
            )
            return
        }
        if (!command.status.equals("pending", ignoreCase = true)) {
            return
        }
        if (!commandExecutionDeduplicator.tryStart(command.commandId)) {
            return
        }

        try {
            val result = runCatching {
                withTimeout(commandTimeoutMs) {
                    sessionManager.executeCommand(
                        droneSn,
                        command.action,
                        command.thermalMeasureRoi?.toThermalMeasureRegion(),
                    )
                }
            }.getOrElse { throwable ->
                if (throwable is TimeoutCancellationException) {
                    DualStreamSessionManager.CommandExecutionResult(
                        status = "failed",
                        message = "command-timeout:${command.action}",
                    )
                } else {
                    DualStreamSessionManager.CommandExecutionResult(
                        status = "failed",
                        message = throwable.message ?: throwable::class.simpleName ?: "command-failed",
                    )
                }
            }
            client.ackCommand(
                droneSn = droneSn,
                commandId = command.commandId,
                status = result.status,
                message = result.message,
                taskId = command.taskId,
                sourceTs = result.sourceTs ?: command.sourceTs,
                thermalTemperature = result.thermalCenterTemperatureC
                    .takeIf { command.action.equals("measure-thermal-region", ignoreCase = true) },
                thermalMeasureRoi = command.thermalMeasureRoi
                    .takeIf { command.action.equals("measure-thermal-region", ignoreCase = true) },
                eventId = result.eventId,
                fireLat = result.fireLat,
                fireLng = result.fireLng,
                fireAlt = result.fireAlt,
                geoMethod = result.geoMethod,
                geoQuality = result.geoQuality,
                geoErrorRadiusM = result.geoErrorRadiusM,
            )
            client.sendStatus(
                droneSn = droneSn,
                connectionState = connectionStateAfterLegacyCommand(result),
                message = result.message ?: "command-${command.action}-${result.status}",
                runtimeStatus = sessionManager.runtimeStatus(),
            )
        } finally {
            commandExecutionDeduplicator.finish(command.commandId)
        }
    }

    private fun connectionStateAfterLegacyCommand(
        result: DualStreamSessionManager.CommandExecutionResult,
    ): AgentConnectionState {
        if (!result.status.equals("applied", ignoreCase = true)) {
            return AgentConnectionState.DEGRADED
        }
        return when (sessionManager?.sessionState) {
            DualStreamSessionState.RUNNING -> AgentConnectionState.STREAMING
            DualStreamSessionState.STOPPED -> AgentConnectionState.CAPABILITY_READY
            else -> AgentConnectionState.CAPABILITY_READY
        }
    }

    private suspend fun pollMsdkCommand(aircraftSn: String) {
        val commandExecutor = commandExecutor ?: return
        val command = client.pollMsdkCommand(aircraftSn) ?: return
        if (!command.aircraftSn.equals(aircraftSn, ignoreCase = true)) {
            client.ackMsdkCommand(
                aircraftSn = aircraftSn,
                commandId = command.commandId,
                status = "IGNORED",
                message = "aircraft-sn-mismatch:${command.aircraftSn}",
            )
            return
        }
        if (!command.status.equals("PENDING", ignoreCase = true) &&
            !command.status.equals("DISPATCHED", ignoreCase = true)
        ) {
            return
        }

        val result = runCatching {
            withTimeout(commandTimeoutMs) {
                commandExecutor.execute(aircraftSn, command)
            }
        }.getOrElse { throwable ->
            if (throwable is TimeoutCancellationException) {
                MsdkCommandExecutionResult(
                    status = "FAILED",
                    message = "command-timeout:${command.command}",
                )
            } else {
                MsdkCommandExecutionResult(
                    status = "FAILED",
                    message = throwable.message ?: throwable::class.simpleName ?: "command-failed",
                )
            }
        }
        client.ackMsdkCommand(
            aircraftSn = aircraftSn,
            commandId = command.commandId,
            status = result.status,
            message = result.message,
        )
    }

    companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MS: Long = 180_000
    }
}

class LegacyCommandDeduplicator {
    private val inFlight = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun tryStart(commandId: String): Boolean = inFlight.add(commandId)

    fun finish(commandId: String) {
        inFlight.remove(commandId)
    }
}

private fun Map<String, Double>.toThermalMeasureRegion(): ThermalMeasureRegion? {
    val x = this["x"] ?: return null
    val y = this["y"] ?: return null
    val width = this["width"] ?: return null
    val height = this["height"] ?: return null
    return ThermalMeasureRegion(x = x, y = y, width = width, height = height)
}
