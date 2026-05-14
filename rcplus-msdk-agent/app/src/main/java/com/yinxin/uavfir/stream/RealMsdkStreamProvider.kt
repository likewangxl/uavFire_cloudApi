package com.yinxin.uavfir.stream

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
        binder.focusThermal(droneSn)
        restartLiveStream(droneSn)
        visibleState = BoundStreamState.BOUND
        thermalState = BoundStreamState.BOUND
        playbackStatus = "shared-side-by-side-preview"
        return StreamStartResult(
            visibleState = visibleState,
            thermalState = thermalState,
            thermalFailureMessage = "single-liveview-source-shared-side-by-side-preview",
            playbackStatus = playbackStatus,
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
        liveStreamController.start(droneSn)
    }
}

private class StubMsdkStreamBinder : MsdkStreamBinder {
    override suspend fun bindVisible(droneSn: String) = Unit

    override suspend fun bindThermal(droneSn: String) = Unit

    override suspend fun focusVisible(droneSn: String) = Unit

    override suspend fun focusThermal(droneSn: String) = Unit

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
