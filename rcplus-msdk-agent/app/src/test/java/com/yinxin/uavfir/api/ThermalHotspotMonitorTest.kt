package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.BoundStreamState
import com.yinxin.uavfir.stream.StreamProvider
import com.yinxin.uavfir.stream.StreamStartResult
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThermalHotspotMonitorTest {
    @Test
    fun pollOnce_reportsHotspotEventWhenMeasuredTemperatureExceedsThreshold() = runTest {
        val api = RecordingDualStreamApi()
        val uploader = RecordingThermalSnapshotUploader("http://ai/snapshots/event-1-annotated.jpg")
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08),
            snapshotPath = "/tmp/thermal.jpg",
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            snapshotUploader = uploader,
            visibleConfirmationScope = backgroundScope,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals("fire-DRONE-001", api.lastTaskEventTaskId)
        assertEquals("DRONE-001", api.lastTaskEventBody?.droneSn)
        assertEquals(153.0, api.lastTaskEventBody?.thermalTemperature ?: -1.0, 1e-6)
        assertEquals("HIGH", api.lastTaskEventBody?.riskLevel)
        assertEquals(1, provider.measureHotspotCalls)
    }

    @Test
    fun pollOnce_uploadsThermalSnapshotAndBindsReturnedUrl() = runTest {
        val api = RecordingDualStreamApi()
        val uploader = RecordingThermalSnapshotUploader("http://ai/snapshots/event-1-annotated.jpg")
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08),
            snapshotPath = "/tmp/thermal.jpg",
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            snapshotUploader = uploader,
            visibleConfirmationScope = backgroundScope,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals("fire-DRONE-001-1780059017562", uploader.lastEventId)
        assertEquals("/tmp/thermal.jpg", uploader.lastSnapshotPath)
        assertEquals("http://ai/snapshots/event-1-annotated.jpg", api.lastTaskEventBody?.thermalImageUrl)
    }

    @Test
    fun pollOnce_skipsFireEventWhenThermalSnapshotIsMissing() = runTest {
        val api = RecordingDualStreamApi()
        val visibleConfirmer = RecordingVisibleSnapshotConfirmer("http://ai/snapshots/visible.jpg")
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08),
            visibleSnapshotPath = "/tmp/visible.jpg",
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            visibleSnapshotConfirmer = visibleConfirmer,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertNull(api.lastTaskEventBody)
        assertEquals(0, provider.captureVisibleSnapshotCalls)
        assertNull(visibleConfirmer.lastEventId)
    }

    @Test
    fun pollOnce_remeasuresAndReportsWhenInitialThermalSnapshotUploadFails() = runTest {
        val api = RecordingDualStreamApi()
        val uploader = SequenceThermalSnapshotUploader(
            listOf(null, null, "http://ai/snapshots/thermal-retry.jpg"),
        )
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08),
            snapshotPath = "/tmp/thermal.jpg",
            visibleSnapshotPath = "/tmp/visible.jpg",
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            snapshotUploader = uploader,
            visibleConfirmationScope = backgroundScope,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals("fire-DRONE-001-1780059017562", uploader.lastEventId)
        assertEquals("http://ai/snapshots/thermal-retry.jpg", api.lastTaskEventBody?.thermalImageUrl)
        assertEquals(2, provider.measureHotspotCalls)
        runCurrent()
        assertEquals(1, provider.captureVisibleSnapshotCalls)
    }

    @Test
    fun pollOnce_waitsForFreshThermalSnapshotBeforeSkippingHotspotEvent() = runTest {
        val api = RecordingDualStreamApi()
        val uploader = RecordingThermalSnapshotUploader("http://ai/snapshots/thermal-late.jpg")
        val provider = SequenceHotspotStreamProvider(
            results = listOf(
                HotspotResult(temperatureC = 153.0, snapshotPath = null),
                HotspotResult(temperatureC = 151.0, snapshotPath = null),
                HotspotResult(temperatureC = 149.0, snapshotPath = "/tmp/thermal-late.jpg"),
            ),
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            snapshotUploader = uploader,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals(3, provider.measureHotspotCalls)
        assertEquals("/tmp/thermal-late.jpg", uploader.lastSnapshotPath)
        assertEquals("http://ai/snapshots/thermal-late.jpg", api.lastTaskEventBody?.thermalImageUrl)
    }

    @Test
    fun pollOnce_reportsThermalEventThenCapturesVisibleSnapshotForAiConfirmation() = runTest {
        val api = RecordingDualStreamApi()
        val thermalUploader = RecordingThermalSnapshotUploader("http://ai/snapshots/thermal.jpg")
        val visibleConfirmer = RecordingVisibleSnapshotConfirmer("http://ai/snapshots/visible.jpg")
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08),
            snapshotPath = "/tmp/thermal.jpg",
            visibleSnapshotPath = "/tmp/visible.jpg",
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            snapshotUploader = thermalUploader,
            visibleSnapshotConfirmer = visibleConfirmer,
            visibleConfirmationScope = this,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")
        runCurrent()

        assertEquals("fire-DRONE-001", api.lastTaskEventTaskId)
        assertEquals("http://ai/snapshots/thermal.jpg", api.lastTaskEventBody?.thermalImageUrl)
        assertEquals(1, provider.captureVisibleSnapshotCalls)
        assertEquals("fire-DRONE-001", visibleConfirmer.lastTaskId)
        assertEquals("fire-DRONE-001-1780059017562", visibleConfirmer.lastEventId)
        assertEquals("DRONE-001", visibleConfirmer.lastDroneSn)
        assertEquals(1780059017562L, visibleConfirmer.lastSourceTs)
        assertEquals("/tmp/visible.jpg", visibleConfirmer.lastSnapshotPath)
        assertEquals("fire-DRONE-001-1780059017562", visibleConfirmer.lastThermalSourceEventId)
        assertEquals("http://ai/snapshots/thermal.jpg", visibleConfirmer.lastThermalImageUrl)
    }

    @Test
    fun pollOnce_retriesVisibleSnapshotConfirmationUntilFreshVisibleImageIsAccepted() = runTest {
        val api = RecordingDualStreamApi()
        val thermalUploader = RecordingThermalSnapshotUploader("http://ai/snapshots/thermal.jpg")
        val visibleConfirmer = SequenceVisibleSnapshotConfirmer(
            listOf(null, "http://ai/snapshots/fresh-visible.jpg"),
        )
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08),
            snapshotPath = "/tmp/thermal.jpg",
            visibleSnapshotPaths = listOf("/tmp/stale-visible.jpg", "/tmp/fresh-visible.jpg"),
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            snapshotUploader = thermalUploader,
            visibleSnapshotConfirmer = visibleConfirmer,
            visibleConfirmationScope = this,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")
        runCurrent()
        advanceUntilIdle()

        assertEquals(2, provider.captureVisibleSnapshotCalls)
        assertEquals(
            listOf("/tmp/stale-visible.jpg", "/tmp/fresh-visible.jpg"),
            visibleConfirmer.snapshotPaths,
        )
        assertEquals("thermal", api.taskEvents.first().analysisChannel)
        assertEquals(1, api.taskEvents.count { it.analysisChannel == "visible" })
        assertEquals("VISIBLE_PENDING", api.taskEvents.last().reviewStatus)
    }

    @Test
    fun pollOnce_recordsVisibleCaptureFailedAfterRetriesAreExhausted() = runTest {
        val api = RecordingDualStreamApi()
        val thermalUploader = RecordingThermalSnapshotUploader("http://ai/snapshots/thermal.jpg")
        val visibleConfirmer = RecordingVisibleSnapshotConfirmer("http://ai/snapshots/visible.jpg")
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08),
            snapshotPath = "/tmp/thermal.jpg",
            visibleSnapshotPaths = listOf(null, null, null),
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            snapshotUploader = thermalUploader,
            visibleSnapshotConfirmer = visibleConfirmer,
            visibleConfirmationScope = this,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")
        runCurrent()
        advanceUntilIdle()

        assertEquals(3, provider.captureVisibleSnapshotCalls)
        assertNull(visibleConfirmer.lastEventId)
        assertEquals("VISIBLE_CAPTURE_FAILED", api.lastTaskEventBody?.reviewStatus)
        assertEquals("visible", api.lastTaskEventBody?.analysisChannel)
        assertEquals("fire-DRONE-001-1780059017562", api.lastTaskEventBody?.thermalSourceEventId)
        assertEquals("http://ai/snapshots/thermal.jpg", api.lastTaskEventBody?.thermalImageUrl)
    }

    @Test
    fun pollOnce_recordsThermalEventBeforeAsyncVisibleConfirmationRuns() = runTest {
        val api = RecordingDualStreamApi()
        val thermalUploader = RecordingThermalSnapshotUploader("http://ai/snapshots/thermal.jpg")
        val visibleConfirmer = RecordingVisibleSnapshotConfirmer("http://ai/snapshots/visible.jpg")
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08),
            snapshotPath = "/tmp/thermal.jpg",
            visibleSnapshotPath = "/tmp/visible.jpg",
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            snapshotUploader = thermalUploader,
            visibleSnapshotConfirmer = visibleConfirmer,
            visibleConfirmationScope = backgroundScope,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals("fire-DRONE-001", api.lastTaskEventTaskId)
        assertEquals("http://ai/snapshots/thermal.jpg", api.lastTaskEventBody?.thermalImageUrl)
        assertEquals(0, provider.captureVisibleSnapshotCalls)
        assertNull(visibleConfirmer.lastEventId)

        runCurrent()

        assertEquals(1, provider.captureVisibleSnapshotCalls)
        assertEquals("fire-DRONE-001-1780059017562", visibleConfirmer.lastEventId)
    }

    @Test
    fun pollOnce_doesNotReportBelowThresholdHotspot() = runTest {
        val api = RecordingDualStreamApi()
        val sessionManager = DualStreamSessionManager(
            HotspotStreamProvider(
                temperatureC = 36.0,
                region = ThermalMeasureRegion.CENTER,
            ),
        )
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertNull(api.lastTaskEventBody)
    }

    @Test
    fun pollOnce_skipsThermalProbeWhenMonitoringDisabled() = runTest {
        val api = RecordingDualStreamApi()
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08),
            snapshotPath = "/tmp/thermal.jpg",
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        // 未启用火情监测：不应探测、不应切红外、不应上报。
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            visibleConfirmationScope = backgroundScope,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals(0, provider.measureHotspotCalls)
        assertNull(api.lastTaskEventBody)
    }

    @Test
    fun executeCommand_togglesThermalMonitoringEnabled() = runTest {
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion.CENTER,
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")

        assertEquals(false, sessionManager.thermalMonitoringEnabled)
        sessionManager.executeCommand("DRONE-001", "thermal-monitor-on")
        assertEquals(true, sessionManager.thermalMonitoringEnabled)
        sessionManager.executeCommand("DRONE-001", "thermal-monitor-off")
        assertEquals(false, sessionManager.thermalMonitoringEnabled)
    }

    private class HotspotStreamProvider(
        private val temperatureC: Double,
        private val region: ThermalMeasureRegion,
        private val snapshotPath: String? = null,
        private val visibleSnapshotPath: String? = null,
        private val visibleSnapshotPaths: List<String?> = listOf(visibleSnapshotPath),
    ) : StreamProvider {
        var measureHotspotCalls = 0
        var captureVisibleSnapshotCalls = 0

        override suspend fun start(droneSn: String): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.BOUND,
            playbackStatus = "shared-side-by-side-preview",
        )

        override suspend fun focusVisible(droneSn: String): StreamStartResult = start(droneSn)

        override suspend fun focusThermal(droneSn: String): StreamStartResult = start(droneSn)

        override suspend fun measureThermalHotspot(
            droneSn: String,
            seedRegion: ThermalMeasureRegion?,
        ): StreamStartResult {
            measureHotspotCalls += 1
            return StreamStartResult(
                visibleState = BoundStreamState.BOUND,
                thermalState = BoundStreamState.BOUND,
                playbackStatus = "shared-side-by-side-preview",
                thermalCenterTemperatureC = temperatureC,
                thermalMeasureRegion = region,
                thermalSnapshotPath = snapshotPath,
            )
        }

        override suspend fun captureVisibleSnapshot(droneSn: String): StreamStartResult {
            val snapshotPath = visibleSnapshotPaths.getOrElse(captureVisibleSnapshotCalls) {
                visibleSnapshotPaths.lastOrNull()
            }
            captureVisibleSnapshotCalls += 1
            return StreamStartResult(
                visibleState = BoundStreamState.BOUND,
                thermalState = BoundStreamState.IDLE,
                playbackStatus = "visible-live-ready",
                visibleSnapshotPath = snapshotPath,
            )
        }

        override suspend fun stop() = Unit
    }

    private data class HotspotResult(
        val temperatureC: Double,
        val snapshotPath: String?,
        val region: ThermalMeasureRegion = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08),
    )

    private class SequenceHotspotStreamProvider(
        private val results: List<HotspotResult>,
    ) : StreamProvider {
        var measureHotspotCalls = 0

        override suspend fun start(droneSn: String): StreamStartResult = StreamStartResult(
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.BOUND,
            playbackStatus = "shared-side-by-side-preview",
        )

        override suspend fun focusVisible(droneSn: String): StreamStartResult = start(droneSn)

        override suspend fun focusThermal(droneSn: String): StreamStartResult = start(droneSn)

        override suspend fun measureThermalHotspot(
            droneSn: String,
            seedRegion: ThermalMeasureRegion?,
        ): StreamStartResult {
            val result = results.getOrElse(measureHotspotCalls) { results.last() }
            measureHotspotCalls += 1
            return StreamStartResult(
                visibleState = BoundStreamState.BOUND,
                thermalState = BoundStreamState.BOUND,
                playbackStatus = "shared-side-by-side-preview",
                thermalCenterTemperatureC = result.temperatureC,
                thermalMeasureRegion = result.region,
                thermalSnapshotPath = result.snapshotPath,
            )
        }

        override suspend fun stop() = Unit
    }

    private class RecordingThermalSnapshotUploader(
        private val url: String?,
    ) : ThermalSnapshotUploader {
        var lastEventId: String? = null
        var lastSnapshotPath: String? = null

        override suspend fun upload(eventId: String, snapshotPath: String): String? {
            lastEventId = eventId
            lastSnapshotPath = snapshotPath
            return url
        }
    }

    private class SequenceThermalSnapshotUploader(
        private val urls: List<String?>,
    ) : ThermalSnapshotUploader {
        var lastEventId: String? = null
        private var index = 0

        override suspend fun upload(eventId: String, snapshotPath: String): String? {
            lastEventId = eventId
            val value = urls.getOrNull(index)
            index += 1
            return value
        }
    }

    private class RecordingVisibleSnapshotConfirmer(
        private val url: String?,
    ) : VisibleSnapshotConfirmer {
        var lastTaskId: String? = null
        var lastEventId: String? = null
        var lastDroneSn: String? = null
        var lastSourceTs: Long? = null
        var lastSnapshotPath: String? = null
        var lastThermalSourceEventId: String? = null
        var lastThermalImageUrl: String? = null

        override suspend fun confirm(
            taskId: String,
            eventId: String,
            droneSn: String,
            sourceTs: Long,
            snapshotPath: String,
            thermalSourceEventId: String?,
            thermalImageUrl: String?,
        ): String? {
            lastTaskId = taskId
            lastEventId = eventId
            lastDroneSn = droneSn
            lastSourceTs = sourceTs
            lastSnapshotPath = snapshotPath
            lastThermalSourceEventId = thermalSourceEventId
            lastThermalImageUrl = thermalImageUrl
            return url
        }
    }

    private class SequenceVisibleSnapshotConfirmer(
        private val urls: List<String?>,
    ) : VisibleSnapshotConfirmer {
        val snapshotPaths = mutableListOf<String>()
        private var index = 0

        override suspend fun confirm(
            taskId: String,
            eventId: String,
            droneSn: String,
            sourceTs: Long,
            snapshotPath: String,
            thermalSourceEventId: String?,
            thermalImageUrl: String?,
        ): String? {
            snapshotPaths += snapshotPath
            val url = urls.getOrElse(index) { urls.lastOrNull() }
            index += 1
            return url
        }
    }

    private class RecordingDualStreamApi : DualStreamApi {
        var lastTaskEventTaskId: String? = null
        var lastTaskEventBody: DualStreamEventRequest? = null
        val taskEvents = mutableListOf<DualStreamEventRequest>()

        override suspend fun heartbeat(
            droneSn: String,
            body: AgentHeartbeatRequest,
        ) = Unit

        override suspend fun status(
            droneSn: String,
            body: AgentStatusRequest,
        ) = Unit

        override suspend fun capability(
            droneSn: String,
            body: CapabilityReportRequest,
        ) = Unit

        override suspend fun pollCommand(droneSn: String): AgentApiEnvelope<AgentCommandResponse>? = null

        override suspend fun ackCommand(
            droneSn: String,
            body: AgentCommandAckRequest,
        ) = Unit

        override suspend fun recordTaskEvent(
            taskId: String,
            body: DualStreamEventRequest,
        ) {
            lastTaskEventTaskId = taskId
            lastTaskEventBody = body
            taskEvents += body
        }

        override suspend fun reportMsdkDeviceState(body: MsdkDeviceStateRequest) = Unit

        override suspend fun pollMsdkCommand(aircraftSn: String): AgentApiEnvelope<MsdkCommandResponse>? = null

        override suspend fun ackMsdkCommand(
            aircraftSn: String,
            body: MsdkCommandAckRequest,
        ) = Unit
    }
}
