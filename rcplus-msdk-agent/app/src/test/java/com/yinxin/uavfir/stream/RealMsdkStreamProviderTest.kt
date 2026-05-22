package com.yinxin.uavfir.stream

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealMsdkStreamProviderTest {
    @Test
    fun start_bindsVisibleAndThermalStreams() = runTest {
        val binder = RecordingMsdkStreamBinder()
        val liveStreamController = RecordingLiveStreamController()
        val provider = RealMsdkStreamProvider(
            binder = binder,
            liveStreamController = liveStreamController,
        )

        val result = provider.start("DRONE-001")

        assertTrue(binder.visibleBound)
        assertEquals(listOf("DRONE-001"), liveStreamController.startedDroneSns)
        assertTrue(binder.thermalBound)
        assertEquals(BoundStreamState.BOUND, provider.visibleState)
        assertEquals(BoundStreamState.BOUND, provider.thermalState)
        assertEquals(BoundStreamState.BOUND, result.visibleState)
        assertEquals(BoundStreamState.BOUND, result.thermalState)
    }

    @Test
    fun start_fallsBackToVisibleOnlyWhenThermalBindingFails() = runTest {
        val binder = RecordingMsdkStreamBinder(
            bindThermalFailure = IllegalStateException(
                "m4t-single-gimbal-only-exposes-single-component-index",
            ),
        )
        val liveStreamController = RecordingLiveStreamController()
        val provider = RealMsdkStreamProvider(
            binder = binder,
            liveStreamController = liveStreamController,
        )

        val result = provider.start("DRONE-001")

        assertTrue(binder.visibleBound)
        assertEquals(listOf("DRONE-001"), liveStreamController.startedDroneSns)
        assertEquals(BoundStreamState.BOUND, provider.visibleState)
        assertEquals(BoundStreamState.IDLE, provider.thermalState)
        assertEquals(BoundStreamState.BOUND, result.visibleState)
        assertEquals(BoundStreamState.IDLE, result.thermalState)
        assertEquals(
            "m4t-single-gimbal-only-exposes-single-component-index",
            result.thermalFailureMessage,
        )
        assertEquals("visible-live-ready", result.playbackStatus)
    }

    @Test
    fun stop_unbindsAllStreamsAndResetsStates() = runTest {
        val binder = RecordingMsdkStreamBinder()
        val liveStreamController = RecordingLiveStreamController()
        val provider = RealMsdkStreamProvider(
            binder = binder,
            liveStreamController = liveStreamController,
        )
        provider.start("DRONE-001")

        provider.stop()

        assertTrue(binder.unbound)
        assertTrue(liveStreamController.stopped)
        assertEquals(BoundStreamState.IDLE, provider.visibleState)
        assertEquals(BoundStreamState.IDLE, provider.thermalState)
    }

    @Test
    fun focusThermal_marksSharedPreviewAsRunningForBothChannels() = runTest {
        val binder = RecordingMsdkStreamBinder()
        val liveStreamController = RecordingLiveStreamController()
        val provider = RealMsdkStreamProvider(
            binder = binder,
            liveStreamController = liveStreamController,
        )

        val result = provider.focusThermal("DRONE-001")

        assertTrue(binder.thermalFocused)
        assertEquals(BoundStreamState.BOUND, result.visibleState)
        assertEquals(BoundStreamState.BOUND, result.thermalState)
        assertEquals("shared-side-by-side-preview", result.playbackStatus)
    }

    @Test
    fun focusThermal_restartsRtmpPushAfterSwitchingThermalSource() = runTest {
        val invocationOrder = mutableListOf<String>()
        val binder = object : MsdkStreamBinder {
            override suspend fun bindVisible(droneSn: String) = Unit
            override suspend fun bindThermal(droneSn: String) = Unit
            override suspend fun focusVisible(droneSn: String) = Unit
            override suspend fun focusThermal(droneSn: String) { invocationOrder += "focusThermal" }
            override suspend fun unbindAll() = Unit
        }
        val liveStreamController = object : LiveStreamController {
            override suspend fun start(droneSn: String) { invocationOrder += "rtmp.start" }
            override suspend fun stop() { invocationOrder += "rtmp.stop" }
        }
        val provider = RealMsdkStreamProvider(
            binder = binder,
            liveStreamController = liveStreamController,
        )

        provider.focusThermal("DRONE-001")

        assertEquals(listOf("focusThermal", "rtmp.stop", "rtmp.start"), invocationOrder)
    }

    @Test
    fun focusVisible_restartsRtmpPushAfterSwitchingVisibleSource() = runTest {
        val invocationOrder = mutableListOf<String>()
        val binder = object : MsdkStreamBinder {
            override suspend fun bindVisible(droneSn: String) = Unit
            override suspend fun bindThermal(droneSn: String) = Unit
            override suspend fun focusVisible(droneSn: String) { invocationOrder += "focusVisible" }
            override suspend fun focusThermal(droneSn: String) = Unit
            override suspend fun unbindAll() = Unit
        }
        val liveStreamController = object : LiveStreamController {
            override suspend fun start(droneSn: String) { invocationOrder += "rtmp.start" }
            override suspend fun stop() { invocationOrder += "rtmp.stop" }
        }
        val provider = RealMsdkStreamProvider(
            binder = binder,
            liveStreamController = liveStreamController,
        )

        provider.focusVisible("DRONE-001")

        assertEquals(listOf("focusVisible", "rtmp.stop", "rtmp.start"), invocationOrder)
    }

    @Test
    fun start_invokesBindVisibleBeforeRtmpPush() = runTest {
        val invocationOrder = mutableListOf<String>()
        val binder = object : MsdkStreamBinder {
            override suspend fun bindVisible(droneSn: String) { invocationOrder += "bindVisible" }
            override suspend fun bindThermal(droneSn: String) { invocationOrder += "bindThermal" }
            override suspend fun focusVisible(droneSn: String) = Unit
            override suspend fun focusThermal(droneSn: String) = Unit
            override suspend fun unbindAll() = Unit
        }
        val liveStreamController = object : LiveStreamController {
            override suspend fun start(droneSn: String) { invocationOrder += "rtmp.start" }
            override suspend fun stop() = Unit
        }
        val provider = RealMsdkStreamProvider(
            binder = binder,
            liveStreamController = liveStreamController,
        )

        provider.start("DRONE-001")

        val bindVisibleIdx = invocationOrder.indexOf("bindVisible")
        val rtmpStartIdx = invocationOrder.indexOf("rtmp.start")
        assertTrue(
            "bindVisible must happen before rtmp.start but got order $invocationOrder",
            bindVisibleIdx in 0 until rtmpStartIdx,
        )
    }

    @Test
    fun start_leavesVisibleIdleWhenRtmpPushFails() = runTest {
        val binder = RecordingMsdkStreamBinder()
        val liveStreamController = RecordingLiveStreamController(
            startFailure = IllegalStateException("rtmp-publish-refused-by-zlm"),
        )
        val provider = RealMsdkStreamProvider(
            binder = binder,
            liveStreamController = liveStreamController,
        )

        val thrown = runCatching { provider.start("DRONE-001") }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("rtmp-publish-refused-by-zlm", thrown?.message)
        assertTrue(binder.visibleBound)
        assertEquals(BoundStreamState.IDLE, provider.visibleState)
        assertEquals(BoundStreamState.IDLE, provider.thermalState)
    }

    @Test
    fun start_doesNotPushRtmpWhenBindVisibleFails() = runTest {
        val binder = RecordingMsdkStreamBinder(
            bindVisibleFailure = IllegalStateException("visible-stream-source-unavailable"),
        )
        val liveStreamController = RecordingLiveStreamController()
        val provider = RealMsdkStreamProvider(
            binder = binder,
            liveStreamController = liveStreamController,
        )

        val thrown = runCatching { provider.start("DRONE-001") }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertTrue(liveStreamController.startedDroneSns.isEmpty())
        assertEquals(BoundStreamState.IDLE, provider.visibleState)
        assertEquals(BoundStreamState.IDLE, provider.thermalState)
    }

    private class RecordingMsdkStreamBinder(
        private val bindThermalFailure: Throwable? = null,
        private val bindVisibleFailure: Throwable? = null,
    ) : MsdkStreamBinder {
        var visibleBound = false
        var thermalBound = false
        var thermalFocused = false
        var unbound = false

        override suspend fun bindVisible(droneSn: String) {
            bindVisibleFailure?.let { throw it }
            visibleBound = true
        }

        override suspend fun bindThermal(droneSn: String) {
            bindThermalFailure?.let { throw it }
            thermalBound = true
        }

        override suspend fun focusVisible(droneSn: String) = Unit

        override suspend fun focusThermal(droneSn: String) {
            thermalFocused = true
        }

        override suspend fun unbindAll() {
            unbound = true
        }
    }

    private class RecordingLiveStreamController(
        private val startFailure: Throwable? = null,
    ) : LiveStreamController {
        val startedDroneSns = mutableListOf<String>()
        var stopped = false

        override suspend fun start(droneSn: String) {
            startFailure?.let { throw it }
            startedDroneSns += droneSn
        }

        override suspend fun stop() {
            stopped = true
        }
    }
}
