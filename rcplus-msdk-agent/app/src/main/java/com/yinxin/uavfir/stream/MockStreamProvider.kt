package com.yinxin.uavfir.stream

class MockStreamProvider : StreamProvider {
    override suspend fun start(droneSn: String): StreamStartResult = StreamStartResult(
        visibleState = BoundStreamState.BOUND,
        thermalState = BoundStreamState.BOUND,
        playbackStatus = "shared-side-by-side-preview",
    )

    override suspend fun focusVisible(droneSn: String): StreamStartResult = StreamStartResult(
        visibleState = BoundStreamState.BOUND,
        thermalState = BoundStreamState.IDLE,
        playbackStatus = "visible-live-ready",
    )

    override suspend fun focusThermal(droneSn: String): StreamStartResult {
        return focusThermal(droneSn, null)
    }

    override suspend fun focusThermal(
        droneSn: String,
        thermalMeasureRegion: ThermalMeasureRegion?,
    ): StreamStartResult = StreamStartResult(
        visibleState = BoundStreamState.BOUND,
        thermalState = BoundStreamState.BOUND,
        thermalFailureMessage = "single-liveview-source-shared-side-by-side-preview",
        playbackStatus = "shared-side-by-side-preview",
    )

    override suspend fun stop() {
    }
}
