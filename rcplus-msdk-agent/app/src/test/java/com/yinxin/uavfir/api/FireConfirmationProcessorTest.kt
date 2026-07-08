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
                thermalMeasureRegion = ThermalMeasureRegion(x = 0.40, y = 0.44, width = 0.12, height = 0.12),
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

        override suspend fun resetGimbal() {
            pitchOrResetCalls += 0.0
        }

        override suspend fun rotateGimbal(pitch: Double, yaw: Double, roll: Double) = Unit

        override suspend fun rotateGimbalToPitch(pitch: Double) {
            pitchOrResetCalls += pitch
        }
    }

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
        private val result: LaserRangefinderResult?,
    ) : LaserRangefinderClient {
        override suspend fun measure(): LaserRangefinderResult? = result
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
