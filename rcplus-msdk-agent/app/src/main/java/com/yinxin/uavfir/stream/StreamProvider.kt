package com.yinxin.uavfir.stream

interface StreamProvider {
    suspend fun start(droneSn: String): StreamStartResult

    suspend fun focusVisible(droneSn: String): StreamStartResult

    suspend fun focusThermal(droneSn: String): StreamStartResult

    suspend fun stop()
}

data class StreamStartResult(
    val visibleState: BoundStreamState,
    val thermalState: BoundStreamState,
    val thermalFailureMessage: String? = null,
    val playbackStatus: String? = null,
) {
    val isApplied: Boolean
        get() = visibleState == BoundStreamState.BOUND
}
