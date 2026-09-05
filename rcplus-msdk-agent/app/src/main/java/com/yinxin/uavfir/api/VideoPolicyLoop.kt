package com.yinxin.uavfir.api

import com.yinxin.uavfir.stream.VideoPolicyPort
import com.yinxin.uavfir.stream.VideoPolicyState
import kotlinx.coroutines.*
import java.util.UUID

class VideoPolicyLoop(
    private val scope: CoroutineScope,
    private val state: VideoPolicyState,
    private val port: VideoPolicyPort,
    private val requester: VideoPolicyRequester,
    private val instanceId: String = UUID.randomUUID().toString(),
    private val onError: (Throwable) -> Unit = {},
) {
    private var pollJob: Job? = null
    private var guardJob: Job? = null

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val context = state.beginRequest()
                if (context != null) {
                    try {
                        val decision = withTimeout(8_000) { requester.request(context.droneSn, port.videoReport(instanceId)) }
                        state.accept(context, instanceId, decision)
                    } catch (cancelled: CancellationException) {
                        state.revoke(context)
                        if (cancelled !is TimeoutCancellationException) throw cancelled
                        onError(cancelled)
                    } catch (error: Exception) {
                        state.revoke(context)
                        onError(error)
                    }
                }
                delay(2_000)
            }
        }
        guardJob = scope.launch {
            while (isActive) {
                try {
                    if (state.beginRequest() != null) port.enforcePolicy()
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException) throw cancelled
                    onError(cancelled)
                } catch (error: Exception) {
                    onError(error)
                }
                delay(250)
            }
        }
    }

    fun stop() {
        pollJob?.cancel(); pollJob = null
        guardJob?.cancel(); guardJob = null
        state.beginRequest()?.let(state::revoke)
    }
}
