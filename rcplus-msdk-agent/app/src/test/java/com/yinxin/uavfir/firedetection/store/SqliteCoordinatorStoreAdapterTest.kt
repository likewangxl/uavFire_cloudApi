package com.yinxin.uavfir.firedetection.store

import com.yinxin.uavfir.api.LaserRangefinderResult
import com.yinxin.uavfir.firedetection.BoundLaserSample
import com.yinxin.uavfir.firedetection.CoordinatorSession
import com.yinxin.uavfir.firedetection.DetectionKind
import com.yinxin.uavfir.firedetection.FireLocalizationResult
import com.yinxin.uavfir.firedetection.GeoMethod
import com.yinxin.uavfir.firedetection.LaserOperationBinding
import com.yinxin.uavfir.firedetection.LocationStatus
import com.yinxin.uavfir.firedetection.NormalizedRoi
import com.yinxin.uavfir.firedetection.TerminalPersistenceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SqliteCoordinatorStoreAdapterTest {
    private val clock = MutableStoreClock(elapsedMillis = 1_100, wallMillis = 100_000)
    private val records = CanonicalCoordinatorStoreRecordFactory(
        clock,
        CoordinatorModelIdentity("visible-v1", "a".repeat(64), 960, "ncnn"),
    )
    private val roi = NormalizedRoi(.2f, .2f, .4f, .4f)
    private val session = CoordinatorSession("session-1", "event-1", "task-1", DetectionKind.FIRE, 1, roi)
    private val request = TerminalPersistenceRequest(
        "session-1",
        "event-1",
        "11111111-1111-4111-8111-111111111111",
        LocationStatus.PRECISE,
        GeoMethod.LASER_RANGEFINDER,
    )

    @Test
    fun terminalUsesOneMonotonicWallAnchorForOrderedLaserSamples() {
        val record = records.terminal(session, request, precise(listOf(800, 900, 1_000)), 6)
        val report = record.report as PreciseTerminalReport

        assertEquals(listOf(99_700L, 99_800L, 99_900L), report.laserSamples.map { it.eventTimestampWallMillis })
        assertEquals(100_000L, record.eventTimestampWallMillis)
        assertEquals(listOf(99_700L, 99_800L, 99_900L),
            Regex("\\\"eventTimestamp\\\":([0-9]+)").findAll(record.payload)
                .map { it.groupValues[1].toLong() }.drop(1).toList())
    }

    @Test
    fun futureMonotonicLaserObservationIsRejectedInsteadOfInventingWallTime() {
        assertThrows(IllegalArgumentException::class.java) {
            records.terminal(session, request, precise(listOf(800, 900, 1_101)), 6)
        }
    }

    @Test
    fun stagedPayloadCarriesDurableReporterAndReleaseIdentityWithoutImageData() {
        val record = records.terminal(session, request, precise(listOf(800, 900, 1_000)), 6)
        listOf(
            "\"agentId\":\"uavfire-agent\"",
            "\"droneSn\":\"task-1\"",
            "\"taskId\":\"task-1\"",
            "\"detectionKind\":\"FIRE\"",
            "\"modelVersion\":\"visible-v1\"",
            "\"modelHash\":\"${"a".repeat(64)}\"",
            "\"policyVersion\":\"agent-visible-v1\"",
            "\"inputSize\":960",
            "\"runtime\":\"ncnn\"",
            "\"visibleRoi\"",
        ).forEach { assertTrue("missing $it", record.payload.contains(it)) }
        assertTrue(!record.payload.contains("initialVisibleRoi"))
        assertTrue(!record.payload.contains("image"))
    }

    private fun precise(times: List<Long>): FireLocalizationResult.Precise {
        val binding = LaserOperationBinding("session-1", "event-1", roi, 7, 9, 700, 1_200)
        val samples = times.mapIndexed { index, time ->
            BoundLaserSample(
                binding,
                hardwareOperationGeneration = 9,
                observationSequence = index + 1L,
                sampledAtMonotonicMs = time,
                measurement = LaserRangefinderResult(
                    34.0 + index * .000001,
                    109.0 + index * .000001,
                    100.0,
                    60.0,
                    "NORMAL",
                    .3,
                    .3,
                ),
            )
        }
        return FireLocalizationResult.Precise(
            DetectionKind.FIRE,
            34.000001,
            109.000001,
            100.0,
            60.0,
            3.0,
            samples,
            roi,
            7,
        )
    }
}
