package com.yinxin.uavfir.wayline

import com.dji.wpmzsdk.manager.WPMZManager
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import java.io.File
import java.util.zip.ZipFile

interface WaypointMissionDiagnostics {
    fun validationErrors(kmzPath: String): List<String>
    fun availableWaylineIds(missionFileName: String): List<Int>?
}

class DjiWaypointMissionDiagnostics : WaypointMissionDiagnostics {
    override fun validationErrors(kmzPath: String): List<String> =
        runCatching {
            WPMZManager.getInstance()
                .checkValidation(kmzPath)
                .value
                ?.map { it.name }
                .orEmpty()
        }.getOrElse { error ->
            listOf("diagnostic_exception:${error.javaClass.simpleName}:${error.message.orEmpty()}")
        }

    override fun availableWaylineIds(missionFileName: String): List<Int>? =
        runCatching {
            WaypointMissionManager.getInstance().getAvailableWaylineIDs(missionFileName)
        }.getOrElse { null }
}

object WaypointMissionDiagnosticFormatter {
    fun formatValidationResult(missionId: String, kmzPath: String, errors: List<String>): String {
        val errorText = errors.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "NoError"
        return "wpmzValidation missionId=$missionId path=$kmzPath errors=$errorText"
    }

    fun formatAvailableWaylineIds(missionId: String, missionFileName: String, ids: List<Int>?): String {
        val idText = ids?.joinToString(",") ?: "diagnostic-unavailable"
        return "availableWaylineIds missionId=$missionId file=$missionFileName ids=$idText"
    }
}

object WaypointMissionFileNames {
    fun startMissionName(kmzFileName: String): String =
        kmzFileName.removeSuffix(".kmz").removeSuffix(".KMZ")
}

object WaypointMissionKmzInspector {
    private val waylineIdRegex = Regex("<wpml:waylineId>\\s*(\\d+)\\s*</wpml:waylineId>")

    fun extractWaylineIds(kmzFile: File): List<Int> =
        runCatching {
            ZipFile(kmzFile).use { zip ->
                val entry = zip.getEntry("wpmz/waylines.wpml") ?: return@use emptyList()
                val wpml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                waylineIdRegex.findAll(wpml)
                    .map { it.groupValues[1].toInt() }
                    .distinct()
                    .toList()
            }
        }.getOrDefault(emptyList())
}
