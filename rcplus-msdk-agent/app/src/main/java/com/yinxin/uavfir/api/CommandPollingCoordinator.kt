package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamSessionManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class CommandPollingCoordinator(
    private val client: AgentBackendClient,
    private val sessionManager: DualStreamSessionManager? = null,
    private val commandExecutor: MsdkCommandExecutor? = sessionManager?.let { DualStreamMsdkCommandExecutor(it) },
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
) : CommandPoller {
    override suspend fun pollOnce(droneSn: String) {
        pollLegacyDualStreamCommand(droneSn)
        pollMsdkCommand(droneSn)
    }

    private suspend fun pollLegacyDualStreamCommand(droneSn: String) {
        val sessionManager = sessionManager ?: return
        val command = client.pollCommand(droneSn) ?: return
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

        val result = runCatching {
            withTimeout(commandTimeoutMs) {
                sessionManager.executeCommand(droneSn, command.action)
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
        )
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
        const val DEFAULT_COMMAND_TIMEOUT_MS: Long = 15_000
    }
}
