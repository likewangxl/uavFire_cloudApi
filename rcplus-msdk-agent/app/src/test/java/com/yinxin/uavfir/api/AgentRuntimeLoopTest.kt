package com.yinxin.uavfir.api

import com.yinxin.uavfir.sdk.CameraCapability
import com.yinxin.uavfir.sdk.DjiDeviceIdentity
import com.yinxin.uavfir.sdk.DjiDeviceSessionAdapter
import com.yinxin.uavfir.sdk.DjiDeviceState
import com.yinxin.uavfir.sdk.DjiTelemetry
import com.yinxin.uavfir.session.AgentConnectionState
import com.yinxin.uavfir.session.DualStreamCommandExecutor
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.session.DualStreamSessionState
import com.yinxin.uavfir.stream.BoundStreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeLoopTest {
    @Test
    fun tickOnce_reportsHeartbeatStatusCapability_andPollsCommands() = runTest {
        val api = RecordingDualStreamApi()
        val reporter = AgentReporter(AgentBackendClient(api))
        val poller = RecordingCommandPoller()
        val loop = AgentRuntimeLoop(
            deviceSession = FakeDeviceSession(
                DjiDeviceState(
                    connectionState = AgentConnectionState.CAPABILITY_READY,
                    capability = CameraCapability(
                        visibleSupported = true,
                        thermalSupported = true,
                    ),
                    telemetry = DjiTelemetry(
                        latitude = 34.123456,
                        longitude = 108.123456,
                        height = 12.5,
                        elevation = 450.0,
                        horizontalSpeed = 3.2,
                        verticalSpeed = -0.4,
                        batteryPercent = 86,
                    ),
                    aircraftModel = "Matrice 4T",
                ),
            ),
            reporter = reporter,
            commandPoller = poller,
            sessionManager = FakeCommandExecutor(DualStreamSessionState.RUNNING),
            scope = backgroundScope,
        )

        loop.tickOnce("DRONE-001")

        assertEquals("DRONE-001", api.lastHeartbeatDroneSn)
        assertEquals("RUNNING", api.lastHeartbeatBody?.sessionState)
        assertEquals("DRONE-001", api.lastStatusDroneSn)
        assertTrue(api.lastStatusBody?.message?.contains("CAPABILITY_READY") == true)
        assertEquals("running", api.lastStatusBody?.visibleState)
        assertEquals("degraded", api.lastStatusBody?.thermalState)
        assertEquals("thermal-stream-source-unavailable", api.lastStatusBody?.statusReason)
        assertEquals("DRONE-001", api.lastCapabilityDroneSn)
        assertEquals("DRONE-001", api.lastMsdkDeviceState?.aircraftSn)
        assertEquals("RC_PLUS_LOCAL", api.lastMsdkDeviceState?.gatewaySn)
        assertEquals(true, api.lastMsdkDeviceState?.online)
        assertEquals("CAPABILITY_READY", api.lastMsdkDeviceState?.connectionState)
        assertEquals("Matrice 4T", api.lastMsdkDeviceState?.deviceName)
        assertEquals("Matrice 4T", api.lastMsdkDeviceState?.model)
        assertEquals(34.123456, api.lastMsdkDeviceState?.latitude)
        assertEquals(108.123456, api.lastMsdkDeviceState?.longitude)
        assertEquals(12.5, api.lastMsdkDeviceState?.height)
        assertEquals(450.0, api.lastMsdkDeviceState?.elevation)
        assertEquals(3.2, api.lastMsdkDeviceState?.horizontalSpeed)
        assertEquals(-0.4, api.lastMsdkDeviceState?.verticalSpeed)
        assertEquals(86, api.lastMsdkDeviceState?.batteryPercent)
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("visibleStream"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("thermalFocus"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("takeoff"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("land"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("returnHome"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("cancelReturnHome"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("emergencyStop"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("hover"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("virtualStick"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("flyToPoint"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("gimbal"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("gimbalReset"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("gimbalRotate"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("camera"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("cameraPhoto"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("cameraRecord"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("cameraStreamSource"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("cameraZoom"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("nightScene"))
        assertEquals(true, api.lastMsdkDeviceState?.capabilities?.get("laserFillLight"))
        assertEquals(1, poller.pollCount)
    }

    @Test
    fun tickOnce_usesDynamicDeviceIdentityInsteadOfConfiguredDroneSn() = runTest {
        val api = RecordingDualStreamApi()
        val reporter = AgentReporter(AgentBackendClient(api))
        val poller = RecordingCommandPoller()
        val loop = AgentRuntimeLoop(
            deviceSession = FakeDeviceSession(
                DjiDeviceState(
                    connectionState = AgentConnectionState.CAPABILITY_READY,
                    identity = DjiDeviceIdentity(
                        gatewaySn = "RC-DYNAMIC",
                        aircraftSn = "AIRCRAFT-DYNAMIC",
                    ),
                    capability = CameraCapability(
                        visibleSupported = true,
                        thermalSupported = false,
                    ),
                ),
            ),
            reporter = reporter,
            commandPoller = poller,
            sessionManager = FakeCommandExecutor(DualStreamSessionState.RUNNING),
            scope = backgroundScope,
        )

        loop.tickOnce("CONFIGURED-SN")

        assertEquals("AIRCRAFT-DYNAMIC", api.lastHeartbeatDroneSn)
        assertEquals("AIRCRAFT-DYNAMIC", api.lastStatusDroneSn)
        assertEquals("AIRCRAFT-DYNAMIC", api.lastCapabilityDroneSn)
        assertEquals("AIRCRAFT-DYNAMIC", api.lastMsdkDeviceState?.aircraftSn)
        assertEquals("RC-DYNAMIC", api.lastMsdkDeviceState?.gatewaySn)
        assertEquals("AIRCRAFT-DYNAMIC", poller.lastDroneSn)
    }

    @Test
    fun tickOnce_marksPreviousIdentityOfflineWhenAircraftIdentityChanges() = runTest {
        val api = RecordingDualStreamApi()
        val loop = AgentRuntimeLoop(
            deviceSession = SequenceDeviceSession(
                listOf(
                    DjiDeviceState(
                        connectionState = AgentConnectionState.CAPABILITY_READY,
                        identity = DjiDeviceIdentity("RC-1", "AIRCRAFT-OLD"),
                    ),
                    DjiDeviceState(
                        connectionState = AgentConnectionState.CAPABILITY_READY,
                        identity = DjiDeviceIdentity("RC-2", "AIRCRAFT-NEW"),
                    ),
                ),
            ),
            reporter = AgentReporter(AgentBackendClient(api)),
            commandPoller = RecordingCommandPoller(),
            sessionManager = FakeCommandExecutor(DualStreamSessionState.RUNNING),
            scope = backgroundScope,
        )

        loop.tickOnce("CONFIGURED-SN")
        loop.tickOnce("CONFIGURED-SN")

        assertTrue(api.msdkDeviceStates.any {
            it.aircraftSn == "AIRCRAFT-OLD" && !it.online && it.connectionState == "DISCONNECTED"
        })
        assertEquals("AIRCRAFT-NEW", api.lastMsdkDeviceState?.aircraftSn)
        assertEquals(true, api.lastMsdkDeviceState?.online)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start_runsRepeatedTicksUntilStopped() = runTest {
        val api = RecordingDualStreamApi()
        val loop = AgentRuntimeLoop(
            deviceSession = FakeDeviceSession(
                DjiDeviceState(connectionState = AgentConnectionState.SDK_READY),
            ),
            reporter = AgentReporter(AgentBackendClient(api)),
            commandPoller = RecordingCommandPoller(),
            sessionManager = FakeCommandExecutor(DualStreamSessionState.INIT),
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            intervalMs = 1_000,
        )

        loop.start("DRONE-LOOP")
        advanceTimeBy(2_500)
        loop.stop()
        val heartbeatCountBefore = api.heartbeatCount
        advanceTimeBy(2_000)

        assertTrue(heartbeatCountBefore >= 3)
        assertEquals(heartbeatCountBefore, api.heartbeatCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start_pollsUrgentCommandsMoreFrequentlyThanHeartbeat() = runTest {
        val api = RecordingDualStreamApi()
        val regularPoller = RecordingCommandPoller()
        val urgentPoller = RecordingCommandPoller()
        val loop = AgentRuntimeLoop(
            deviceSession = FakeDeviceSession(
                DjiDeviceState(connectionState = AgentConnectionState.SDK_READY),
            ),
            reporter = AgentReporter(AgentBackendClient(api)),
            commandPoller = regularPoller,
            urgentCommandPoller = urgentPoller,
            sessionManager = FakeCommandExecutor(DualStreamSessionState.INIT),
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            intervalMs = 1_000,
            urgentCommandIntervalMs = 200,
        )

        loop.start("DRONE-LOOP")
        advanceTimeBy(450)
        loop.stop()

        assertEquals(1, api.heartbeatCount)
        assertEquals(1, regularPoller.pollCount)
        assertTrue(urgentPoller.pollCount >= 3)
    }

    @Test
    fun tickOnce_retriesStreamStartWithBackoffWhenSessionFailed() = runTest {
        val api = RecordingDualStreamApi()
        val executor = RecordingCommandExecutor(DualStreamSessionState.FAILED)
        var now = 1_000L
        val loop = AgentRuntimeLoop(
            deviceSession = FakeDeviceSession(
                DjiDeviceState(connectionState = AgentConnectionState.CAPABILITY_READY),
            ),
            reporter = AgentReporter(AgentBackendClient(api)),
            commandPoller = RecordingCommandPoller(),
            sessionManager = executor,
            scope = backgroundScope,
            sessionRetryIntervalMs = 15_000L,
            clockMs = { now },
        )

        loop.tickOnce("DRONE-001")
        now = 6_000L
        loop.tickOnce("DRONE-001")
        now = 16_000L
        loop.tickOnce("DRONE-001")

        // 首次立即重试，退避窗（15s）内不重试，窗满后再试
        assertEquals(listOf("start", "start"), executor.executedActions)
    }

    @Test
    fun tickOnce_doesNotRetryStreamStartForPlaceholderAircraftIdentity() = runTest {
        val api = RecordingDualStreamApi()
        val executor = RecordingCommandExecutor(DualStreamSessionState.FAILED)
        val loop = AgentRuntimeLoop(
            deviceSession = FakeDeviceSession(
                DjiDeviceState(
                    connectionState = AgentConnectionState.CAPABILITY_READY,
                    identity = DjiDeviceIdentity(
                        gatewaySn = "RC_PLUS_LOCAL",
                        aircraftSn = "UNKNOWN-AIRCRAFT-8L5CM9C00102ML",
                    ),
                ),
            ),
            reporter = AgentReporter(AgentBackendClient(api)),
            commandPoller = RecordingCommandPoller(),
            sessionManager = executor,
            scope = backgroundScope,
            clockMs = { 1_000L },
        )

        loop.tickOnce("")

        // 占位身份下起流会把流名推成 UNKNOWN-AIRCRAFT-*，前端按真机 SN 永远取不到
        assertEquals(emptyList<String>(), executor.executedActions)
    }

    @Test
    fun tickOnce_doesNotRetryStreamStartWhenSessionNotFailedOrLinkDown() = runTest {
        val api = RecordingDualStreamApi()
        val runningExecutor = RecordingCommandExecutor(DualStreamSessionState.RUNNING)
        AgentRuntimeLoop(
            deviceSession = FakeDeviceSession(
                DjiDeviceState(connectionState = AgentConnectionState.CAPABILITY_READY),
            ),
            reporter = AgentReporter(AgentBackendClient(api)),
            commandPoller = RecordingCommandPoller(),
            sessionManager = runningExecutor,
            scope = backgroundScope,
            clockMs = { 1_000L },
        ).tickOnce("DRONE-001")
        assertEquals(emptyList<String>(), runningExecutor.executedActions)

        val failedExecutor = RecordingCommandExecutor(DualStreamSessionState.FAILED)
        AgentRuntimeLoop(
            deviceSession = FakeDeviceSession(
                DjiDeviceState(connectionState = AgentConnectionState.ERROR),
            ),
            reporter = AgentReporter(AgentBackendClient(api)),
            commandPoller = RecordingCommandPoller(),
            sessionManager = failedExecutor,
            scope = backgroundScope,
            clockMs = { 1_000L },
        ).tickOnce("DRONE-001")
        // 链路 ERROR 时不做无谓重试
        assertEquals(emptyList<String>(), failedExecutor.executedActions)
    }

    private class FakeDeviceSession(
        private val state: DjiDeviceState,
    ) : DjiDeviceSessionAdapter {
        override suspend fun initialize(): DjiDeviceState = state
    }

    private class SequenceDeviceSession(
        private val states: List<DjiDeviceState>,
    ) : DjiDeviceSessionAdapter {
        private var index = 0

        override suspend fun initialize(): DjiDeviceState {
            val state = states[index.coerceAtMost(states.lastIndex)]
            index += 1
            return state
        }
    }

    private class FakeCommandExecutor(
        override val sessionState: DualStreamSessionState,
    ) : DualStreamCommandExecutor {
        override fun runtimeStatus(): DualStreamCommandExecutor.RuntimeStatus = DualStreamCommandExecutor.RuntimeStatus(
            sessionState = sessionState,
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.IDLE,
            failureReason = "thermal-stream-source-unavailable",
        )

        override suspend fun executeCommand(
            droneSn: String,
            action: String,
        ): DualStreamSessionManager.CommandExecutionResult = DualStreamSessionManager.CommandExecutionResult(
            status = "ignored",
        )
    }

    private class RecordingCommandExecutor(
        override val sessionState: DualStreamSessionState,
    ) : DualStreamCommandExecutor {
        val executedActions = mutableListOf<String>()

        override suspend fun executeCommand(
            droneSn: String,
            action: String,
        ): DualStreamSessionManager.CommandExecutionResult {
            executedActions += action
            return DualStreamSessionManager.CommandExecutionResult(status = "applied")
        }
    }

    private class RecordingCommandPoller : CommandPoller {
        var pollCount: Int = 0
        var lastDroneSn: String? = null

        override suspend fun pollOnce(droneSn: String) {
            pollCount += 1
            lastDroneSn = droneSn
        }
    }

    private class RecordingDualStreamApi : DualStreamApi {
        override suspend fun reportAgentFire(agentToken: String, body: okhttp3.RequestBody) =
            retrofit2.Response.success(okhttp3.ResponseBody.create(null, "{}"))
        var heartbeatCount: Int = 0
        var lastHeartbeatDroneSn: String? = null
        var lastHeartbeatBody: AgentHeartbeatRequest? = null
        var lastStatusDroneSn: String? = null
        var lastStatusBody: AgentStatusRequest? = null
        var lastCapabilityDroneSn: String? = null
        var lastCapabilityBody: CapabilityReportRequest? = null
        var lastMsdkDeviceState: MsdkDeviceStateRequest? = null
        val msdkDeviceStates: MutableList<MsdkDeviceStateRequest> = mutableListOf()

        override suspend fun heartbeat(
            agentToken: String,
            droneSn: String,
            body: AgentHeartbeatRequest,
        ) {
            heartbeatCount += 1
            lastHeartbeatDroneSn = droneSn
            lastHeartbeatBody = body
        }

        override suspend fun status(
            agentToken: String,
            droneSn: String,
            body: AgentStatusRequest,
        ) {
            lastStatusDroneSn = droneSn
            lastStatusBody = body
        }

        override suspend fun capability(
            agentToken: String,
            droneSn: String,
            body: CapabilityReportRequest,
        ) {
            lastCapabilityDroneSn = droneSn
            lastCapabilityBody = body
        }

        override suspend fun pollCommand(agentToken: String, droneSn: String): AgentApiEnvelope<AgentCommandResponse>? = null

        override suspend fun ackCommand(
            agentToken: String,
            droneSn: String,
            body: AgentCommandAckRequest,
        ) = Unit

        override suspend fun recordTaskEvent(
            taskId: String,
            body: DualStreamEventRequest,
        ) = Unit

        override suspend fun reportMsdkDeviceState(body: MsdkDeviceStateRequest) {
            lastMsdkDeviceState = body
            msdkDeviceStates.add(body)
        }

        override suspend fun pollMsdkCommand(aircraftSn: String): AgentApiEnvelope<MsdkCommandResponse>? = null

        override suspend fun ackMsdkCommand(
            aircraftSn: String,
            body: MsdkCommandAckRequest,
        ) = Unit
    }
}
