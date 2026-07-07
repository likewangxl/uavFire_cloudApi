package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.BoundStreamState
import com.yinxin.uavfir.stream.StreamProvider
import com.yinxin.uavfir.stream.StreamStartResult
import com.yinxin.uavfir.stream.ThermalMeasuredPoint
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import kotlinx.coroutines.CompletableDeferred
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
    fun frameHotspotCandidate_triggersImmediateMeasurementWithoutWaitingForPollInterval() = runTest {
        val api = RecordingDualStreamApi()
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08),
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
            monitorScope = this,
            visibleConfirmationScope = backgroundScope,
            clockMs = { 1780059017562L },
        )

        monitor.onFrameHotspotCandidate("DRONE-001").join()

        assertEquals(1, provider.measureHotspotCalls)
        assertEquals("fire-DRONE-001", api.lastTaskEventTaskId)
        assertEquals(153.0, api.lastTaskEventBody?.thermalTemperature ?: -1.0, 1e-6)
    }

    @Test
    fun frameHotspotCandidate_debouncesRepeatedCallbacksForTwoSecondsAfterProcessing() = runTest {
        val api = RecordingDualStreamApi()
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion.CENTER,
        )
        val sessionManager = DualStreamSessionManager(provider)
        var now = 100L
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
            monitorScope = this,
            visibleConfirmationScope = backgroundScope,
            clockMs = { now },
        )

        monitor.onFrameHotspotCandidate("DRONE-001").join()
        now = 1_500L
        monitor.onFrameHotspotCandidate("DRONE-001").join()
        now = 2_101L
        monitor.onFrameHotspotCandidate("DRONE-001").join()

        assertEquals(2, provider.measureHotspotCalls)
        assertEquals(2, api.taskEvents.size)
    }

    @Test
    fun frameHotspotCandidate_skipsWhenMonitoringDisabled() = runTest {
        val api = RecordingDualStreamApi()
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion.CENTER,
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
            monitorScope = this,
            visibleConfirmationScope = backgroundScope,
            clockMs = { 1780059017562L },
        )

        monitor.onFrameHotspotCandidate("DRONE-001").join()

        assertEquals(0, provider.measureHotspotCalls)
        assertNull(api.lastTaskEventBody)
    }

    @Test
    fun frameHotspotCandidate_sharesInFlightGuardWithPollOnce() = runTest {
        val api = RecordingDualStreamApi()
        val provider = BlockingHotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion.CENTER,
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
            monitorScope = this,
            visibleConfirmationScope = backgroundScope,
            clockMs = { 1780059017562L },
        )

        val triggerJob = monitor.onFrameHotspotCandidate("DRONE-001")
        provider.measureStarted.await()
        monitor.pollOnce("DRONE-001")

        assertEquals(1, provider.measureHotspotCalls)

        provider.releaseMeasure.complete(Unit)
        triggerJob.join()

        assertEquals(1, provider.measureHotspotCalls)
        assertEquals(1, api.taskEvents.size)
    }

    @Test
    fun pollOnce_usesTwoSecondDefaultProbeInterval() = runTest {
        val api = RecordingDualStreamApi()
        val provider = HotspotStreamProvider(
            temperatureC = 36.0,
            region = ThermalMeasureRegion.CENTER,
        )
        val sessionManager = DualStreamSessionManager(provider)
        var now = 100L
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
            clockMs = { now },
        )

        monitor.pollOnce("DRONE-001")
        now = 2_099L
        monitor.pollOnce("DRONE-001")
        now = 2_100L
        monitor.pollOnce("DRONE-001")

        assertEquals(2, provider.measureHotspotCalls)
    }

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
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
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
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
            snapshotUploader = uploader,
            visibleConfirmationScope = backgroundScope,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals("fire-DRONE-001-1780059017562", uploader.lastEventId)
        assertEquals("/tmp/thermal.jpg", uploader.lastSnapshotPath)
        assertEquals("http://ai/snapshots/event-1-annotated.jpg", api.lastTaskEventBody?.thermalImageUrl)
        assertEquals(1, api.taskEvents.size)
    }

    @Test
    fun monitor_reports_best_sample_temperature() = runTest {
        val api = RecordingDualStreamApi()
        val firstRegion = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08)
        val bestRegion = ThermalMeasureRegion(x = 0.50, y = 0.52, width = 0.06, height = 0.07)
        val provider = SequenceHotspotStreamProvider(
            results = listOf(
                HotspotResult(46.0, snapshotPath = "/tmp/thermal-first.jpg", region = firstRegion),
                HotspotResult(47.0, snapshotPath = null, region = firstRegion),
                HotspotResult(61.0, snapshotPath = null, region = bestRegion),
                HotspotResult(48.0, snapshotPath = null, region = firstRegion),
            ),
        )
        val uploader = RecordingThermalSnapshotUploader("http://ai/snapshots/thermal.jpg")
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            dwellConfirmer = enabledDwellConfirmer(sessionManager),
            snapshotUploader = uploader,
            visibleConfirmationScope = backgroundScope,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals(4, provider.measureHotspotCalls)
        assertEquals(61.0, api.lastTaskEventBody?.thermalTemperature ?: -1.0, 1e-6)
        assertEquals(bestRegion.toApiMapForTest(), api.lastTaskEventBody?.thermalMeasureRoi)
        assertEquals("http://ai/snapshots/thermal.jpg", api.lastTaskEventBody?.thermalImageUrl)
    }

    @Test
    fun monitor_suppresses_report_when_dwell_rejects() = runTest {
        val api = RecordingDualStreamApi()
        val provider = SequenceHotspotStreamProvider(
            results = listOf(
                HotspotResult(46.0, snapshotPath = "/tmp/thermal-first.jpg"),
                HotspotResult(41.0, snapshotPath = null),
                HotspotResult(42.0, snapshotPath = null),
                HotspotResult(43.0, snapshotPath = null),
            ),
        )
        val uploader = RecordingThermalSnapshotUploader("http://ai/snapshots/thermal.jpg")
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            dwellConfirmer = enabledDwellConfirmer(sessionManager),
            snapshotUploader = uploader,
            visibleConfirmationScope = backgroundScope,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals(4, provider.measureHotspotCalls)
        assertEquals(0, api.taskEvents.size)
        assertNull(uploader.lastEventId)
    }

    @Test
    fun pollOnce_reportsFireEventWhenThermalSnapshotIsMissing() = runTest {
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
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
            visibleSnapshotConfirmer = visibleConfirmer,
            visibleConfirmationScope = backgroundScope,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals("fire-DRONE-001", api.lastTaskEventTaskId)
        assertEquals(153.0, api.lastTaskEventBody?.thermalTemperature ?: -1.0, 1e-6)
        assertNull(api.lastTaskEventBody?.thermalImageUrl)
        assertEquals(0, provider.captureVisibleSnapshotCalls)
        assertNull(visibleConfirmer.lastEventId)
    }

    @Test
    fun pollOnce_rereportsThermalEventBeforeVisibleConfirmationWhenAsyncRetryGetsSnapshot() = runTest {
        val api = RecordingDualStreamApi()
        val uploader = SequenceThermalSnapshotUploader(
            listOf(null, null, null, "http://ai/snapshots/thermal-retry.jpg"),
        )
        val firstRegion = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08)
        val retryRegion = ThermalMeasureRegion(x = 0.50, y = 0.52, width = 0.06, height = 0.07)
        val retryMeasurementRegion = ThermalMeasureRegion(x = 0.51, y = 0.53, width = 0.04, height = 0.05)
        val provider = SequenceHotspotStreamProvider(
            results = listOf(
                HotspotResult(
                    temperatureC = 153.0,
                    snapshotPath = "/tmp/thermal-first.jpg",
                    region = firstRegion,
                ),
                HotspotResult(
                    temperatureC = 171.0,
                    snapshotPath = "/tmp/thermal-retry.jpg",
                    region = retryRegion,
                    measurements = listOf(ThermalMeasuredPoint(172.0, retryMeasurementRegion)),
                ),
            ),
            visibleSnapshotPath = "/tmp/visible.jpg",
        )
        val visibleConfirmer = RecordingVisibleSnapshotConfirmer("http://ai/snapshots/visible.jpg")
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            client = AgentBackendClient(api),
            sessionManager = sessionManager,
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
            snapshotUploader = uploader,
            visibleSnapshotConfirmer = visibleConfirmer,
            visibleConfirmationScope = this,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals("fire-DRONE-001-1780059017562", uploader.lastEventId)
        assertEquals(1, provider.measureHotspotCalls)
        assertEquals("thermal", api.taskEvents.first().analysisChannel)
        assertNull(api.taskEvents.first().thermalImageUrl)

        advanceUntilIdle()

        assertEquals(2, provider.measureHotspotCalls)
        assertEquals(1, provider.captureVisibleSnapshotCalls)
        assertEquals(listOf("thermal", "thermal", "visible"), api.taskEvents.map { it.analysisChannel })
        assertEquals(153.0, api.taskEvents[0].thermalTemperature ?: -1.0, 1e-6)
        assertEquals(firstRegion.toApiMapForTest(), api.taskEvents[0].thermalMeasureRoi)
        assertNull(api.taskEvents[0].thermalImageUrl)
        assertEquals(171.0, api.taskEvents[1].thermalTemperature ?: -1.0, 1e-6)
        assertEquals(retryRegion.toApiMapForTest(), api.taskEvents[1].thermalMeasureRoi)
        assertEquals(
            listOf(ThermalMeasurementPayload(172.0, retryMeasurementRegion.toApiMapForTest())),
            api.taskEvents[1].thermalMeasurements,
        )
        assertEquals("http://ai/snapshots/thermal-retry.jpg", api.taskEvents[1].thermalImageUrl)
        assertEquals("fire-DRONE-001-1780059017562", visibleConfirmer.lastEventId)
        assertEquals("http://ai/snapshots/thermal-retry.jpg", visibleConfirmer.lastThermalImageUrl)
    }

    @Test
    fun pollOnce_usesOnlyOneRemeasureAttemptForMissingThermalSnapshot() = runTest {
        val api = RecordingDualStreamApi()
        val uploader = RecordingThermalSnapshotUploader(null)
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
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
            snapshotUploader = uploader,
            visibleConfirmationScope = this,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals(1, provider.measureHotspotCalls)
        assertEquals(153.0, api.taskEvents.first().thermalTemperature ?: -1.0, 1e-6)
        assertNull(api.taskEvents.first().thermalImageUrl)

        advanceUntilIdle()

        assertEquals(2, provider.measureHotspotCalls)
        assertNull(uploader.lastSnapshotPath)
        assertEquals(1, api.taskEvents.size)
        assertEquals(0, provider.captureVisibleSnapshotCalls)
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
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
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
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
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
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
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
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
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
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
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
            dwellConfirmer = disabledDwellConfirmer(sessionManager),
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

    private fun disabledDwellConfirmer(
        sessionManager: DualStreamSessionManager,
    ): ThermalDwellConfirmer = ThermalDwellConfirmer(
        sessionManager = sessionManager,
        missionHold = NoopMissionHoldControl,
        enabled = false,
    )

    private fun enabledDwellConfirmer(
        sessionManager: DualStreamSessionManager,
    ): ThermalDwellConfirmer = ThermalDwellConfirmer(
        sessionManager = sessionManager,
        missionHold = NoopMissionHoldControl,
        stabilizeMs = 0L,
        sampleIntervalMs = 0L,
        clockMs = { 1780059017562L },
    )

    private object NoopMissionHoldControl : MissionHoldControl {
        override suspend fun holdForConfirmation(): Boolean = true

        override suspend fun resumeAfterConfirmation() = Unit
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
        val measurements: List<ThermalMeasuredPoint> = emptyList(),
    )

    private class SequenceHotspotStreamProvider(
        private val results: List<HotspotResult>,
        private val visibleSnapshotPath: String? = null,
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
            val result = results.getOrElse(measureHotspotCalls) { results.last() }
            measureHotspotCalls += 1
            return StreamStartResult(
                visibleState = BoundStreamState.BOUND,
                thermalState = BoundStreamState.BOUND,
                playbackStatus = "shared-side-by-side-preview",
                thermalCenterTemperatureC = result.temperatureC,
                thermalMeasureRegion = result.region,
                thermalMeasurements = result.measurements,
                thermalSnapshotPath = result.snapshotPath,
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

    private class BlockingHotspotStreamProvider(
        private val temperatureC: Double,
        private val region: ThermalMeasureRegion,
    ) : StreamProvider {
        var measureHotspotCalls = 0
        val measureStarted = CompletableDeferred<Unit>()
        val releaseMeasure = CompletableDeferred<Unit>()

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
            measureStarted.complete(Unit)
            releaseMeasure.await()
            return StreamStartResult(
                visibleState = BoundStreamState.BOUND,
                thermalState = BoundStreamState.BOUND,
                playbackStatus = "shared-side-by-side-preview",
                thermalCenterTemperatureC = temperatureC,
                thermalMeasureRegion = region,
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

private fun ThermalMeasureRegion.toApiMapForTest(): Map<String, Double> = mapOf(
    "x" to x,
    "y" to y,
    "width" to width,
    "height" to height,
)
