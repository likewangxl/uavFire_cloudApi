package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.BoundStreamState
import com.yinxin.uavfir.stream.StreamProvider
import com.yinxin.uavfir.stream.StreamStartResult
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

private const val METERS_PER_LAT_DEG = 111_320.0

@OptIn(ExperimentalCoroutinesApi::class)
class FireConfirmationProcessorTest {
    @Test
    fun run_completes_all_phases_happy_path() = runTest {
        val streamProvider = ConfirmationStreamProvider()
        val sessionManager = DualStreamSessionManager(streamProvider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val flight = RecordingFlightControl()
        val gimbal = RecordingGimbalControl()
        val camera = RecordingCameraControl()
        val missionHold = RecordingMissionHold()
        val api = RecordingDualStreamApi()
        var aircraftLocation = AircraftLocation(latitude = 34.0, longitude = 108.0, altitudeM = 120.0)
        val processor = FireConfirmationProcessor(
            sessionManager = sessionManager,
            flightControl = flight,
            gimbalControl = gimbal,
            cameraControl = camera,
            missionHold = missionHold,
            client = AgentBackendClient(api),
            visibleSnapshotConfirmer = RecordingVisibleSnapshotConfirmer("http://ai/visible.jpg"),
            laserRangefinder = RecordingLaserRangefinder(
                LaserRangefinderResult(
                    latitude = 34.0004,
                    longitude = 108.0005,
                    distanceM = 58.0,
                    state = "NORMAL",
                ),
            ),
            aircraftLocationProvider = { aircraftLocation },
            approachLegsM = listOf(60.0),
            orbitBearingCount = 0,
            flyToTimeoutMs = 5_000,
            pollIntervalMs = 100,
            clockMs = { 1780059017562L },
        )
        flight.onFlyToPoint = { lat, lng, height ->
            aircraftLocation = AircraftLocation(lat, lng, height)
        }

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals(FireConfirmationPhase.VISIBLE_CONFIRM, result.phaseReached)
        assertEquals(91.5, result.closeMeasureTemperatureC ?: -1.0, 1e-6)
        assertEquals(34.0004, result.preciseLat ?: -1.0, 1e-6)
        assertEquals(108.0005, result.preciseLng ?: -1.0, 1e-6)
        assertEquals("laser-rangefinder", result.geoMethod)
        assertEquals(5, result.laserFix?.normalSampleCount)
        assertEquals(true, result.laserFix?.centered)
        assertTrue(result.resetCompleted)
        assertTrue(sessionManager.thermalMonitoringEnabled)
        assertEquals(listOf("hold", "resume"), missionHold.calls)
        assertEquals(listOf("thermal"), camera.streamSources)
        assertEquals(listOf(-45.0, 0.0), gimbal.pitchOrResetCalls)
        assertEquals(listOf(5.0, 1.0), camera.zoomRatios)
        assertEquals(1, api.taskEvents.size)
        assertEquals("task-fire-001", api.lastTaskEventTaskId)
        assertEquals(91.5, api.lastTaskEventBody?.thermalTemperature ?: -1.0, 1e-6)
        assertEquals(1, streamProvider.focusThermalCalls)
        assertEquals(1, streamProvider.focusVisibleCalls)
        assertEquals(1, streamProvider.captureVisibleSnapshotCalls)
    }

    @Test
    fun report_carries_laser_fix_when_high_confidence() = runTest {
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(
                LaserRangefinderResult(34.00041, 108.00051, 386.0, 58.0, "NORMAL"),
            ),
        )

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        val body = processorApi(processor).lastTaskEventBody
        assertEquals(34.00041, body?.fireLat ?: -1.0, 1e-6)
        assertEquals(108.00051, body?.fireLng ?: -1.0, 1e-6)
        assertEquals(386.0, body?.fireAlt ?: -1.0, 1e-6)
        assertEquals("LASER_RANGEFINDER", body?.geoMethod)
        assertEquals(5.0, body?.geoErrorRadiusM ?: -1.0, 1e-6)
    }

