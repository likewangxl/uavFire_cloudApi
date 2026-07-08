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
            LaserRangefinderResult(latitude = 34.00001, longitude = 108.00001, distanceM = 70.0, state = "NORMAL"),
            LaserRangefinderResult(latitude = 34.00002, longitude = 108.00002, distanceM = 72.0, state = "NORMAL"),
            LaserRangefinderResult(latitude = 34.0009, longitude = 108.0009, distanceM = 500.0, state = "NORMAL"),
            LaserRangefinderResult(latitude = 34.00003, longitude = 108.00003, distanceM = 74.0, state = "NORMAL"),
        )
        val processor = processorWithArrivingAircraft(laserRangefinder = laser)

        val result = processor.run(defaultRequest())

        assertEquals("laser-rangefinder", result.geoMethod)
        assertEquals(34.00002, result.preciseLat ?: -1.0, 1e-6)
        assertEquals(108.00002, result.preciseLng ?: -1.0, 1e-6)
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
        enabled: Boolean = true,
        aircraftLocationProvider: () -> AircraftLocation? = { AircraftLocation(34.0, 108.0, 120.0) },
        flyToTimeoutMs: Long = 5_000,
        pollIntervalMs: Long = 100,
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
            aircraftLocationProvider = aircraftLocationProvider,
            enabled = enabled,
            flyToTimeoutMs = flyToTimeoutMs,
            pollIntervalMs = pollIntervalMs,
            clockMs = { 1780059017562L },
        )
        processorApiByInstance[processor] = api
        return processor
    }

    private suspend fun processorWithArrivingAircraft(
        laserRangefinder: LaserRangefinderClient,
        streamProvider: ConfirmationStreamProvider = ConfirmationStreamProvider(),
    ): FireConfirmationProcessor {
        val sessionManager = runningSession(streamProvider)
        val flight = RecordingFlightControl()
        var aircraftLocation = AircraftLocation(34.0, 108.0, 120.0)
        val processor = processor(
            sessionManager = sessionManager,
            flight = flight,
            laserRangefinder = laserRangefinder,
            aircraftLocationProvider = { aircraftLocation },
        )
        flight.onFlyToPoint = { lat, lng, height ->
            aircraftLocation = AircraftLocation(lat, lng, height)
        }
        return processor
    }

    private fun processorApi(processor: FireConfirmationProcessor): RecordingDualStreamApi =
        requireNotNull(processorApiByInstance[processor])

    private fun defaultRequest(taskId: String = "task-fire-001") = FireConfirmationRequest(
        droneSn = "DRONE-001",
        taskId = taskId,
        fireLat = 34.0009,
        fireLng = 108.0009,
        fireAlt = 110.0,
    )

    private class ConfirmationStreamProvider(
        private val measureStatus: String = "applied",
        private val visibleSnapshotPath: String? = "/tmp/visible.jpg",
        private val thermalMeasureRegions: List<ThermalMeasureRegion> = listOf(
            ThermalMeasureRegion(x = 0.40, y = 0.44, width = 0.12, height = 0.12),
        ),
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
            onFlyToPointSuspend?.invoke(latitude, longitude, height)
            onFlyToPoint?.invoke(latitude, longitude, height)
        }

        override suspend fun setNavigationLight(enabled: Boolean) = Unit
    }

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

    private class RecordingDualStreamApi : DualStreamApi {
        var lastTaskEventTaskId: String? = null
        var lastTaskEventBody: DualStreamEventRequest? = null
        val taskEvents = mutableListOf<DualStreamEventRequest>()

        override suspend fun heartbeat(droneSn: String, body: AgentHeartbeatRequest) = Unit
        override suspend fun status(droneSn: String, body: AgentStatusRequest) = Unit
        override suspend fun capability(droneSn: String, body: CapabilityReportRequest) = Unit
        override suspend fun pollCommand(droneSn: String): AgentApiEnvelope<AgentCommandResponse>? = null
        override suspend fun ackCommand(droneSn: String, body: AgentCommandAckRequest) = Unit

        override suspend fun recordTaskEvent(taskId: String, body: DualStreamEventRequest) {
            lastTaskEventTaskId = taskId
            lastTaskEventBody = body
            taskEvents += body
        }

        override suspend fun reportMsdkDeviceState(body: MsdkDeviceStateRequest) = Unit
        override suspend fun pollMsdkCommand(aircraftSn: String): AgentApiEnvelope<MsdkCommandResponse>? = null
        override suspend fun ackMsdkCommand(aircraftSn: String, body: MsdkCommandAckRequest) = Unit
    }

    private companion object {
        val processorApiByInstance = mutableMapOf<FireConfirmationProcessor, RecordingDualStreamApi>()
    }
}
