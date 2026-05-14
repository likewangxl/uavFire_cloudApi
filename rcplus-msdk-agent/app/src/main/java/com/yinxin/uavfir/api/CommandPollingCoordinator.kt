package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamSessionManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class CommandPollingCoordinator(
    private val client: AgentBackendClient,
    private val sessionManager: DualStreamSessionManager,
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
) : CommandPoller {
    override suspend fun pollOnce(droneSn: String) {
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

    companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MS: Long = 15_000
    }
}