    @Test
    fun report_omits_geo_when_laser_unavailable() = runTest {
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(null),
        )

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        val body = processorApi(processor).lastTaskEventBody
        assertNull(body?.fireLat)
        assertNull(body?.fireLng)
        assertNull(body?.fireAlt)
        assertNull(body?.geoMethod)
        assertNull(body?.geoErrorRadiusM)
    }

    @Test
    fun flyto_timeout_triggers_reset() = runTest {
        val streamProvider = ConfirmationStreamProvider()
        val sessionManager = runningSession(streamProvider)
        val flight = RecordingFlightControl()
        val camera = RecordingCameraControl()
        val missionHold = RecordingMissionHold()
        val processor = processor(
            sessionManager = sessionManager,
            flight = flight,
            camera = camera,
            missionHold = missionHold,
            aircraftLocationProvider = { AircraftLocation(34.0, 108.0, 120.0) },
            flyToTimeoutMs = 300,
            pollIntervalMs = 100,
        )

        val result = processor.run(defaultRequest())

        assertFalse(result.success)
        assertEquals(FireConfirmationPhase.FLY_TO, result.phaseReached)
        assertEquals("fly-to-timeout", result.failureReason)
        assertTrue(flight.stopFlyToPointCalled)
        assertTrue(result.resetCompleted)
        assertEquals(listOf("thermal"), camera.streamSources)
        assertEquals(listOf("hold", "resume"), missionHold.calls)
    }

    @Test
    fun legs_progress_toward_laser_fix() = runTest {
        val request = defaultRequest()
        val firstFix = LaserRangefinderResult(34.0010, 108.0010, 110.0, 95.0, "NORMAL")
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(
                firstFix,
                firstFix,
                firstFix,
                firstFix,
                firstFix,
                LaserRangefinderResult(34.00101, 108.00101, 110.0, 58.0, "NORMAL"),
            ),
            approachLegsM = listOf(100.0, 60.0),
            orbitBearingCount = 0,
        )
        val flight = processorFlight(processor)

        val result = processor.run(request)

        assertTrue(result.success)
        assertEquals(2, result.legsCompleted)
        assertEquals(2, flight.flyToCalls.size)
        assertEquals(60.0, horizontalDistanceM(flight.flyToCalls[1].location, firstFix.toLocation()), 1.0)
    }

    @Test
    fun background_hit_rejected_by_displacement_gate() = runTest {
        val request = defaultRequest()
        val backgroundHit = LaserRangefinderResult(
            latitude = request.fireLat + 222.0 / METERS_PER_LAT_DEG,
            longitude = request.fireLng,
            altitude = 300.0,
            distanceM = 58.0,
            state = "NORMAL",
        )
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(backgroundHit),
            approachLegsM = listOf(100.0, 60.0),
            orbitBearingCount = 0,
        )
        val flight = processorFlight(processor)

        val result = processor.run(request)

        assertTrue(result.success)
        assertNull(result.laserFix)
        assertNull(processorApi(processor).lastTaskEventBody?.fireLat)
        assertNull(processorApi(processor).lastTaskEventBody?.fireLng)
        assertEquals(2, flight.flyToCalls.size)
        assertEquals(60.0, horizontalDistanceM(flight.flyToCalls[1].location, request.toLocation()), 1.0)
    }

    @Test
    fun smoke_echo_trimmed_fix_survives() = runTest {
        val request = defaultRequest()
        val smokeEcho = LaserRangefinderResult(
            latitude = request.fireLat + 39.0 / METERS_PER_LAT_DEG,
            longitude = request.fireLng,
            altitude = 120.0,
            distanceM = 58.0,
            state = "NORMAL",
        )
        val realSamples = listOf(
            LaserRangefinderResult(request.fireLat + 1.0 / METERS_PER_LAT_DEG, request.fireLng, 120.0, 58.0, "NORMAL"),
            LaserRangefinderResult(request.fireLat + 2.0 / METERS_PER_LAT_DEG, request.fireLng, 120.0, 58.0, "NORMAL"),
            LaserRangefinderResult(request.fireLat + 3.0 / METERS_PER_LAT_DEG, request.fireLng, 120.0, 58.0, "NORMAL"),
        )
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(smokeEcho, smokeEcho, *realSamples.toTypedArray()),
            orbitBearingCount = 0,
        )

        val result = processor.run(request)

        assertTrue(result.success)
        assertEquals("HIGH", result.laserFix?.confidence)
        assertEquals(3, result.laserFix?.normalSampleCount)
        assertEquals(request.fireLat + 2.0 / METERS_PER_LAT_DEG, result.preciseLat ?: -1.0, 1e-8)
        assertEquals(request.fireLng, result.preciseLng ?: -1.0, 1e-8)
    }

    @Test
    fun legs_keep_original_target_without_fix() = runTest {
        val request = defaultRequest()
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(null, null, null, null, null),
            approachLegsM = listOf(100.0, 60.0),
            orbitBearingCount = 0,
        )
        val flight = processorFlight(processor)

        val result = processor.run(request)

        assertTrue(result.success)
        assertEquals(2, result.legsCompleted)
        assertEquals(2, flight.flyToCalls.size)
        assertEquals(60.0, horizontalDistanceM(flight.flyToCalls[1].location, request.toLocation()), 1.0)
        assertNull(result.laserFix)
    }

    @Test
    fun leg_flyto_timeout_triggers_reset_preserving_report() = runTest {
        val streamProvider = ConfirmationStreamProvider()
        val sessionManager = runningSession(streamProvider)
        val flight = RecordingFlightControl()
        val camera = RecordingCameraControl()
        val missionHold = RecordingMissionHold()
        val api = RecordingDualStreamApi()
        val processor = processor(
            sessionManager = sessionManager,
            flight = flight,
            camera = camera,
            missionHold = missionHold,
            api = api,
            aircraftLocationProvider = { AircraftLocation(34.0, 108.0, 120.0) },
            approachLegsM = listOf(100.0, 60.0),
            orbitBearingCount = 0,
            flyToTimeoutMs = 300,
            pollIntervalMs = 100,
        )

        val result = processor.run(defaultRequest())

        assertFalse(result.success)
        assertEquals(FireConfirmationPhase.FLY_TO, result.phaseReached)
        assertEquals("fly-to-timeout", result.failureReason)
        assertEquals(0, result.legsCompleted)
        assertEquals(0, api.taskEvents.size)
        assertTrue(flight.stopFlyToPointCalled)
        assertTrue(result.resetCompleted)
        assertEquals(listOf("thermal"), camera.streamSources)
        assertEquals(listOf("hold", "resume"), missionHold.calls)
    }

    @Test
    fun orbit_visits_configured_bearings() = runTest {
        val singleFix = LaserRangefinderResult(34.0010, 108.0010, 110.0, 58.0, "NORMAL")
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(singleFix),
            approachLegsM = listOf(100.0, 60.0),
            orbitBearingCount = 3,
            orbitPerPointLaserSamples = 3,
        )
        val flight = processorFlight(processor)

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals(2, result.legsCompleted)
        assertEquals(3, result.orbitPointsVisited)
        assertEquals(5, flight.flyToCalls.size)
        val orbitBearings = flight.flyToCalls.drop(2).map {
            bearingFrom(singleFix.toLocation(), it.location)
        }
        assertEquals(120.0, angleDiffDeg(orbitBearings[0], orbitBearings[1]), 2.0)
        assertEquals(120.0, angleDiffDeg(orbitBearings[1], orbitBearings[2]), 2.0)
    }

    @Test
    fun orbit_fix_median_across_points() = runTest {
        val legFix = LaserRangefinderResult(34.0010, 108.0010, 110.0, 58.0, "NORMAL")
        val orbitSamples = listOf(
            LaserRangefinderResult(34.00091, 108.00091, 110.0, 58.0, "NORMAL"),
            LaserRangefinderResult(34.00093, 108.00093, 110.0, 58.0, "NORMAL"),
            LaserRangefinderResult(34.00092, 108.00092, 110.0, 58.0, "NORMAL"),
            LaserRangefinderResult(34.00097, 108.00097, 110.0, 58.0, "NORMAL"),
            LaserRangefinderResult(34.00095, 108.00095, 110.0, 58.0, "NORMAL"),
            LaserRangefinderResult(34.00096, 108.00096, 110.0, 58.0, "NORMAL"),
            LaserRangefinderResult(34.00094, 108.00094, 110.0, 58.0, "NORMAL"),
            LaserRangefinderResult(34.00098, 108.00098, 110.0, 58.0, "NORMAL"),
            LaserRangefinderResult(34.00099, 108.00099, 110.0, 58.0, "NORMAL"),
        )
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(
                *List(10) { legFix }.plus(orbitSamples).toTypedArray(),
            ),
            approachLegsM = listOf(100.0, 60.0),
            orbitBearingCount = 3,
            orbitPerPointLaserSamples = 3,
        )

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals(34.00095, result.orbitFix?.latitude ?: -1.0, 1e-6)
        assertEquals(108.00095, result.orbitFix?.longitude ?: -1.0, 1e-6)
        assertEquals(34.00095, result.preciseLat ?: -1.0, 1e-6)
        assertEquals(108.00095, result.preciseLng ?: -1.0, 1e-6)
        assertEquals(9, result.orbitFix?.normalSampleCount)
    }

    @Test
    fun orbit_point_timeout_skipped_not_fatal() = runTest {
        val streamProvider = ConfirmationStreamProvider()
        val sessionManager = runningSession(streamProvider)
        val flight = RecordingFlightControl()
        val legFix = LaserRangefinderResult(34.0010, 108.0010, 110.0, 58.0, "NORMAL")
        val laser = RecordingLaserRangefinder(legFix)
        var aircraftLocation = AircraftLocation(34.0, 108.0, 120.0)
        val processor = processor(
            sessionManager = sessionManager,
            flight = flight,
            laserRangefinder = laser,
            aircraftLocationProvider = { aircraftLocation },
            approachLegsM = listOf(100.0, 60.0),
            orbitBearingCount = 3,
            orbitPerPointLaserSamples = 3,
            flyToTimeoutMs = 300,
            pollIntervalMs = 100,
        )
        flight.onFlyToPoint = { lat, lng, height ->
            if (flight.flyToCalls.size != 4) {
                aircraftLocation = AircraftLocation(lat, lng, height)
            }
        }

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals(2, result.orbitPointsVisited)
        assertEquals(FireConfirmationPhase.VISIBLE_CONFIRM, result.phaseReached)
    }

    @Test
    fun orbit_point_overlapping_current_position_skips_flight() = runTest {
        val request = defaultRequest()
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(null),
            approachLegsM = listOf(60.0),
            orbitBearingCount = 3,
            orbitPerPointLaserSamples = 0,
            windProvider = StaticWindProvider(WindReading(speedMps = 3.0, sourceBearingDeg = 0.0)),
            visibleConfirmAtUpwind = false,
        )
        val flight = processorFlight(processor)

        val result = processor.run(request)

        assertTrue(result.success)
        assertEquals(3, result.orbitPointsVisited)
        assertEquals(3, flight.flyToCalls.size)
        assertEquals(0.0, bearingFrom(request.toLocation(), flight.flyToCalls[0].location), 2.0)
        assertEquals(120.0, bearingFrom(request.toLocation(), flight.flyToCalls[1].location), 2.0)
        assertEquals(240.0, bearingFrom(request.toLocation(), flight.flyToCalls[2].location), 2.0)
    }

    @Test
    fun orbit_disabled_by_zero_count() = runTest {
        val singleFix = LaserRangefinderResult(34.0010, 108.0010, 110.0, 58.0, "NORMAL")
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(singleFix),
            approachLegsM = listOf(100.0, 60.0),
            orbitBearingCount = 0,
        )
        val flight = processorFlight(processor)

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals(2, flight.flyToCalls.size)
        assertNull(result.orbitFix)
        assertEquals(0, result.orbitPointsVisited)
    }

    @Test
    fun upwind_bias_selects_wind_source_bearing() = runTest {
        val request = defaultRequest()
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(null),
            approachLegsM = listOf(60.0),
            orbitBearingCount = 0,
            windProvider = StaticWindProvider(WindReading(speedMps = 3.0, sourceBearingDeg = 90.0)),
        )
        val flight = processorFlight(processor)

        processor.run(request)

        assertEquals(1, flight.flyToCalls.size)
        assertEquals(60.0, horizontalDistanceM(flight.flyToCalls[0].location, request.toLocation()), 1.0)
        assertEquals(90.0, bearingFrom(request.toLocation(), flight.flyToCalls[0].location), 2.0)
    }

    @Test
    fun no_wind_falls_back_to_aircraft_bearing() = runTest {
        val request = defaultRequest()
        val current = AircraftLocation(34.0, 108.0, 120.0)
        val expectedFallbackBearing = bearingFrom(request.toLocation(), current)
        val noWindProcessor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(null),
            approachLegsM = listOf(60.0),
            orbitBearingCount = 0,
            windProvider = StaticWindProvider(null),
            initialAircraftLocation = current,
        )
        val lowWindProcessor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(null),
            approachLegsM = listOf(60.0),
            orbitBearingCount = 0,
            windProvider = StaticWindProvider(WindReading(speedMps = 1.0, sourceBearingDeg = 90.0)),
            initialAircraftLocation = current,
        )

        noWindProcessor.run(request)
        lowWindProcessor.run(request)

        assertEquals(expectedFallbackBearing, bearingFrom(request.toLocation(), processorFlight(noWindProcessor).flyToCalls[0].location), 2.0)
        assertEquals(expectedFallbackBearing, bearingFrom(request.toLocation(), processorFlight(lowWindProcessor).flyToCalls[0].location), 2.0)
    }

    @Test
    fun report_happens_once_after_orbit() = runTest {
        val legFix = LaserRangefinderResult(34.0010, 108.0010, 110.0, 58.0, "NORMAL")
        val orbitFix = LaserRangefinderResult(34.00095, 108.00095, 110.0, 58.0, "NORMAL")
        val laser = RecordingLaserRangefinder(
            *List(10) { legFix }.plus(List(9) { orbitFix }).toTypedArray(),
        )
        val api = RecordingDualStreamApi(onRecord = { laser.calls })
        val processor = processorWithArrivingAircraft(
            laserRangefinder = laser,
            api = api,
            approachLegsM = listOf(100.0, 60.0),
            orbitBearingCount = 3,
            orbitPerPointLaserSamples = 3,
        )

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals(1, api.taskEvents.size)
        assertEquals(listOf(19), api.recordMarkers)
        assertEquals(34.00095, api.lastTaskEventBody?.fireLat ?: -1.0, 1e-6)
        assertEquals(108.00095, api.lastTaskEventBody?.fireLng ?: -1.0, 1e-6)
        assertEquals(34.00095, result.preciseLat ?: -1.0, 1e-6)
        assertEquals(108.00095, result.preciseLng ?: -1.0, 1e-6)
    }

    @Test
    fun measure_failure_triggers_reset() = runTest {
        val streamProvider = ConfirmationStreamProvider(measureStatus = "failed")
        val sessionManager = runningSession(streamProvider)
        val flight = RecordingFlightControl()
        val camera = RecordingCameraControl()
        var aircraftLocation = AircraftLocation(34.0, 108.0, 120.0)
        val processor = processor(
            sessionManager = sessionManager,
            flight = flight,
            camera = camera,
            aircraftLocationProvider = { aircraftLocation },
        )
        flight.onFlyToPoint = { lat, lng, height ->
            aircraftLocation = AircraftLocation(lat, lng, height)
        }

        val result = processor.run(defaultRequest())

        assertFalse(result.success)
        assertEquals(FireConfirmationPhase.MEASURE_CLOSE, result.phaseReached)
        assertEquals("thermal-close-measure-failed", result.failureReason)
        assertTrue(result.resetCompleted)
        assertEquals(0, processorApi(processor).taskEvents.size)
        assertEquals(listOf("thermal"), camera.streamSources)
    }

    @Test
    fun visible_confirm_failure_still_resets_and_reports_partial_success() = runTest {
        val streamProvider = ConfirmationStreamProvider(visibleSnapshotPath = "/tmp/visible.jpg")
        val sessionManager = runningSession(streamProvider)
        val visibleConfirmer = RecordingVisibleSnapshotConfirmer(
            url = null,
            throwOnConfirm = true,
        )
        val flight = RecordingFlightControl()
        val camera = RecordingCameraControl()
        var aircraftLocation = AircraftLocation(34.0, 108.0, 120.0)
        val api = RecordingDualStreamApi()
        val processor = processor(
            sessionManager = sessionManager,
            flight = flight,
            camera = camera,
            api = api,
            visibleSnapshotConfirmer = visibleConfirmer,
            aircraftLocationProvider = { aircraftLocation },
        )
        flight.onFlyToPoint = { lat, lng, height ->
            aircraftLocation = AircraftLocation(lat, lng, height)
        }

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals(FireConfirmationPhase.VISIBLE_CONFIRM, result.phaseReached)
        assertEquals("visible-confirm-failed", result.failureReason)
        assertTrue(result.resetCompleted)
        assertEquals(1, api.taskEvents.size)
        assertEquals(1, visibleConfirmer.confirmCalls)
    }

    @Test
    fun final_leg_failure_reports_first_leg_result() = runTest {
        val request = defaultRequest()
        val streamProvider = ConfirmationStreamProvider(
            thermalHotspotResults = listOf(
                appliedHotspot(temperatureC = 91.5, snapshotPath = "/tmp/first-leg-thermal.jpg"),
                appliedHotspot(temperatureC = null, region = null, snapshotPath = null),
            ),
        )
        val firstFix = LaserRangefinderResult(request.fireLat, request.fireLng, 120.0, 95.0, "NORMAL")
        val processor = processorWithArrivingAircraft(
            streamProvider = streamProvider,
            laserRangefinder = RecordingLaserRangefinder(firstFix),
            approachLegsM = listOf(100.0, 60.0),
            orbitBearingCount = 0,
        )

        val result = processor.run(request)

        assertTrue(result.success)
        assertEquals("final-leg-measure-degraded", result.failureReason)
        assertEquals(1, processorApi(processor).taskEvents.size)
        assertEquals(91.5, processorApi(processor).lastTaskEventBody?.thermalTemperature ?: -1.0, 1e-6)
        assertEquals(request.fireLat, processorApi(processor).lastTaskEventBody?.fireLat ?: -1.0, 1e-6)
        assertEquals(request.fireLng, processorApi(processor).lastTaskEventBody?.fireLng ?: -1.0, 1e-6)
        assertEquals(2, result.legsCompleted)
    }

    @Test
    fun visible_confirm_returns_to_upwind_point() = runTest {
        val request = defaultRequest()
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(null),
            approachLegsM = listOf(60.0),
            orbitBearingCount = 3,
            orbitPerPointLaserSamples = 0,
            windProvider = StaticWindProvider(WindReading(speedMps = 3.0, sourceBearingDeg = 0.0)),
        )
        val flight = processorFlight(processor)

        val result = processor.run(request)

        assertTrue(result.success)
        assertEquals(3, result.orbitPointsVisited)
        assertEquals(0.0, bearingFrom(request.toLocation(), flight.flyToCalls.last().location), 2.0)
    }

    @Test
    fun run_rejected_below_battery_threshold() = runTest {
        val lowBatteryFlight = RecordingFlightControl()
        val lowBatteryMissionHold = RecordingMissionHold()
        val lowBatteryProcessor = processor(
            sessionManager = runningSession(ConfirmationStreamProvider()),
            flight = lowBatteryFlight,
            missionHold = lowBatteryMissionHold,
            batteryProvider = StaticBatteryProvider(29),
            minBatteryPercentToStart = 30,
        )

        val lowBatteryResult = lowBatteryProcessor.run(defaultRequest())

        assertFalse(lowBatteryResult.success)
        assertEquals(FireConfirmationPhase.IDLE, lowBatteryResult.phaseReached)
        assertEquals("battery-below-threshold", lowBatteryResult.failureReason)
        assertTrue(lowBatteryResult.resetCompleted)
        assertEquals(emptyList<FlyToCall>(), lowBatteryFlight.flyToCalls)
        assertEquals(listOf("hold", "resume"), lowBatteryMissionHold.calls)

        val nullBatteryProcessor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(null),
            orbitBearingCount = 0,
            batteryProvider = StaticBatteryProvider(null),
        )

        val nullBatteryResult = nullBatteryProcessor.run(defaultRequest())

        assertTrue(nullBatteryResult.success)
        assertEquals(1, processorFlight(nullBatteryProcessor).flyToCalls.size)
    }

    @Test
    fun mission_duration_guard_aborts_to_reset() = runTest {
        var now = 0L
        var aircraftLocation = AircraftLocation(34.0, 108.0, 120.0)
        val flight = RecordingFlightControl()
        val processor = processor(
            sessionManager = runningSession(ConfirmationStreamProvider()),
            flight = flight,
            laserRangefinder = RecordingLaserRangefinder(null),
            aircraftLocationProvider = { aircraftLocation },
            approachLegsM = listOf(100.0, 60.0),
            orbitBearingCount = 0,
            maxMissionDurationMs = 1_000L,
            clockMs = { now },
        )
        flight.onFlyToPoint = { lat, lng, height ->
            aircraftLocation = AircraftLocation(lat, lng, height)
            now = 2_000L
        }

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals("mission-duration-exceeded", result.failureReason)
        assertEquals(1, result.legsCompleted)
        assertEquals(1, processorApi(processor).taskEvents.size)
        assertTrue(result.resetCompleted)
    }

    @Test
    fun waituntil_diverging_position_aborts() = runTest {
        val target = defaultRequest().toLocation()
        val locations = ArrayDeque(
            listOf(
                AircraftLocation(34.0, 108.0, 120.0),
                AircraftLocation(target.latitude + 60.0 / METERS_PER_LAT_DEG, target.longitude, 120.0),
                AircraftLocation(target.latitude + 50.0 / METERS_PER_LAT_DEG, target.longitude, 120.0),
                AircraftLocation(target.latitude + 95.0 / METERS_PER_LAT_DEG, target.longitude, 120.0),
            ),
        )
        val flight = RecordingFlightControl()
        val processor = processor(
            sessionManager = runningSession(ConfirmationStreamProvider()),
            flight = flight,
            aircraftLocationProvider = { locations.removeFirstOrNull() ?: locations.lastOrNull() ?: target },
            approachLegsM = listOf(0.1),
            flyToTimeoutMs = 5_000,
            pollIntervalMs = 100,
        )

        val result = processor.run(defaultRequest())

        assertFalse(result.success)
        assertEquals(FireConfirmationPhase.FLY_TO, result.phaseReached)
        assertEquals("fly-to-timeout", result.failureReason)
        assertTrue(flight.stopFlyToPointCalled)
        assertEquals(0, processorApi(processor).taskEvents.size)
    }

    @Test
    fun waituntil_altitude_runaway_aborts() = runTest {
        val locations = ArrayDeque(
            listOf(
                AircraftLocation(34.0, 108.0, 120.0),
                AircraftLocation(34.0, 108.0, 120.0),
                AircraftLocation(34.0, 108.0, 200.0),
            ),
        )
        val flight = RecordingFlightControl()
        val processor = processor(
            sessionManager = runningSession(ConfirmationStreamProvider()),
            flight = flight,
            aircraftLocationProvider = { locations.removeFirstOrNull() ?: AircraftLocation(34.0, 108.0, 200.0) },
            approachLegsM = listOf(0.1),
            flyToTimeoutMs = 5_000,
            pollIntervalMs = 100,
        )

        val result = processor.run(defaultRequest())

        assertFalse(result.success)
        assertEquals(FireConfirmationPhase.FLY_TO, result.phaseReached)
        assertEquals("fly-to-timeout", result.failureReason)
        assertTrue(flight.stopFlyToPointCalled)
        assertEquals(0, processorApi(processor).taskEvents.size)
    }

    @Test
    fun report_uploads_thermal_snapshot_and_carries_url() = runTest {
        val uploader = RecordingThermalSnapshotUploader("http://ai/thermal.jpg")
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(null),
            orbitBearingCount = 0,
            snapshotUploader = uploader,
        )

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals(listOf("task-fire-001-1780059017562" to "/tmp/thermal.jpg"), uploader.uploads)
        assertEquals("http://ai/thermal.jpg", processorApi(processor).lastTaskEventBody?.thermalImageUrl)
    }

    @Test
    fun report_logs_when_snapshot_upload_fails() = runTest {
        val uploader = RecordingThermalSnapshotUploader(null)
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(null),
            orbitBearingCount = 0,
            snapshotUploader = uploader,
        )

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals(2, uploader.uploads.size)
        assertNull(processorApi(processor).lastTaskEventBody?.thermalImageUrl)
    }

    @Test
    fun flight_altitude_keeps_current_never_laser_altitude() = runTest {
        val highAltitudeFix = LaserRangefinderResult(34.00091, 108.00091, 386.0, 58.0, "NORMAL")
        val processor = processorWithArrivingAircraft(
            laserRangefinder = RecordingLaserRangefinder(highAltitudeFix),
            initialAircraftLocation = AircraftLocation(34.0, 108.0, 120.0),
            approachLegsM = listOf(100.0, 60.0),
            orbitBearingCount = 0,
        )
        val flight = processorFlight(processor)

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals(2, flight.flyToCalls.size)
        assertEquals(120.0, flight.flyToCalls[0].location.altitudeM, 1e-6)
        assertEquals(120.0, flight.flyToCalls[1].location.altitudeM, 1e-6)
    }

    @Test
    fun reset_step_failure_retries_then_continues() = runTest {
        val streamProvider = ConfirmationStreamProvider()
        val sessionManager = runningSession(streamProvider)
        val flight = RecordingFlightControl()
        val gimbal = RecordingGimbalControl()
        val camera = RecordingCameraControl(failThermalStreamAttempts = 3)
        val missionHold = RecordingMissionHold()
        var aircraftLocation = AircraftLocation(34.0, 108.0, 120.0)
        val processor = processor(
            sessionManager = sessionManager,
            flight = flight,
            gimbal = gimbal,
            camera = camera,
            missionHold = missionHold,
            aircraftLocationProvider = { aircraftLocation },
        )
        flight.onFlyToPoint = { lat, lng, height ->
            aircraftLocation = AircraftLocation(lat, lng, height)
        }

        val result = processor.run(defaultRequest())

        assertFalse(result.resetCompleted)
        assertEquals(3, camera.setStreamSourceAttempts)
        assertEquals(listOf(-45.0, 0.0), gimbal.pitchOrResetCalls)
        assertEquals(listOf(5.0, 1.0), camera.zoomRatios)
        assertEquals(listOf("hold", "resume"), missionHold.calls)
        assertTrue(result.success)
    }

    @Test
    fun run_rejected_when_already_running() = runTest {
        val streamProvider = ConfirmationStreamProvider()
        val sessionManager = runningSession(streamProvider)
        val flight = RecordingFlightControl()
        val flightStarted = CompletableDeferred<Unit>()
        val releaseFlight = CompletableDeferred<Unit>()
        flight.onFlyToPointSuspend = { lat, lng, height ->
            flightStarted.complete(Unit)
            releaseFlight.await()
        }
        val processor = processor(
            sessionManager = sessionManager,
            flight = flight,
            aircraftLocationProvider = { AircraftLocation(34.0, 108.0, 120.0) },
        )

        val first = async { processor.run(defaultRequest()) }
        flightStarted.await()
        val second = processor.run(defaultRequest(taskId = "task-fire-002"))
        releaseFlight.complete(Unit)
        first.await()

        assertFalse(second.success)
        assertEquals(FireConfirmationPhase.IDLE, second.phaseReached)
        assertEquals("busy", second.failureReason)
        assertFalse(second.resetCompleted)
    }

    @Test
    fun disabled_processor_never_autotriggers() = runTest {
        val processor = processor(
            sessionManager = runningSession(ConfirmationStreamProvider()),
            enabled = false,
            aircraftLocationProvider = { AircraftLocation(34.0, 108.0, 120.0) },
        )
        val autoTrigger = FireConfirmationAutoTrigger(
            processor = processor,
            scope = this,
            fireLocationProvider = { AircraftLocation(34.0, 108.0, 120.0) },
        )

        autoTrigger.onConfirmedReport(taskId = "task-fire-001", droneSn = "DRONE-001")
        runCurrent()
        advanceUntilIdle()

        assertEquals(0, autoTrigger.startedCount)
        assertEquals(0, processorApi(processor).taskEvents.size)
    }

    @Test
    fun missing_aircraft_location_fails_to_reset() = runTest {
        val streamProvider = ConfirmationStreamProvider()
        val sessionManager = runningSession(streamProvider)
        sessionManager.thermalMonitoringEnabled = true
        val camera = RecordingCameraControl()
        val missionHold = RecordingMissionHold()
        val processor = processor(
            sessionManager = sessionManager,
            camera = camera,
            missionHold = missionHold,
            aircraftLocationProvider = { null },
        )

        val result = processor.run(defaultRequest())

        assertFalse(result.success)
        assertEquals(FireConfirmationPhase.FLY_TO, result.phaseReached)
        assertEquals("aircraft-location-unavailable", result.failureReason)
        assertTrue(result.resetCompleted)
        assertTrue(sessionManager.thermalMonitoringEnabled)
        assertEquals(listOf("thermal"), camera.streamSources)
        assertEquals(listOf("hold", "resume"), missionHold.calls)
    }

    @Test
    fun manual_command_dispatches_fire_confirmation_params_to_runner() = runTest {
        var receivedRequest: FireConfirmationRequest? = null
        val sessionManager = DualStreamSessionManager(
            ConfirmationStreamProvider(),
            fireConfirmationRunner = { request ->
                receivedRequest = request
                FireConfirmationResult(
                    phaseReached = FireConfirmationPhase.VISIBLE_CONFIRM,
                    success = true,
                    failureReason = null,
                    closeMeasureTemperatureC = 88.0,
                    preciseLat = request.fireLat,
                    preciseLng = request.fireLng,
                    resetCompleted = true,
                )
            },
        )

        val result = sessionManager.executeCommand(
            droneSn = "DRONE-001",
            action = "fire-confirmation-mission",
            params = mapOf(
                "taskId" to "task-manual-001",
                "lat" to 34.12,
                "lng" to 108.23,
                "alt" to 121.5,
            ),
        )

        assertEquals("applied", result.status)
        assertEquals(88.0, result.thermalCenterTemperatureC ?: -1.0, 1e-6)
        assertEquals("task-manual-001", receivedRequest?.taskId)
        assertEquals("DRONE-001", receivedRequest?.droneSn)
        assertEquals(34.12, receivedRequest?.fireLat ?: -1.0, 1e-6)
        assertEquals(108.23, receivedRequest?.fireLng ?: -1.0, 1e-6)
        assertEquals(121.5, receivedRequest?.fireAlt ?: -1.0, 1e-6)
    }

    @Test
    fun aim_converges_within_iterations() = runTest {
        val streamProvider = ConfirmationStreamProvider(
            thermalMeasureRegions = listOf(
                ThermalMeasureRegion(x = 0.56, y = 0.56, width = 0.12, height = 0.12),
                ThermalMeasureRegion(x = 0.50, y = 0.38, width = 0.12, height = 0.12),
                ThermalMeasureRegion(x = 0.44, y = 0.44, width = 0.12, height = 0.12),
            ),
        )
        val sessionManager = runningSession(streamProvider)
        val flight = RecordingFlightControl()
        val gimbal = RecordingGimbalControl()
        val laser = RecordingLaserRangefinder(
            LaserRangefinderResult(
                latitude = 34.0004,
                longitude = 108.0005,
                distanceM = 58.0,
                state = "NORMAL",
            ),
        )
        var aircraftLocation = AircraftLocation(34.0, 108.0, 120.0)
        val processor = processor(
            sessionManager = sessionManager,
            flight = flight,
            gimbal = gimbal,
            laserRangefinder = laser,
            aircraftLocationProvider = { aircraftLocation },
        )
        flight.onFlyToPoint = { lat, lng, height ->
            aircraftLocation = AircraftLocation(lat, lng, height)
        }

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals(true, result.laserFix?.centered)
        assertEquals(3, streamProvider.measureHotspotCalls)
        assertEquals(0, gimbal.rotationCalls.size)
        assertEquals(2, gimbal.relativeRotationCalls.size)
        assertEquals(-4.44, gimbal.relativeRotationCalls[0].pitch, 1e-6)
        assertEquals(5.4, gimbal.relativeRotationCalls[0].yaw, 1e-6)
        assertEquals(2.22, gimbal.relativeRotationCalls[1].pitch, 1e-6)
        assertEquals(2.7, gimbal.relativeRotationCalls[1].yaw, 1e-6)
    }

    @Test
    fun aim_failure_does_not_block_laser() = runTest {
        val streamProvider = ConfirmationStreamProvider(
            thermalMeasureRegions = List(4) {
                ThermalMeasureRegion(x = 0.70, y = 0.70, width = 0.10, height = 0.10)
            },
        )
        val laser = RecordingLaserRangefinder(
            LaserRangefinderResult(
                latitude = 34.0004,
                longitude = 108.0005,
                distanceM = 58.0,
                state = "NORMAL",
            ),
        )
        val sessionManager = runningSession(streamProvider)
        val flight = RecordingFlightControl()
        var aircraftLocation = AircraftLocation(34.0, 108.0, 120.0)
        val processor = processor(
            sessionManager = sessionManager,
            flight = flight,
            laserRangefinder = laser,
            aircraftLocationProvider = { aircraftLocation },
        )
        flight.onFlyToPoint = { lat, lng, height ->
            aircraftLocation = AircraftLocation(lat, lng, height)
        }

        val result = processor.run(defaultRequest())

        assertTrue(result.success)
        assertEquals("laser-rangefinder", result.geoMethod)
        assertEquals(false, result.laserFix?.centered)
        assertEquals(5, laser.calls)
    }

    @Test
    fun laser_median_of_normal_samples() = runTest {
        val laser = RecordingLaserRangefinder(
            LaserRangefinderResult(latitude = null, longitude = null, distanceM = null, state = "NO_SIGNAL"),
            LaserRangefinderResult(latitude = 34.00091, longitude = 108.00091, distanceM = 70.0, state = "NORMAL"),
            LaserRangefinderResult(latitude = 34.00092, longitude = 108.00092, distanceM = 72.0, state = "NORMAL"),
            LaserRangefinderResult(latitude = 34.0009, longitude = 108.0009, distanceM = 500.0, state = "NORMAL"),
            LaserRangefinderResult(latitude = 34.00093, longitude = 108.00093, distanceM = 74.0, state = "NORMAL"),
        )
        val processor = processorWithArrivingAircraft(laserRangefinder = laser)

        val result = processor.run(defaultRequest())

        assertEquals("laser-rangefinder", result.geoMethod)
        assertEquals(34.00092, result.preciseLat ?: -1.0, 1e-6)
        assertEquals(108.00092, result.preciseLng ?: -1.0, 1e-6)
        assertEquals("HIGH", result.laserFix?.confidence)
        assertEquals(3, result.laserFix?.normalSampleCount)
    }

    @Test
    fun laser_insufficient_normal_falls_back() = runTest {
        val laser = RecordingLaserRangefinder(
            LaserRangefinderResult(latitude = 34.0001, longitude = 108.0001, distanceM = 70.0, state = "NORMAL"),
            LaserRangefinderResult(latitude = null, longitude = null, distanceM = null, state = "NO_SIGNAL"),
            LaserRangefinderResult(latitude = 34.0002, longitude = 108.0002, distanceM = 72.0, state = "NORMAL"),
            null,
            null,
        )
        val processor = processorWithArrivingAircraft(laserRangefinder = laser)

        val result = processor.run(defaultRequest())

        assertNull(result.laserFix)
        assertEquals("standoff-hover-point-fallback", result.geoMethod)
    }

    @Test
    fun laser_scatter_rejected() = runTest {
        val laser = RecordingLaserRangefinder(
            LaserRangefinderResult(latitude = 34.0000, longitude = 108.0000, distanceM = 70.0, state = "NORMAL"),
            LaserRangefinderResult(latitude = 34.0010, longitude = 108.0000, distanceM = 71.0, state = "NORMAL"),
            LaserRangefinderResult(latitude = 34.0000, longitude = 108.0010, distanceM = 72.0, state = "NORMAL"),
            null,
            null,
        )
        val processor = processorWithArrivingAircraft(laserRangefinder = laser)

        val result = processor.run(defaultRequest())

        assertNull(result.laserFix)
        assertEquals("standoff-hover-point-fallback", result.geoMethod)
    }

    @Test
    fun laser_plausibility_gate_rejects_near_echo() = runTest {
        val laser = RecordingLaserRangefinder(
            LaserRangefinderResult(latitude = 34.0001, longitude = 108.0001, distanceM = 2.0, state = "NORMAL"),
            LaserRangefinderResult(latitude = 34.0002, longitude = 108.0002, distanceM = 70.0, state = "NORMAL"),
            LaserRangefinderResult(latitude = 34.0003, longitude = 108.0003, distanceM = 72.0, state = "NORMAL"),
            null,
            null,
        )
        val processor = processorWithArrivingAircraft(laserRangefinder = laser)

        val result = processor.run(defaultRequest())

        assertNull(result.laserFix)
        assertEquals("standoff-hover-point-fallback", result.geoMethod)
    }

    private suspend fun runningSession(streamProvider: ConfirmationStreamProvider): DualStreamSessionManager {
        val sessionManager = DualStreamSessionManager(streamProvider)
        sessionManager.start("DRONE-001")
        return sessionManager
    }

    private fun processor(
        sessionManager: DualStreamSessionManager,
        flight: RecordingFlightControl = RecordingFlightControl(),
        gimbal: RecordingGimbalControl = RecordingGimbalControl(),
        camera: RecordingCameraControl = RecordingCameraControl(),
        missionHold: RecordingMissionHold = RecordingMissionHold(),
        api: RecordingDualStreamApi = RecordingDualStreamApi(),
        visibleSnapshotConfirmer: VisibleSnapshotConfirmer = RecordingVisibleSnapshotConfirmer("http://ai/visible.jpg"),
        laserRangefinder: LaserRangefinderClient = NoopLaserRangefinderClient,
        snapshotUploader: ThermalSnapshotUploader = RecordingThermalSnapshotUploader(null),
        batteryProvider: BatteryProvider = StaticBatteryProvider(null),
        enabled: Boolean = true,
        aircraftLocationProvider: () -> AircraftLocation? = { AircraftLocation(34.0, 108.0, 120.0) },
        approachLegsM: List<Double> = listOf(60.0),
        orbitBearingCount: Int = 0,
        orbitPerPointLaserSamples: Int = 3,
        windProvider: WindProvider = StaticWindProvider(null),
        flyToTimeoutMs: Long = 5_000,
        pollIntervalMs: Long = 100,
        minBatteryPercentToStart: Int = 30,
        maxMissionDurationMs: Long = 360_000L,
        visibleConfirmAtUpwind: Boolean = true,
        clockMs: () -> Long = { 1780059017562L },
    ): FireConfirmationProcessor {
        val processor = FireConfirmationProcessor(
            sessionManager = sessionManager,
            flightControl = flight,
            gimbalControl = gimbal,
            cameraControl = camera,
            missionHold = missionHold,
            client = AgentBackendClient(api),
            visibleSnapshotConfirmer = visibleSnapshotConfirmer,
            laserRangefinder = laserRangefinder,
            snapshotUploader = snapshotUploader,
            batteryProvider = batteryProvider,
            windProvider = windProvider,
            aircraftLocationProvider = aircraftLocationProvider,
            enabled = enabled,
            approachLegsM = approachLegsM,
            orbitBearingCount = orbitBearingCount,
            orbitPerPointLaserSamples = orbitPerPointLaserSamples,
            flyToTimeoutMs = flyToTimeoutMs,
            pollIntervalMs = pollIntervalMs,
            minBatteryPercentToStart = minBatteryPercentToStart,
            maxMissionDurationMs = maxMissionDurationMs,
            visibleConfirmAtUpwind = visibleConfirmAtUpwind,
            clockMs = clockMs,
        )
        processorApiByInstance[processor] = api
        processorFlightByInstance[processor] = flight
        return processor
    }

    private suspend fun processorWithArrivingAircraft(
        laserRangefinder: LaserRangefinderClient,
        streamProvider: ConfirmationStreamProvider = ConfirmationStreamProvider(),
        api: RecordingDualStreamApi = RecordingDualStreamApi(),
        approachLegsM: List<Double> = listOf(60.0),
        orbitBearingCount: Int = 0,
        orbitPerPointLaserSamples: Int = 3,
        windProvider: WindProvider = StaticWindProvider(null),
        initialAircraftLocation: AircraftLocation = AircraftLocation(34.0, 108.0, 120.0),
        snapshotUploader: ThermalSnapshotUploader = RecordingThermalSnapshotUploader(null),
        batteryProvider: BatteryProvider = StaticBatteryProvider(null),
        visibleConfirmAtUpwind: Boolean = true,
    ): FireConfirmationProcessor {
        val sessionManager = runningSession(streamProvider)
        val flight = RecordingFlightControl()
        var aircraftLocation = initialAircraftLocation
        val processor = processor(
            sessionManager = sessionManager,
            flight = flight,
            api = api,
            laserRangefinder = laserRangefinder,
            snapshotUploader = snapshotUploader,
            batteryProvider = batteryProvider,
            visibleConfirmAtUpwind = visibleConfirmAtUpwind,
            aircraftLocationProvider = { aircraftLocation },
            approachLegsM = approachLegsM,
            orbitBearingCount = orbitBearingCount,
            orbitPerPointLaserSamples = orbitPerPointLaserSamples,
            windProvider = windProvider,
        )
        flight.onFlyToPoint = { lat, lng, height ->
            aircraftLocation = AircraftLocation(lat, lng, height)
        }
        return processor
    }

    private fun processorApi(processor: FireConfirmationProcessor): RecordingDualStreamApi =
        requireNotNull(processorApiByInstance[processor])

    private fun processorFlight(processor: FireConfirmationProcessor): RecordingFlightControl =
        requireNotNull(processorFlightByInstance[processor])

    private fun defaultRequest(taskId: String = "task-fire-001") = FireConfirmationRequest(
        droneSn = "DRONE-001",
        taskId = taskId,
        fireLat = 34.0009,
        fireLng = 108.0009,
        fireAlt = 110.0,
    )

    private fun FireConfirmationRequest.toLocation(): AircraftLocation =
        AircraftLocation(fireLat, fireLng, fireAlt ?: 0.0)

    private fun LaserRangefinderResult.toLocation(): AircraftLocation =
        AircraftLocation(requireNotNull(latitude), requireNotNull(longitude), altitude ?: 0.0)

    private class ConfirmationStreamProvider(
        private val measureStatus: String = "applied",
        private val visibleSnapshotPath: String? = "/tmp/visible.jpg",
        private val thermalMeasureRegions: List<ThermalMeasureRegion> = listOf(
            ThermalMeasureRegion(x = 0.40, y = 0.44, width = 0.12, height = 0.12),
        ),
        private val thermalHotspotResults: List<StreamStartResult>? = null,
    ) : StreamProvider {
        var focusThermalCalls = 0
        var focusVisibleCalls = 0
        var measureHotspotCalls = 0
        var captureVisibleSnapshotCalls = 0

        override suspend fun start(droneSn: String): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.BOUND,
            playbackStatus = "shared-side-by-side-preview",
        )

        override suspend fun focusVisible(droneSn: String): StreamStartResult {
            focusVisibleCalls += 1
            return start(droneSn)
        }

        override suspend fun focusThermal(droneSn: String): StreamStartResult {
            focusThermalCalls += 1
            return start(droneSn)
        }

        override suspend fun measureThermalHotspot(
            droneSn: String,
            seedRegion: ThermalMeasureRegion?,
        ): StreamStartResult {
            measureHotspotCalls += 1
            thermalHotspotResults?.let {
                return it[(measureHotspotCalls - 1).coerceAtMost(it.lastIndex)]
            }
            if (measureStatus != "applied") {
                throw IllegalStateException("thermal-close-measure-failed")
            }
            return StreamStartResult(
                visibleState = BoundStreamState.BOUND,
                thermalState = BoundStreamState.BOUND,
                playbackStatus = "shared-side-by-side-preview",
                thermalCenterTemperatureC = 91.5,
                thermalMeasureRegion = thermalMeasureRegions[
                    (measureHotspotCalls - 1).coerceAtMost(thermalMeasureRegions.lastIndex)
                ],
                thermalSnapshotPath = "/tmp/thermal.jpg",
            )
        }

        override suspend fun captureVisibleSnapshot(droneSn: String): StreamStartResult {
            captureVisibleSnapshotCalls += 1
            return StreamStartResult(
                visibleState = BoundStreamState.BOUND,
                thermalState = BoundStreamState.IDLE,
                playbackStatus = "visible-live-ready",
                visibleSnapshotPath = visibleSnapshotPath,
            )
        }

        override suspend fun stop() = Unit
    }

    private class RecordingFlightControl : FlightControlActionClient {
        var stopFlyToPointCalled = false
        val flyToCalls = mutableListOf<FlyToCall>()
        var onFlyToPoint: ((Double, Double, Double) -> Unit)? = null
        var onFlyToPointSuspend: (suspend (Double, Double, Double) -> Unit)? = null

        override suspend fun startTakeoff() = Unit
        override suspend fun startGoHome() = Unit
        override suspend fun stopGoHome() = Unit
        override suspend fun startAutoLanding() = Unit
        override suspend fun stopAutoLanding() = Unit
        override suspend fun emergencyStop() = Unit
        override suspend fun hover() = Unit
        override suspend fun stopFlyToPoint() {
            stopFlyToPointCalled = true
        }

        override suspend fun sendVirtualStick(key: String, durationMs: Long) = Unit

        override suspend fun flyToPoint(latitude: Double, longitude: Double, height: Double, speed: Double) {
            flyToCalls += FlyToCall(AircraftLocation(latitude, longitude, height), speed)
            onFlyToPointSuspend?.invoke(latitude, longitude, height)
            onFlyToPoint?.invoke(latitude, longitude, height)
        }

        override suspend fun setNavigationLight(enabled: Boolean) = Unit
    }

    private data class FlyToCall(
        val location: AircraftLocation,
        val speed: Double,
    )

    private class RecordingGimbalControl : GimbalActionClient {
        val pitchOrResetCalls = mutableListOf<Double>()
        val rotationCalls = mutableListOf<GimbalRotationCall>()
        val relativeRotationCalls = mutableListOf<GimbalRelativeRotationCall>()

        override suspend fun resetGimbal() {
            pitchOrResetCalls += 0.0
        }

        override suspend fun rotateGimbal(pitch: Double, yaw: Double, roll: Double) {
            rotationCalls += GimbalRotationCall(pitch, yaw, roll)
        }

        override suspend fun rotateGimbalBy(pitchDelta: Double, yawDelta: Double) {
            relativeRotationCalls += GimbalRelativeRotationCall(pitchDelta, yawDelta)
        }

        override suspend fun rotateGimbalToPitch(pitch: Double) {
            pitchOrResetCalls += pitch
        }
    }

    private data class GimbalRotationCall(
        val pitch: Double,
        val yaw: Double,
        val roll: Double,
    )

    private data class GimbalRelativeRotationCall(
        val pitch: Double,
        val yaw: Double,
    )

    private class RecordingCameraControl(
        private val failThermalStreamAttempts: Int = 0,
    ) : CameraActionClient {
        val streamSources = mutableListOf<String>()
        val zoomRatios = mutableListOf<Double>()
        var setStreamSourceAttempts = 0

        override suspend fun startShootPhoto() = Unit
        override suspend fun startRecord() = Unit
        override suspend fun stopRecord() = Unit

        override suspend fun setStreamSource(source: String) {
            setStreamSourceAttempts += 1
            if (source == "thermal" && setStreamSourceAttempts <= failThermalStreamAttempts) {
                throw IllegalStateException("thermal-stream-reset-failed")
            }
            streamSources += source
        }

        override suspend fun setZoom(ratio: Double) {
            zoomRatios += ratio
        }

        override suspend fun setNightScene(enabled: Boolean) = Unit
        override suspend fun setLaserFillLight(enabled: Boolean) = Unit
    }

    private class RecordingMissionHold : MissionHoldControl {
        val calls = mutableListOf<String>()

        override suspend fun holdForConfirmation(): Boolean {
            calls += "hold"
            return true
        }

        override suspend fun resumeAfterConfirmation() {
            calls += "resume"
        }
    }

    private class RecordingVisibleSnapshotConfirmer(
        private val url: String?,
        private val throwOnConfirm: Boolean = false,
    ) : VisibleSnapshotConfirmer {
        var confirmCalls = 0

        override suspend fun confirm(
            taskId: String,
            eventId: String,
            droneSn: String,
            sourceTs: Long,
            snapshotPath: String,
            thermalSourceEventId: String?,
            thermalImageUrl: String?,
        ): String? {
            confirmCalls += 1
            if (throwOnConfirm) {
                throw IllegalStateException("visible-confirm-failed")
            }
            return url
        }
    }

    private class RecordingLaserRangefinder(
        vararg private val results: LaserRangefinderResult?,
    ) : LaserRangefinderClient {
        var calls = 0

        override suspend fun measure(): LaserRangefinderResult? {
            val index = calls.coerceAtMost(results.lastIndex)
            calls += 1
            return results.getOrNull(index)
        }
    }

    private class RecordingThermalSnapshotUploader(
        private val url: String?,
    ) : ThermalSnapshotUploader {
        val uploads = mutableListOf<Pair<String, String>>()

        override suspend fun upload(eventId: String, snapshotPath: String): String? {
            uploads += eventId to snapshotPath
            return url
        }
    }

    private class StaticBatteryProvider(
        private val percent: Int?,
    ) : BatteryProvider {
        override fun currentPercent(): Int? = percent
    }

    private class RecordingDualStreamApi(
        private val onRecord: (() -> Int)? = null,
    ) : DualStreamApi {
        override suspend fun reportAgentFire(body: okhttp3.RequestBody) =
            retrofit2.Response.success(okhttp3.ResponseBody.create(null, "{}"))
        var lastTaskEventTaskId: String? = null
        var lastTaskEventBody: DualStreamEventRequest? = null
        val taskEvents = mutableListOf<DualStreamEventRequest>()
        val recordMarkers = mutableListOf<Int>()

        override suspend fun heartbeat(droneSn: String, body: AgentHeartbeatRequest) = Unit
        override suspend fun status(droneSn: String, body: AgentStatusRequest) = Unit
        override suspend fun capability(droneSn: String, body: CapabilityReportRequest) = Unit
        override suspend fun pollCommand(droneSn: String): AgentApiEnvelope<AgentCommandResponse>? = null
        override suspend fun ackCommand(droneSn: String, body: AgentCommandAckRequest) = Unit

        override suspend fun recordTaskEvent(taskId: String, body: DualStreamEventRequest) {
            onRecord?.let { recordMarkers += it() }
            lastTaskEventTaskId = taskId
            lastTaskEventBody = body
            taskEvents += body
        }

        override suspend fun reportMsdkDeviceState(body: MsdkDeviceStateRequest) = Unit
        override suspend fun pollMsdkCommand(aircraftSn: String): AgentApiEnvelope<MsdkCommandResponse>? = null
        override suspend fun ackMsdkCommand(aircraftSn: String, body: MsdkCommandAckRequest) = Unit
    }

    private class StaticWindProvider(
        private val wind: WindReading?,
    ) : WindProvider {
        override suspend fun currentWind(): WindReading? = wind
    }

    private companion object {
        val processorApiByInstance = mutableMapOf<FireConfirmationProcessor, RecordingDualStreamApi>()
        val processorFlightByInstance = mutableMapOf<FireConfirmationProcessor, RecordingFlightControl>()

        fun appliedHotspot(
            temperatureC: Double?,
            region: ThermalMeasureRegion? = ThermalMeasureRegion(x = 0.40, y = 0.44, width = 0.12, height = 0.12),
            snapshotPath: String?,
        ): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.BOUND,
            playbackStatus = "shared-side-by-side-preview",
            thermalCenterTemperatureC = temperatureC,
            thermalMeasureRegion = region,
            thermalSnapshotPath = snapshotPath,
        )
    }
}

