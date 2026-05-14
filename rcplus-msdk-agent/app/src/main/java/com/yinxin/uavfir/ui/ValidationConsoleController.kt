package com.yinxin.uavfir.ui

import com.yinxin.uavfir.sdk.DjiDeviceSessionAdapter
import com.yinxin.uavfir.sdk.DjiDeviceState
import com.yinxin.uavfir.stream.BoundStreamState
import com.yinxin.uavfir.session.DualStreamCommandExecutor

class ValidationConsoleController(
    private val deviceSession: DjiDeviceSessionAdapter,
    private val commandExecutor: DualStreamCommandExecutor,
) {
    data class UiResult(
        val statusText: String,
    )

    suspend fun refreshDeviceStatus(droneSn: String): UiResult {
        val deviceState = deviceSession.initialize()
        return UiResult(statusText = buildDeviceStatusText(deviceState))
    }

    suspend fun startDualStream(droneSn: String): UiResult {
        val result = commandExecutor.executeCommand(droneSn, "start")
        return UiResult(
            statusText = buildString {
                append("双流启动结果: ")
                append(result.status)
                result.visibleState?.let {
                    append("\n可见光: ")
                    append(it.toUiStreamState())
                }
                result.thermalState?.let {
                    append("\n红外: ")
                    append(
                        if (result.message != null && it != BoundStreamState.BOUND) {
                            "degraded"
                        } else {
                            it.toUiStreamState()
                        },
                    )
                }
                result.message?.let {
                    append("\n原因: ")
                    append(it)
                }
            },
        )
    }

    private fun buildDeviceStatusText(deviceState: DjiDeviceState): String {
        val capability = deviceState.capability
        return buildString {
            append("连接状态: ")
            append(deviceState.connectionState.name)
            if (capability != null) {
                append("\n可见光: ")
                append(capability.visibleSupported)
                append("\n红外: ")
                append(capability.thermalSupported)
            }
        }
    }

    private fun BoundStreamState.toUiStreamState(): String = when (this) {
        BoundStreamState.BOUND -> "running"
        BoundStreamState.IDLE -> "idle"
    }
}
