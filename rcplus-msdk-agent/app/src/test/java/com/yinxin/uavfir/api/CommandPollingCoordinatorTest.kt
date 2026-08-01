package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.BoundStreamState
import com.yinxin.uavfir.stream.MockStreamProvider
import com.yinxin.uavfir.stream.StreamProvider
import com.yinxin.uavfir.stream.StreamStartResult
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import com.yinxin.uavfir.firedetection.CoordinatorArmingHealth
import com.yinxin.uavfir.firedetection.VisibleDetectorControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPollingCoordinatorTest {
    @Test
    fun pollerAppliesVersionedDisarmAndIgnoresOlderArm() = runTest {
        val control = VisibleDetectorControl {
            CoordinatorArmingHealth(
                featureEnabled = true, detectorArmRequested = true, visibleSourceActive = true,
                sourceGenerationValid = true, detectorHealthy = true, storeHealthy = true,
                outboxHealthy = true, missionAdaptersHealthy = true, safetyAdaptersHealthy = true,
                manualHoldActive = false, competingOwnerActive = false,
            )
        }
        val manager = DualStreamSessionManager(MockStreamProvider(), visibleDetectorControl = control)
        val disarmApi = RecordingDualStreamApi(AgentApiEnvelope(data = AgentCommandResponse(
            commandId = "cmd-disarm-8", droneSn = "DRONE-001", action = "visible-detector-disarm",
            status = "pending", urgent = true, params = mapOf("intentVersion" to 8.0),
        )))
        CommandPollingCoordinator(AgentBackendClient(disarmApi), manager, pollMsdk = false).pollOnce("DRONE-001")
        assertEquals("applied", disarmApi.lastAck?.status)

        val staleArmApi = RecordingDualStreamApi(AgentApiEnvelope(data = AgentCommandResponse(
            commandId = "cmd-arm-7", droneSn = "DRONE-001", action = "visible-detector-arm",
            status = "pending", urgent = true, params = mapOf("intentVersion" to 7.0),
        )))
        CommandPollingCoordinator(AgentBackendClient(staleArmApi), manager, pollMsdk = false).pollOnce("DRONE-001")

        assertEquals("ignored", staleArmApi.lastAck?.status)
        assertEquals("DISARMED", manager.detectorStatus().intent)
        assertEquals(8L, manager.detectorStatus().intentVersion)
    }

    @Test
    fun urgentPoller_executesUrgentLegacyDualStreamCommand() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-urgent-focus",
                    droneSn = "DRONE-001",
                    action = "focus-visible",
                    status = "pending",
                    urgent = true,
                ),
            ),
        )
        val manager = DualStreamSessionManager(MockStreamProvider())
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
            pollLegacyDualStreamUrgentOnly = true,
            pollMsdk = false,
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-urgent-focus", api.lastAck?.commandId)
        assertEquals("applied", api.lastAck?.status)
    }

    @Test
    fun poller_routesFireConfirmationParamsToRunner() = runTest {
        // 此前 AgentCommandResponse 缺 params 字段，后端派的抵近命令参数被丢弃，
        // agent 秒拒 fire-confirmation-params-required（2026-07-26 实飞抵近链首飞暴露）。
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-fire-approach",
                    droneSn = "DRONE-001",
                    action = "fire-confirmation-mission",
                    status = "pending",
                    urgent = true,
                    params = mapOf(
                        "lat" to 34.9604606,
                        "lng" to 109.3163910,
                        "alt" to 27.6,
                        "taskId" to "fire-DRONE-001",
                    ),
                ),
            ),
        )
        var received: FireConfirmationRequest? = null
        val manager = DualStreamSessionManager(MockStreamProvider()) { request ->
            received = request
            FireConfirmationResult(
                phaseReached = FireConfirmationPhase.IDLE,
                success = true,
                failureReason = null,
                closeMeasureTemperatureC = 120.0,
                preciseLat = request.fireLat,
                preciseLng = request.fireLng,
                resetCompleted = true,
            )
        }
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
            pollMsdk = false,
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("ignored", api.lastAck?.status)
        assertNull(received)
    }

    @Test
    fun poller_routesVisibleFireHoldAndAcknowledgesOwningEvent() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-visible-hold",
                    droneSn = "DRONE-001",
                    action = "visible-fire-hold",
                    status = "pending",
                    urgent = true,
                    params = mapOf("eventId" to "fire-visible-1"),
                ),
            ),
        )
        val flight = RecordingFlightControlActionClient()
        var now = 0L
        val locator = VisibleFireLaserLocator(
            missionHold = object : MissionHoldControl {
                override suspend fun holdForConfirmation(): Boolean = false
                override suspend fun resumeAfterConfirmation() = Unit
            },
            flightControl = flight,
            velocityProvider = AircraftVelocityProvider { VelocitySample(0.1, 0.1) },
            time = object : VisibleFireTime {
                override fun nowMs(): Long = now
                override suspend fun delayMs(durationMs: Long) {
                    now += durationMs
                }
            },
        )
        val manager = DualStreamSessionManager(
            streamProvider = MockStreamProvider(),
            visibleFireLaserLocator = locator,
        )
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
            pollMsdk = false,
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("ignored", api.lastAck?.status)
        assertTrue(flight.actions.isEmpty())
    }

    @Test
    fun pollerAcknowledgesLaserCoordinatesForOriginalVisibleFireEvent() = runTest {
        val roi = mapOf("x" to 0.4, "y" to 0.3, "width" to 0.2, "height" to 0.2)
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-visible-laser",
                    droneSn = "DRONE-001",
                    action = "visible-fire-laser-measure",
                    status = "pending",
                    urgent = true,
                    params = mapOf(
                        "eventId" to "fire-visible-1",
                        "taskId" to "task-1",
                        "visibleRoi" to roi,
                    ),
                ),
            ),
        )
        var now = 0L
        val laserValues = ArrayDeque(
            listOf(
                LaserRangefinderResult(34.960120, 109.316450, 530.0, 60.0, "NORMAL"),
                LaserRangefinderResult(34.960123, 109.316456, 531.0, 60.2, "NORMAL"),
                LaserRangefinderResult(34.960126, 109.316462, 532.0, 59.9, "NORMAL"),
            ),
        )
        val locator = VisibleFireLaserLocator(
            missionHold = object : MissionHoldControl {
                override suspend fun holdForConfirmation(): Boolean = true
                override suspend fun resumeAfterConfirmation() = Unit
            },
            flightControl = RecordingFlightControlActionClient(),
            velocityProvider = AircraftVelocityProvider { VelocitySample(0.1, 0.1) },
            time = object : VisibleFireTime {
                override fun nowMs(): Long = now
                override suspend fun delayMs(durationMs: Long) {
                    now += durationMs
                }
            },
            targetAimer = VisibleTargetAimer { _, _ -> true },
            laserRangefinder = object : LaserRangefinderClient {
                override suspend fun measure(): LaserRangefinderResult? =
                    laserValues.removeFirstOrNull()
            },
        )
        locator.hold("fire-visible-1")
        val manager = DualStreamSessionManager(
            streamProvider = MockStreamProvider(),
            visibleFireLaserLocator = locator,
        )
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
            pollMsdk = false,
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("ignored", api.lastAck?.status)
        assertNull(api.lastAck?.fireLat)
    }

    @Test
    fun urgentPoller_skipsLegacyDualStreamCommandWhenUrgentFieldIsMissing() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-normal-focus",
                    droneSn = "DRONE-001",
                    action = "focus-visible",
                    status = "pending",
                ),
            ),
        )
        val manager = DualStreamSessionManager(MockStreamProvider())
        val urgentCoordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
            pollLegacyDualStreamUrgentOnly = true,
            pollMsdk = false,
        )
        val normalCoordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
            pollMsdk = false,
        )

        urgentCoordinator.pollOnce("DRONE-001")
        assertNull(api.lastAck)

        normalCoordinator.pollOnce("DRONE-001")

        assertEquals("cmd-normal-focus", api.lastAck?.commandId)
        assertEquals("applied", api.lastAck?.status)
    }

    @Test
    fun urgentAndNormalPollers_doNotExecuteSameLegacyCommandConcurrently() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-urgent-start",
                    droneSn = "DRONE-001",
                    action = "start",
                    status = "pending",
                    urgent = true,
                ),
            ),
        )
        val provider = BlockingStartStreamProvider()
        val manager = DualStreamSessionManager(provider)
        val deduplicator = LegacyCommandDeduplicator()
        val urgentCoordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
            commandExecutionDeduplicator = deduplicator,
            pollLegacyDualStreamUrgentOnly = true,
            pollMsdk = false,
        )
        val normalCoordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
            commandExecutionDeduplicator = deduplicator,
            pollMsdk = false,
        )

        val urgentJob = launch { urgentCoordinator.pollOnce("DRONE-001") }
        provider.startEntered.await()
        normalCoordinator.pollOnce("DRONE-001")

        assertEquals(1, provider.startCalls)

        provider.releaseStart.complete(Unit)
        urgentJob.join()

        assertEquals(1, provider.startCalls)
        assertEquals("cmd-urgent-start", api.lastAck?.commandId)
    }

    @Test
    fun pollOnce_startCommandStartsSession_andAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-1",
                    droneSn = "DRONE-001",
                    action = "start",
                    status = "pending",
                ),
            ),
        )
        val manager = DualStreamSessionManager(MockStreamProvider())
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-1", api.lastAck?.commandId)
        assertEquals("applied", api.lastAck?.status)
        assertEquals("RUNNING", manager.state.value.name)
    }

    @Test
    fun pollOnce_withoutCommandDoesNotAcknowledge() = runTest {
        val api = RecordingDualStreamApi(nextCommand = null)
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = DualStreamSessionManager(MockStreamProvider()),
        )

        coordinator.pollOnce("DRONE-001")

        assertNull(api.lastAck)
    }

    @Test
    fun pollOnce_stopCommandStopsSession_andAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-2",
                    droneSn = "DRONE-001",
                    action = "stop",
                    status = "pending",
                ),
            ),
        )
        val manager = DualStreamSessionManager(MockStreamProvider())
        manager.start("DRONE-001")
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-2", api.lastAck?.commandId)
        assertEquals("applied", api.lastAck?.status)
        assertEquals("STOPPED", manager.state.value.name)
    }

    @Test
    fun pollOnce_focusVisibleAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-3",
                    droneSn = "DRONE-001",
                    action = "focus-visible",
                    status = "pending",
                ),
            ),
        )
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = DualStreamSessionManager(MockStreamProvider()),
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-3", api.lastAck?.commandId)
        assertEquals("applied", api.lastAck?.status)
        assertNull(api.lastAck?.message)
    }

    @Test
    fun pollOnce_focusThermalImmediatelyReportsRuntimeStatusWithTemperature() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-thermal",
                    droneSn = "DRONE-001",
                    action = "focus-thermal",
                    status = "pending",
                ),
            ),
        )
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = DualStreamSessionManager(ThermalTemperatureStreamProvider()),
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-thermal", api.lastAck?.commandId)
        assertEquals("applied", api.lastAck?.status)
        assertEquals(89.4, api.lastStatusBody?.thermalCenterTemperatureC ?: -1.0, 1e-6)
    }

    @Test
    fun pollOnce_measureThermalRegionCommandAcknowledgesMeasuredTemperaturePayload() = runTest {
        val roi = mapOf(
            "x" to 0.25,
            "y" to 0.30,
            "width" to 0.20,
            "height" to 0.15,
        )
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-measure",
                    droneSn = "DRONE-001",
                    action = "measure-thermal-region",
                    status = "pending",
                    taskId = "task-001",
                    sourceTs = 1779163200000L,
                    thermalMeasureRoi = roi,
                ),
            ),
        )
        val streamProvider = RegionTemperatureStreamProvider()
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = DualStreamSessionManager(streamProvider),
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-measure", api.lastAck?.commandId)
        assertEquals("applied", api.lastAck?.status)
        assertEquals("task-001", api.lastAck?.taskId)
        assertEquals(1779163200000L, api.lastAck?.sourceTs)
        assertEquals(57.6, api.lastAck?.thermalTemperature ?: -1.0, 1e-6)
        assertEquals(roi, api.lastAck?.thermalMeasureRoi)
        assertEquals(ThermalMeasureRegion(0.25, 0.30, 0.20, 0.15), streamProvider.lastRequestedRoi)
    }


    @Test
    fun pollOnce_hangingCommandAcknowledgesFailedTimeout() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-timeout",
                    droneSn = "DRONE-001",
                    action = "start",
                    status = "pending",
                ),
            ),
        )
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = DualStreamSessionManager(HangingStreamProvider()),
            commandTimeoutMs = 100,
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-timeout", api.lastAck?.commandId)
        assertEquals("failed", api.lastAck?.status)
        assertEquals("command-timeout:start", api.lastAck?.message)
    }

    @Test
    fun pollOnce_commandForDifferentDroneAcknowledgesIgnored() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-4",
                    droneSn = "DRONE-OTHER",
                    action = "start",
                    status = "pending",
                ),
            ),
        )
        val manager = DualStreamSessionManager(MockStreamProvider())
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-4", api.lastAck?.commandId)
        assertEquals("ignored", api.lastAck?.status)
        assertTrue(api.lastAck?.message?.contains("DRONE-OTHER") == true)
        assertEquals("INIT", manager.state.value.name)
    }

    @Test
    fun pollOnce_msdkFocusVisibleCommandExecutesMappedDualStreamCommand_andAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = null,
            nextMsdkCommand = AgentApiEnvelope(
                data = MsdkCommandResponse(
                    commandId = "msdk-focus-1",
                    aircraftSn = "AIRCRAFT-001",
                    command = "focus_visible",
                    params = emptyMap(),
                    status = "PENDING",
                ),
            ),
        )
        val executor = RecordingMsdkCommandExecutor(
            result = MsdkCommandExecutionResult(
                status = "APPLIED",
                message = "focus-visible applied",
            ),
        )
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            commandExecutor = executor,
        )

        coordinator.pollOnce("AIRCRAFT-001")

        assertEquals("AIRCRAFT-001", executor.lastAircraftSn)
        assertEquals("focus_visible", executor.lastCommand?.command)
        assertEquals("msdk-focus-1", api.lastMsdkAck?.commandId)
        assertEquals("APPLIED", api.lastMsdkAck?.status)
        assertEquals("focus-visible applied", api.lastMsdkAck?.message)
        assertNull(api.lastAck)
    }

    @Test
    fun pollOnce_msdkTakeoffWithFlightExecutorAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = null,
            nextMsdkCommand = AgentApiEnvelope(
                data = MsdkCommandResponse(
                    commandId = "msdk-takeoff-1",
                    aircraftSn = "AIRCRAFT-001",
                    command = "takeoff",
                    params = emptyMap(),
                    status = "PENDING",
                ),
            ),
        )
        val flightControlClient = RecordingFlightControlActionClient()
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            commandExecutor = DualStreamMsdkCommandExecutor(
                dualStreamExecutor = DualStreamSessionManager(MockStreamProvider()),
                flightControlClient = flightControlClient,
            ),
        )

        coordinator.pollOnce("AIRCRAFT-001")

        assertEquals(listOf("takeoff"), flightControlClient.actions)
        assertEquals("msdk-takeoff-1", api.lastMsdkAck?.commandId)
        assertEquals("APPLIED", api.lastMsdkAck?.status)
        assertEquals("takeoff applied", api.lastMsdkAck?.message)
    }

    @Test
    fun pollOnce_msdkReturnHomeWithFlightExecutorAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = null,
            nextMsdkCommand = AgentApiEnvelope(
                data = MsdkCommandResponse(
                    commandId = "msdk-rth-1",
                    aircraftSn = "AIRCRAFT-001",
                    command = "return_home",
                    params = emptyMap(),
                    status = "PENDING",
                ),
            ),
        )
        val flightControlClient = RecordingFlightControlActionClient()
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            commandExecutor = DualStreamMsdkCommandExecutor(
                dualStreamExecutor = DualStreamSessionManager(MockStreamProvider()),
                flightControlClient = flightControlClient,
            ),
        )

        coordinator.pollOnce("AIRCRAFT-001")

        assertEquals(listOf("return_home"), flightControlClient.actions)
        assertEquals("msdk-rth-1", api.lastMsdkAck?.commandId)
        assertEquals("APPLIED", api.lastMsdkAck?.status)
        assertEquals("return_home applied", api.lastMsdkAck?.message)
    }

    @Test
    fun pollOnce_msdkCancelReturnHomeWithFlightExecutorAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = null,
            nextMsdkCommand = AgentApiEnvelope(
                data = MsdkCommandResponse(
                    commandId = "msdk-cancel-rth-1",
                    aircraftSn = "AIRCRAFT-001",
                    command = "cancel_return_home",
                    params = emptyMap(),
                    status = "PENDING",
                ),
            ),
        )
        val flightControlClient = RecordingFlightControlActionClient()
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            commandExecutor = DualStreamMsdkCommandExecutor(
                dualStreamExecutor = DualStreamSessionManager(MockStreamProvider()),
                flightControlClient = flightControlClient,
            ),
        )

        coordinator.pollOnce("AIRCRAFT-001")

        assertEquals(listOf("cancel_return_home"), flightControlClient.actions)
        assertEquals("msdk-cancel-rth-1", api.lastMsdkAck?.commandId)
        assertEquals("APPLIED", api.lastMsdkAck?.status)
        assertEquals("cancel_return_home applied", api.lastMsdkAck?.message)
    }

    @Test
    fun pollOnce_msdkLandingWithFlightExecutorAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = null,
            nextMsdkCommand = AgentApiEnvelope(
                data = MsdkCommandResponse(
                    commandId = "msdk-land-1",
                    aircraftSn = "AIRCRAFT-001",
                    command = "land",
                    params = emptyMap(),
                    status = "PENDING",
                ),
            ),
        )
        val flightControlClient = RecordingFlightControlActionClient()
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            commandExecutor = DualStreamMsdkCommandExecutor(
                dualStreamExecutor = DualStreamSessionManager(MockStreamProvider()),
                flightControlClient = flightControlClient,
            ),
        )

        coordinator.pollOnce("AIRCRAFT-001")

        assertEquals(listOf("land"), flightControlClient.actions)
        assertEquals("msdk-land-1", api.lastMsdkAck?.commandId)
        assertEquals("APPLIED", api.lastMsdkAck?.status)
        assertEquals("land applied", api.lastMsdkAck?.message)
    }

    @Test
    fun pollOnce_msdkEmergencyStopWithFlightExecutorAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = null,
            nextMsdkCommand = AgentApiEnvelope(
                data = MsdkCommandResponse(
                    commandId = "msdk-stop-1",
                    aircraftSn = "AIRCRAFT-001",
                    command = "emergency_stop",
                    params = emptyMap(),
                    status = "PENDING",
                ),
            ),
        )
        val flightControlClient = RecordingFlightControlActionClient()
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            commandExecutor = DualStreamMsdkCommandExecutor(
                dualStreamExecutor = DualStreamSessionManager(MockStreamProvider()),
                flightControlClient = flightControlClient,
            ),
        )

        coordinator.pollOnce("AIRCRAFT-001")

        assertEquals(listOf("emergency_stop"), flightControlClient.actions)
        assertEquals("msdk-stop-1", api.lastMsdkAck?.commandId)
        assertEquals("APPLIED", api.lastMsdkAck?.status)
        assertEquals("emergency_stop applied", api.lastMsdkAck?.message)
    }

    @Test
    fun pollOnce_msdkVirtualStickWithFlightExecutorAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = null,
            nextMsdkCommand = AgentApiEnvelope(
                data = MsdkCommandResponse(
                    commandId = "msdk-stick-1",
                    aircraftSn = "AIRCRAFT-001",
                    command = "virtual_stick",
                    params = mapOf("key" to "ArrowUp", "duration_ms" to 800.0),
                    status = "PENDING",
                ),
            ),
        )
        val flightControlClient = RecordingFlightControlActionClient()
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            commandExecutor = DualStreamMsdkCommandExecutor(
                dualStreamExecutor = DualStreamSessionManager(MockStreamProvider()),
                flightControlClient = flightControlClient,
            ),
        )

        coordinator.pollOnce("AIRCRAFT-001")

        assertEquals(listOf("virtual_stick:ArrowUp:800"), flightControlClient.actions)
        assertEquals("msdk-stick-1", api.lastMsdkAck?.commandId)
        assertEquals("APPLIED", api.lastMsdkAck?.status)
        assertEquals("virtual_stick applied", api.lastMsdkAck?.message)
    }

    @Test
    fun pollOnce_msdkFlyToPointWithFlightExecutorAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = null,
            nextMsdkCommand = AgentApiEnvelope(
                data = MsdkCommandResponse(
                    commandId = "msdk-fly-to-1",
                    aircraftSn = "AIRCRAFT-001",
                    command = "fly_to_point",
                    params = mapOf(
                        "latitude" to 34.1,
                        "longitude" to 108.9,
                        "height" to 50.0,
                        "speed" to 5.0,
                    ),
                    status = "PENDING",
                ),
            ),
        )
        val flightControlClient = RecordingFlightControlActionClient()
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            commandExecutor = DualStreamMsdkCommandExecutor(
                dualStreamExecutor = DualStreamSessionManager(MockStreamProvider()),
                flightControlClient = flightControlClient,
            ),
        )

        coordinator.pollOnce("AIRCRAFT-001")

        assertEquals(listOf("fly_to_point:34.1:108.9:50.0:5.0"), flightControlClient.actions)
        assertEquals("msdk-fly-to-1", api.lastMsdkAck?.commandId)
        assertEquals("APPLIED", api.lastMsdkAck?.status)
        assertEquals("fly_to_point applied", api.lastMsdkAck?.message)
    }

    @Test
    fun pollOnce_msdkHoverAndStopFlyToPointDoNotUseEmergencyStop() = runTest {
        val hoverClient = RecordingFlightControlActionClient()
        val hoverCoordinator = CommandPollingCoordinator(
            client = AgentBackendClient(
                RecordingDualStreamApi(
                    nextCommand = null,
                    nextMsdkCommand = AgentApiEnvelope(
                        data = MsdkCommandResponse(
                            commandId = "msdk-hover-1",
                            aircraftSn = "AIRCRAFT-001",
                            command = "hover",
                            params = emptyMap(),
                            status = "PENDING",
                        ),
                    ),
                ),
            ),
            commandExecutor = DualStreamMsdkCommandExecutor(
                dualStreamExecutor = DualStreamSessionManager(MockStreamProvider()),
                flightControlClient = hoverClient,
            ),
        )
        hoverCoordinator.pollOnce("AIRCRAFT-001")

        val stopClient = RecordingFlightControlActionClient()
        val stopCoordinator = CommandPollingCoordinator(
            client = AgentBackendClient(
                RecordingDualStreamApi(
                    nextCommand = null,
                    nextMsdkCommand = AgentApiEnvelope(
                        data = MsdkCommandResponse(
                            commandId = "msdk-stop-fly-1",
                            aircraftSn = "AIRCRAFT-001",
                            command = "stop_fly_to_point",
                            params = emptyMap(),
                            status = "PENDING",
                        ),
                    ),
                ),
            ),
            commandExecutor = DualStreamMsdkCommandExecutor(
                dualStreamExecutor = DualStreamSessionManager(MockStreamProvider()),
                flightControlClient = stopClient,
            ),
        )
        stopCoordinator.pollOnce("AIRCRAFT-001")

        assertEquals(listOf("hover"), hoverClient.actions)
        assertEquals(listOf("stop_fly_to_point"), stopClient.actions)
    }

    @Test
    fun pollOnce_msdkGimbalAndCameraCommandsUsePayloadClients() = runTest {
        val payloadClient = RecordingPayloadActionClient()
        val commands = listOf(
            "gimbal_reset" to emptyMap<String, Any>(),
            "gimbal_rotate" to mapOf("pitch" to 8.0, "yaw" to -4.0, "roll" to 0.0),
            "camera_start_photo" to emptyMap(),
            "camera_start_record" to emptyMap(),
            "camera_stop_record" to emptyMap(),
            "camera_stream_source" to mapOf("source" to "thermal"),
            "camera_zoom" to mapOf("ratio" to 4.0),
            "night_scene" to mapOf("enabled" to true),
            "laser_fill_light" to mapOf("enabled" to false),
        )

        for ((index, command) in commands.withIndex()) {
            val api = RecordingDualStreamApi(
                nextCommand = null,
                nextMsdkCommand = AgentApiEnvelope(
                    data = MsdkCommandResponse(
                        commandId = "msdk-payload-$index",
                        aircraftSn = "AIRCRAFT-001",
                        command = command.first,
                        params = command.second,
                        status = "PENDING",
                    ),
                ),
            )
            val coordinator = CommandPollingCoordinator(
                client = AgentBackendClient(api),
                commandExecutor = DualStreamMsdkCommandExecutor(
                    dualStreamExecutor = DualStreamSessionManager(MockStreamProvider()),
                    gimbalClient = payloadClient,
                    cameraClient = payloadClient,
                ),
            )

            coordinator.pollOnce("AIRCRAFT-001")

            assertEquals("APPLIED", api.lastMsdkAck?.status)
        }

        assertEquals(
            listOf(
                "gimbal_reset",
                "gimbal_rotate:8.0:-4.0:0.0",
                "camera_start_photo",
                "camera_start_record",
                "camera_stop_record",
                "camera_stream_source:thermal",
                "camera_zoom:4.0",
                "night_scene:true",
                "laser_fill_light:false",
            ),
            payloadClient.actions,
        )
    }

    private class RecordingDualStreamApi(
        private val nextCommand: AgentApiEnvelope<AgentCommandResponse>?,
        private val nextMsdkCommand: AgentApiEnvelope<MsdkCommandResponse>? = null,
    ) : DualStreamApi {
        override suspend fun reportAgentFire(agentToken: String, body: okhttp3.RequestBody) =
            retrofit2.Response.success(okhttp3.ResponseBody.create(null, "{}"))
        var lastAck: AgentCommandAckRequest? = null
        var lastMsdkAck: MsdkCommandAckRequest? = null
        var lastStatusBody: AgentStatusRequest? = null

        override suspend fun heartbeat(
            agentToken: String,
            droneSn: String,
            body: AgentHeartbeatRequest,
        ) = Unit

        override suspend fun status(
            agentToken: String,
            droneSn: String,
            body: AgentStatusRequest,
        ) {
            lastStatusBody = body
        }

        override suspend fun capability(
            agentToken: String,
            droneSn: String,
            body: CapabilityReportRequest,
        ) = Unit

        override suspend fun pollCommand(agentToken: String, droneSn: String): AgentApiEnvelope<AgentCommandResponse>? = nextCommand

        override suspend fun ackCommand(
            agentToken: String,
            droneSn: String,
            body: AgentCommandAckRequest,
        ) {
            lastAck = body
        }

        override suspend fun recordTaskEvent(
            taskId: String,
            body: DualStreamEventRequest,
        ) = Unit

        override suspend fun reportMsdkDeviceState(body: MsdkDeviceStateRequest) = Unit

        override suspend fun pollMsdkCommand(aircraftSn: String): AgentApiEnvelope<MsdkCommandResponse>? = nextMsdkCommand

        override suspend fun ackMsdkCommand(
            aircraftSn: String,
            body: MsdkCommandAckRequest,
        ) {
            lastMsdkAck = body
        }
    }

    private class ThermalTemperatureStreamProvider : StreamProvider {
        override suspend fun start(droneSn: String): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.IDLE,
        )

        override suspend fun focusVisible(droneSn: String): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.IDLE,
        )

        override suspend fun focusThermal(droneSn: String): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.BOUND,
            thermalCenterTemperatureC = 89.4,
        )

        override suspend fun stop() = Unit
    }

    private class RegionTemperatureStreamProvider : StreamProvider {
        var lastRequestedRoi: ThermalMeasureRegion? = null

        override suspend fun start(droneSn: String): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.IDLE,
        )

        override suspend fun focusVisible(droneSn: String): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.IDLE,
        )

        override suspend fun focusThermal(droneSn: String): StreamStartResult {
            return focusThermal(droneSn, null)
        }

        override suspend fun focusThermal(
            droneSn: String,
            thermalMeasureRegion: ThermalMeasureRegion?,
        ): StreamStartResult {
            lastRequestedRoi = thermalMeasureRegion
            return StreamStartResult(
                visibleState = BoundStreamState.BOUND,
                thermalState = BoundStreamState.BOUND,
                thermalCenterTemperatureC = 57.6,
                thermalMeasureRegion = ThermalMeasureRegion(0.42, 0.46, 0.08, 0.08),
            )
        }

        override suspend fun stop() = Unit
    }

    private class RecordingMsdkCommandExecutor(
        private val result: MsdkCommandExecutionResult,
    ) : MsdkCommandExecutor {
        var lastAircraftSn: String? = null
        var lastCommand: MsdkCommandResponse? = null

        override suspend fun execute(
            aircraftSn: String,
            command: MsdkCommandResponse,
        ): MsdkCommandExecutionResult {
            lastAircraftSn = aircraftSn
            lastCommand = command
            return result
        }
    }

    private class RecordingFlightControlActionClient : FlightControlActionClient {
        val actions = mutableListOf<String>()

        override suspend fun startTakeoff() {
            actions += "takeoff"
        }

        override suspend fun startGoHome() {
            actions += "return_home"
        }

        override suspend fun stopGoHome() {
            actions += "cancel_return_home"
        }

        override suspend fun startAutoLanding() {
            actions += "land"
        }

        override suspend fun stopAutoLanding() {
            actions += "stop_landing"
        }

        override suspend fun emergencyStop() {
            actions += "emergency_stop"
        }

        override suspend fun hover() {
            actions += "hover"
        }

        override suspend fun stopFlyToPoint() {
            actions += "stop_fly_to_point"
        }

        override suspend fun sendVirtualStick(
            key: String,
            durationMs: Long,
        ) {
            actions += "virtual_stick:$key:$durationMs"
        }

        override suspend fun flyToPoint(
            latitude: Double,
            longitude: Double,
            height: Double,
            speed: Double,
        ) {
            actions += "fly_to_point:$latitude:$longitude:$height:$speed"
        }

        override suspend fun setNavigationLight(enabled: Boolean) {
            actions += "navigation_light:$enabled"
        }
    }

    private class RecordingPayloadActionClient : GimbalActionClient, CameraActionClient {
        val actions = mutableListOf<String>()

        override suspend fun resetGimbal() {
            actions += "gimbal_reset"
        }

        override suspend fun rotateGimbal(
            pitch: Double,
            yaw: Double,
            roll: Double,
        ) {
            actions += "gimbal_rotate:$pitch:$yaw:$roll"
        }

        override suspend fun rotateGimbalBy(pitchDelta: Double, yawDelta: Double) {
            actions += "gimbal_rotate_by:$pitchDelta:$yawDelta"
        }

        override suspend fun rotateGimbalToPitch(pitch: Double) {
            actions += "gimbal_pitch:$pitch"
        }

        override suspend fun startShootPhoto() {
            actions += "camera_start_photo"
        }

        override suspend fun startRecord() {
            actions += "camera_start_record"
        }

        override suspend fun stopRecord() {
            actions += "camera_stop_record"
        }

        override suspend fun setStreamSource(source: String) {
            actions += "camera_stream_source:$source"
        }

        override suspend fun setZoom(ratio: Double) {
            actions += "camera_zoom:$ratio"
        }

        override suspend fun setNightScene(enabled: Boolean) {
            actions += "night_scene:$enabled"
        }

        override suspend fun setLaserFillLight(enabled: Boolean) {
            actions += "laser_fill_light:$enabled"
        }
    }

    private class HangingStreamProvider : StreamProvider {
        override suspend fun start(droneSn: String): StreamStartResult {
            awaitCancellation()
        }

        override suspend fun focusVisible(droneSn: String): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.IDLE,
        )

        override suspend fun focusThermal(droneSn: String): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.BOUND,
        )

        override suspend fun stop() = Unit
    }

    private class BlockingStartStreamProvider : StreamProvider {
        var startCalls = 0
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()

        override suspend fun start(droneSn: String): StreamStartResult {
            startCalls += 1
            startEntered.complete(Unit)
            releaseStart.await()
            return StreamStartResult(
                visibleState = BoundStreamState.BOUND,
                thermalState = BoundStreamState.IDLE,
            )
        }

        override suspend fun focusVisible(droneSn: String): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.IDLE,
        )

        override suspend fun focusThermal(droneSn: String): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.BOUND,
        )

        override suspend fun stop() = Unit
    }
}