private fun horizontalDistanceM(a: AircraftLocation, b: AircraftLocation): Double {
    val metersPerLng = METERS_PER_LAT_DEG * cos(((a.latitude + b.latitude) / 2.0).toRadians()).coerceAtLeast(0.01)
    val dx = (a.longitude - b.longitude) * metersPerLng
    val dy = (a.latitude - b.latitude) * METERS_PER_LAT_DEG
    return hypot(dx, dy)
}

private fun bearingFrom(origin: AircraftLocation, point: AircraftLocation): Double {
    val metersPerLng = METERS_PER_LAT_DEG * cos(origin.latitude.toRadians()).coerceAtLeast(0.01)
    val dx = (point.longitude - origin.longitude) * metersPerLng
    val dy = (point.latitude - origin.latitude) * METERS_PER_LAT_DEG
    return atan2(dx, dy).toDegrees().normalizeDeg()
}

private fun angleDiffDeg(a: Double, b: Double): Double {
    val diff = abs(a.normalizeDeg() - b.normalizeDeg())
    return if (diff > 180.0) 360.0 - diff else diff
}

private fun Double.toRadians(): Double = this / 180.0 * PI

private fun Double.toDegrees(): Double = this * 180.0 / PI

private fun Double.normalizeDeg(): Double = ((this % 360.0) + 360.0) % 360.0
