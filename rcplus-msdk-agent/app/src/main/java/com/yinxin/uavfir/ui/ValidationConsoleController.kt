package com.yinxin.uavfir.ui

import com.yinxin.uavfir.sdk.DjiDeviceSessionAdapter
import com.yinxin.uavfir.sdk.DjiDeviceState
import com.yinxin.uavfir.sdk.DjiHomeStatus
import com.yinxin.uavfir.sdk.DjiStorageStatus
import com.yinxin.uavfir.session.DualStreamCommandExecutor
import com.yinxin.uavfir.session.AgentConnectionState
import java.util.Locale

class ValidationConsoleController(
    private val deviceSession: DjiDeviceSessionAdapter,
    private val commandExecutor: DualStreamCommandExecutor,
) {
    data class UiResult(
        val statusText: String,
    )

    suspend fun refreshDeviceStatus(droneSn: String): UiResult {
        val deviceState = readDeviceState()
        return UiResult(statusText = buildDeviceStatusText(deviceState))
    }

    suspend fun readDeviceState(): DjiDeviceState = deviceSession.initialize()

    suspend fun startDualStream(droneSn: String): UiResult {
        val result = commandExecutor.executeCommand(droneSn, "start")
        return UiResult(
            statusText = buildString {
                append("双流启动结果: ")
                append(result.status)
                result.visibleState?.let {
                    append("\n可见光: ")
                    append(if (it.name == "BOUND") "running" else it.name.lowercase(Locale.US))
                }
                result.thermalState?.let {
                    append("\n红外: ")
                    append(if (it.name == "BOUND") "running" else it.name.lowercase(Locale.US))
                }
                result.message?.let {
                    append("\n原因: ")
                    append(it)
                }
            },
        )
    }

    private fun buildDeviceStatusText(deviceState: DjiDeviceState): String {
        return buildString {
            append("连接状态: ")
            append(deviceState.connectionState)
            deviceState.capability?.let {
                append("\n可见光: ")
                append(it.visibleSupported)
                append("\n红外: ")
                append(it.thermalSupported)
            }
        }
    }

    companion object {
        fun buildHomeStatus(
            deviceState: DjiDeviceState,
            storageStatus: DjiStorageStatus,
        ): DjiHomeStatus {
            return DjiHomeStatus(
                flightLimitText = buildFlightLimitText(deviceState),
                taskSpaceText = buildTaskSpaceText(storageStatus),
                aircraftStatusText = buildAircraftStatusText(deviceState),
            )
        }

        private fun buildFlightLimitText(deviceState: DjiDeviceState): String {
            if (deviceState.connectionState != AgentConnectionState.CAPABILITY_READY) {
                return "飞行限制\n未连接"
            }

            val limit = deviceState.flightLimit
            val height = limit.heightLimitMeters?.let { "限高${it}m" } ?: "限高--"
            val distance = if (limit.distanceLimitEnabled == true) {
                limit.distanceLimitMeters?.let { "限距${it}m" } ?: "限距--"
            } else {
                "未限距"
            }
            return "飞行限制\n$height / $distance"
        }

        private fun buildTaskSpaceText(storageStatus: DjiStorageStatus): String {
            val free = storageStatus.freeBytes
            val total = storageStatus.totalBytes
            if (free == null || total == null || total <= 0L) {
                return "任务空间\n读取中"
            }
            return "任务空间\n${formatGb(free)} / ${formatGb(total)}"
        }

        private fun buildAircraftStatusText(deviceState: DjiDeviceState): String {
            val model = normalizeAircraftModel(deviceState.aircraftModel)
            val status = when (deviceState.connectionState) {
                AgentConnectionState.CAPABILITY_READY -> "已连接"
                AgentConnectionState.SDK_READY -> "未连接"
                AgentConnectionState.AIRCRAFT_CONNECTED -> "已连接"
                AgentConnectionState.STREAMING -> "已连接"
                AgentConnectionState.DEGRADED -> "已连接"
                AgentConnectionState.IDLE -> "未连接"
                AgentConnectionState.ERROR -> "异常"
            }
            return "当前飞行器\n$model    $status"
        }

        private fun normalizeAircraftModel(model: String?): String {
            val value = model?.trim().orEmpty()
            return when {
                value.isBlank() || value == "UNKNOWN" -> "MATRICE 4T"
                value.contains("MATRICE_4T", ignoreCase = true) -> "MATRICE 4T"
                value.contains("MATRICE_4_SERIES", ignoreCase = true) -> "MATRICE 4T"
                value.contains("Matrice 4T", ignoreCase = true) -> "MATRICE 4T"
                else -> value
            }
        }

        private fun formatGb(bytes: Long): String {
            return String.format(Locale.US, "%.1fGB", bytes / 1_000_000_000.0)
        }
    }
}
