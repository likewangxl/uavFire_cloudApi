package com.yinxin.uavfir.stream

interface StreamProvider {
    suspend fun start(droneSn: String): StreamStartResult

    suspend fun focusVisible(droneSn: String): StreamStartResult

    suspend fun focusThermal(droneSn: String): StreamStartResult

    suspend fun focusThermal(
        droneSn: String,
        thermalMeasureRegion: ThermalMeasureRegion?,
    ): StreamStartResult = focusThermal(droneSn)

    suspend fun measureThermalRegion(
        droneSn: String,
        thermalMeasureRegion: ThermalMeasureRegion,
    ): StreamStartResult = focusThermal(droneSn, thermalMeasureRegion)

    suspend fun measureThermalHotspot(
        droneSn: String,
        seedRegion: ThermalMeasureRegion? = null,
    ): StreamStartResult = focusThermal(droneSn, seedRegion)

    suspend fun captureVisibleSnapshot(droneSn: String): StreamStartResult = focusVisible(droneSn)

    suspend fun stop()
}

data class StreamStartResult(
    val visibleState: BoundStreamState,
    val thermalState: BoundStreamState,
    val thermalFailureMessage: String? = null,
    val playbackStatus: String? = null,
    val thermalCenterTemperatureC: Double? = null,
    val thermalMeasureRegion: ThermalMeasureRegion? = null,
    val thermalMeasurements: List<ThermalMeasuredPoint> = emptyList(),
    val thermalSnapshotPath: String? = null,
    val visibleSnapshotPath: String? = null,
) {
    val isApplied: Boolean
        get() = visibleState == BoundStreamState.BOUND
}
