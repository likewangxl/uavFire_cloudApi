package com.yinxin.uavfir.stream

import kotlinx.coroutines.delay

class RealMsdkStreamProvider(
    private val binder: MsdkStreamBinder = defaultStreamBinder(),
    private val liveStreamController: LiveStreamController = defaultLiveStreamController(),
) : StreamProvider {
    var visibleState: BoundStreamState = BoundStreamState.IDLE
        private set

    var thermalState: BoundStreamState = BoundStreamState.IDLE
        private set

    private var playbackStatus: String = "awaiting-media-url"

    override suspend fun start(droneSn: String): StreamStartResult {
        binder.bindVisible(droneSn)
        liveStreamController.start(droneSn)
        visibleState = BoundStreamState.BOUND
        thermalState = BoundStreamState.IDLE
        playbackStatus = "visible-live-ready"
        return runCatching {
            binder.bindThermal(droneSn)
            thermalState = BoundStreamState.BOUND
            StreamStartResult(
                visibleState = visibleState,
                thermalState = thermalState,
                playbackStatus = "shared-side-by-side-preview",
            )
        }.getOrElse {
            StreamStartResult(
                visibleState = visibleState,
                thermalState = thermalState,
                thermalFailureMessage = it.message ?: it::class.simpleName ?: "unknown-thermal-start-failure",
                playbackStatus = playbackStatus,
            )
        }
    }

    override suspend fun focusVisible(droneSn: String): StreamStartResult {
        binder.focusVisible(droneSn)
        restartLiveStream(droneSn)
        visibleState = BoundStreamState.BOUND
        thermalState = BoundStreamState.IDLE
        playbackStatus = "visible-live-ready"
        return StreamStartResult(
            visibleState = visibleState,
            thermalState = thermalState,
            playbackStatus = playbackStatus,
        )
    }

    override suspend fun focusThermal(droneSn: String): StreamStartResult {
        return focusThermal(droneSn, null)
    }

    override suspend fun focusThermal(
        droneSn: String,
        thermalMeasureRegion: ThermalMeasureRegion?,
    ): StreamStartResult {
        binder.focusThermal(droneSn)
        restartLiveStream(droneSn)
        val thermalMeasurement = runCatching {
            if (thermalMeasureRegion == null) {
                binder.measureThermalCenterTemperatureC()?.let {
                    ThermalMeasurementResult(
                        temperatureC = it,
                        region = ThermalMeasureRegion.CENTER,
                    )
                }
            } else {
                binder.locateAndMeasureThermalHotspotC(thermalMeasureRegion)
            }
        }.getOrNull()
        visibleState = BoundStreamState.BOUND
        thermalState = BoundStreamState.BOUND
        playbackStatus = "shared-side-by-side-preview"
        val restoreVisibleFailure = if (thermalMeasureRegion != null) {
            runCatching {
                binder.focusVisible(droneSn)
                restartLiveStream(droneSn)
                visibleState = BoundStreamState.BOUND
                thermalState = BoundStreamState.IDLE
                playbackStatus = "visible-live-ready"
            }.exceptionOrNull()
        } else {
            null
        }
        val message = when {
            restoreVisibleFailure != null ->
                restoreVisibleFailure.message
                    ?: restoreVisibleFailure::class.simpleName
                    ?: "thermal-measured-visible-restore-failed"
            thermalMeasureRegion != null -> "thermal-measured-visible-restored"
            else -> "single-liveview-source-shared-side-by-side-preview"
        }
        return StreamStartResult(
            visibleState = visibleState,
            thermalState = thermalState,
            thermalFailureMessage = message,
            playbackStatus = playbackStatus,
            thermalCenterTemperatureC = thermalMeasurement?.temperatureC,
            thermalMeasureRegion = thermalMeasurement?.region,
        )
    }

    override suspend fun stop() {
        liveStreamController.stop()
        binder.unbindAll()
        visibleState = BoundStreamState.IDLE
        thermalState = BoundStreamState.IDLE
        playbackStatus = "awaiting-media-url"
    }

    private suspend fun restartLiveStream(droneSn: String) {
        liveStreamController.stop()
        delay(MSDK_LIVE_RESTART_DRAIN_MS)
        liveStreamController.start(droneSn)
    }

    private companion object {
        const val MSDK_LIVE_RESTART_DRAIN_MS: Long = 800L
    }
}

private class StubMsdkStreamBinder : MsdkStreamBinder {
    override suspend fun bindVisible(droneSn: String) = Unit

    override suspend fun bindThermal(droneSn: String) = Unit

    override suspend fun focusVisible(droneSn: String) = Unit

    override suspend fun focusThermal(droneSn: String) = Unit

    override suspend fun measureThermalCenterTemperatureC(): Double? = null

    override suspend fun measureThermalRegionTemperatureC(region: ThermalMeasureRegion): Double? = null

    override suspend fun unbindAll() = Unit
}

private class StubLiveStreamController : LiveStreamController {
    override suspend fun start(droneSn: String) = Unit

    override suspend fun stop() = Unit
}

private fun defaultStreamBinder(): MsdkStreamBinder {
    return if (com.yinxin.uavfir.AppContextHolder.get() != null) {
        DjiMsdkStreamBinder()
    } else {
        StubMsdkStreamBinder()
    }
}

private fun defaultLiveStreamController(): LiveStreamController {
    return if (com.yinxin.uavfir.AppContextHolder.get() != null) {
        DjiLiveStreamController()
    } else {
        StubLiveStreamController()
    }
}
