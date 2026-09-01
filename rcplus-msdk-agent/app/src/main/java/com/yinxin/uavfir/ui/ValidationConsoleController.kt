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
            val distance = when (limit.distanceLimitEnabled) {
                true -> limit.distanceLimitMeters?.let { "限距${it}m" } ?: "限距--"
                false -> "未限距"
                null -> "限距--"
            }
            return "飞行限制\n$height / $distance"
        }

        private fun buildTaskSpaceText(storageStatus: DjiStorageStatus): String {
            val free = storageStatus.freeBytes
            val total = storageStatus.totalBytes
            if (free == null || total == null || total <= 0L) {
                return "任务空间\n读取中"
            }
            return "任务空间\n可用 ${formatGb(free)} / 总计 ${formatGb(total)}"
        }

        private fun buildAircraftStatusText(deviceState: DjiDeviceState): String {
            val deviceName = resolveAircraftDisplayName(
                aircraftName = deviceState.aircraftName,
                aircraftModel = deviceState.aircraftModel,
            )
            val hasAircraftLink = when (deviceState.connectionState) {
                AgentConnectionState.AIRCRAFT_CONNECTED,
                AgentConnectionState.CAPABILITY_READY,
                AgentConnectionState.STREAMING,
                AgentConnectionState.DEGRADED,
                -> true
                AgentConnectionState.IDLE,
                AgentConnectionState.SDK_READY,
                AgentConnectionState.ERROR,
                -> false
            }
            val status = if (hasAircraftLink && deviceName != UNKNOWN_AIRCRAFT_NAME) "已连接" else "未连接"
            return "当前飞行器\n$deviceName    $status"
        }

        private fun resolveAircraftDisplayName(
            aircraftName: String?,
            aircraftModel: String?,
        ): String {
            val name = aircraftName?.trim().orEmpty()
            if (name.isNotBlank() && !isUnknownDeviceValue(name)) return name

            val value = aircraftModel?.trim().orEmpty()
            return when {
                isUnknownDeviceValue(value) -> UNKNOWN_AIRCRAFT_NAME
                value.equals("M300_RTK", ignoreCase = true) -> "DJI M300 RTK"
                value.equals("M350_RTK", ignoreCase = true) -> "DJI M350 RTK"
                value.contains("MATRICE_4T", ignoreCase = true) -> "MATRICE 4T"
                value.contains("MATRICE_4_SERIES", ignoreCase = true) -> "DJI MATRICE 4 SERIES"
                value.contains("Matrice 4T", ignoreCase = true) -> "MATRICE 4T"
                else -> value.replace('_', ' ')
            }
        }

        private fun isUnknownDeviceValue(value: String): Boolean {
            return value.isBlank() ||
                value.equals("UNKNOWN", ignoreCase = true) ||
                value.equals("UNRECOGNIZED", ignoreCase = true)
        }

        private fun formatGb(bytes: Long): String {
            return String.format(Locale.US, "%.1fGB", bytes / 1_000_000_000.0)
        }

        private const val UNKNOWN_AIRCRAFT_NAME = "UNKNOWN"
    }
}
