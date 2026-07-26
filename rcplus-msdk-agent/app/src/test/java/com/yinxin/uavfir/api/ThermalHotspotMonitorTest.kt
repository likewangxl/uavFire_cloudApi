package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.BoundStreamState
import com.yinxin.uavfir.stream.StreamProvider
import com.yinxin.uavfir.stream.StreamStartResult
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 探针已降权为 HUD 供数：只测画面最热点刷新中心温度，
 * 不上报热点事件、不驻留复测、不自主拍可见光确认照——
 * 火情触发与确认串行化到后端（红外 YOLO → 实测温度 → 证据照）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThermalHotspotMonitorTest {
    @Test
    fun frameHotspotCandidate_triggersImmediateProbeAndNeverCapturesVisibleSnapshot() = runTest {
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion(x = 0.42, y = 0.46, width = 0.08, height = 0.08),
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            sessionManager = sessionManager,
            monitorScope = this,
            clockMs = { 1780059017562L },
        )

        monitor.onFrameHotspotCandidate("DRONE-001").join()

        assertEquals(1, provider.measureHotspotCalls)
        // 即便测到远超旧 45°C 触发线的高温，也不再自主切可见光拍照
        assertEquals(0, provider.captureVisibleSnapshotCalls)
    }

    @Test
    fun frameHotspotCandidate_debouncesRepeatedCallbacksForTwoSecondsAfterProcessing() = runTest {
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion.CENTER,
        )
        val sessionManager = DualStreamSessionManager(provider)
        var now = 100L
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            sessionManager = sessionManager,
            monitorScope = this,
            clockMs = { now },
        )

        monitor.onFrameHotspotCandidate("DRONE-001").join()
        now = 1_500L
        monitor.onFrameHotspotCandidate("DRONE-001").join()
        now = 2_101L
        monitor.onFrameHotspotCandidate("DRONE-001").join()

        assertEquals(2, provider.measureHotspotCalls)
    }

    @Test
    fun frameHotspotCandidate_skipsWhenMonitoringDisabled() = runTest {
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion.CENTER,
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        val monitor = ThermalHotspotMonitor(
            sessionManager = sessionManager,
            monitorScope = this,
            clockMs = { 1780059017562L },
        )

        monitor.onFrameHotspotCandidate("DRONE-001").join()

        assertEquals(0, provider.measureHotspotCalls)
    }

    @Test
    fun frameHotspotCandidate_sharesInFlightGuardWithPollOnce() = runTest {
        val provider = BlockingHotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion.CENTER,
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            sessionManager = sessionManager,
            monitorScope = this,
            clockMs = { 1780059017562L },
        )

        val triggerJob = monitor.onFrameHotspotCandidate("DRONE-001")
        provider.measureStarted.await()
        monitor.pollOnce("DRONE-001")

        assertEquals(1, provider.measureHotspotCalls)

        provider.releaseMeasure.complete(Unit)
        triggerJob.join()

        assertEquals(1, provider.measureHotspotCalls)
    }

    @Test
    fun pollOnce_usesTwoSecondDefaultProbeInterval() = runTest {
        val provider = HotspotStreamProvider(
            temperatureC = 36.0,
            region = ThermalMeasureRegion.CENTER,
        )
        val sessionManager = DualStreamSessionManager(provider)
        var now = 100L
        sessionManager.start("DRONE-001")
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            sessionManager = sessionManager,
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
    fun pollOnce_skipsWhenSessionNotRunning() = runTest {
        val provider = HotspotStreamProvider(
            temperatureC = 153.0,
            region = ThermalMeasureRegion.CENTER,
        )
        val sessionManager = DualStreamSessionManager(provider)
        sessionManager.thermalMonitoringEnabled = true
        val monitor = ThermalHotspotMonitor(
            sessionManager = sessionManager,
            clockMs = { 1780059017562L },
        )

        monitor.pollOnce("DRONE-001")

        assertEquals(0, provider.measureHotspotCalls)
    }

    private class HotspotStreamProvider(
        private val temperatureC: Double,
        private val region: ThermalMeasureRegion,
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
            )
        }

        override suspend fun captureVisibleSnapshot(droneSn: String): StreamStartResult {
            captureVisibleSnapshotCalls += 1
            return StreamStartResult(
                visibleState = BoundStreamState.BOUND,
                thermalState = BoundStreamState.IDLE,
                playbackStatus = "visible-live-ready",
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
}
