package com.yinxin.uavfir.wayline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WaypointMissionDiagnosticsTest {

    @Test
    fun formatValidationResult_reportsNoErrorWhenValidationListIsEmpty() {
        assertEquals(
            "wpmzValidation missionId=mission-001 path=/tmp/a.kmz errors=NoError",
            WaypointMissionDiagnosticFormatter.formatValidationResult(
                "mission-001",
                "/tmp/a.kmz",
                emptyList(),
            ),
        )
    }

    @Test
    fun formatValidationResult_reportsAllValidationErrors() {
        assertEquals(
            "wpmzValidation missionId=mission-002 path=/tmp/b.kmz errors=FileParseError,DampintDistOutOfRange",
            WaypointMissionDiagnosticFormatter.formatValidationResult(
                "mission-002",
                "/tmp/b.kmz",
                listOf("FileParseError", "DampintDistOutOfRange"),
            ),
        )
    }

    @Test
    fun formatAvailableWaylineIds_reportsUnavailableWhenMsdkThrows() {
        assertEquals(
            "availableWaylineIds missionId=mission-003 file=c.kmz ids=diagnostic-unavailable",
            WaypointMissionDiagnosticFormatter.formatAvailableWaylineIds(
                "mission-003",
                "c.kmz",
                null,
            ),
        )
    }

    @Test
    fun formatAvailableWaylineIds_reportsReturnedIds() {
        assertEquals(
            "availableWaylineIds missionId=mission-004 file=d.kmz ids=0,2",
            WaypointMissionDiagnosticFormatter.formatAvailableWaylineIds(
                "mission-004",
                "d.kmz",
                listOf(0, 2),
            ),
        )
    }

    @Test
    fun extractWaylineIds_readsIdsFromWaylinesWpml() {
        val kmz = File.createTempFile("wayline-ids", ".kmz")
        ZipOutputStream(kmz.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("wpmz/waylines.wpml"))
            zip.write(
                """
                <kml xmlns:wpml="http://www.dji.com/wpmz/1.0.6">
                  <Document>
                    <Folder><wpml:waylineId>2</wpml:waylineId></Folder>
                    <Folder><wpml:waylineId>0</wpml:waylineId></Folder>
                    <Folder><wpml:waylineId>2</wpml:waylineId></Folder>
                  </Document>
                </kml>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }

        assertEquals(listOf(2, 0), WaypointMissionKmzInspector.extractWaylineIds(kmz))
    }

    @Test
    fun extractWaylineIds_returnsEmptyForMissingWaylinesFile() {
        val kmz = File.createTempFile("wayline-ids-missing", ".kmz")
        ZipOutputStream(kmz.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("wpmz/template.kml"))
            zip.write("<kml />".toByteArray())
            zip.closeEntry()
        }

        assertEquals(emptyList<Int>(), WaypointMissionKmzInspector.extractWaylineIds(kmz))
    }

    @Test
    fun startGuardRejectsUnavailableAircraftDiagnostics() {
        val selection = WaypointMissionStartGuard.selectWaylineIds(null, listOf(0))

        assertEquals(false, selection.canStart)
        assertEquals("available-wayline-ids-unavailable", selection.rejectionReason)
    }

    @Test
    fun startGuardRejectsEmptyAircraftWaylineIds() {
        val selection = WaypointMissionStartGuard.selectWaylineIds(emptyList(), listOf(0))

        assertEquals(false, selection.canStart)
        assertEquals("no-available-wayline-ids-on-aircraft", selection.rejectionReason)
    }

    @Test
    fun startGuardRejectsRequestedIdThatAircraftDoesNotExpose() {
        val selection = WaypointMissionStartGuard.selectWaylineIds(listOf(0), listOf(2))

        assertEquals(false, selection.canStart)
        assertEquals("requested-wayline-ids-unavailable:2", selection.rejectionReason)
    }

    @Test
    fun startGuardUsesAircraftConfirmedWaylineIds() {
        val selection = WaypointMissionStartGuard.selectWaylineIds(listOf(0), null)

        assertEquals(true, selection.canStart)
        assertEquals(listOf(0), selection.waylineIds)
        assertEquals(null, selection.rejectionReason)
    }
}
