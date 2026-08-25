package com.yinxin.uavfir.stream

import com.yinxin.uavfir.BuildConfig
import com.yinxin.uavfir.sdk.PayloadSelectionRegistry
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.livestream.LiveStreamManager
import dji.v5.manager.datacenter.livestream.LiveStreamSettings
import dji.v5.manager.datacenter.livestream.LiveStreamType
import dji.v5.manager.datacenter.livestream.StreamQuality
import dji.v5.manager.datacenter.livestream.settings.RtmpSettings
import dji.v5.manager.interfaces.ILiveStreamManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DjiLiveStreamController(
    private val liveStreamManager: ILiveStreamManager = LiveStreamManager.getInstance(),
    private val host: String = BuildConfig.AGENT_MEDIA_HOST,
    private val rtmpPort: Int = BuildConfig.AGENT_MEDIA_RTMP_PORT,
    private val streamApp: String = BuildConfig.AGENT_MEDIA_STREAM_APP,
) : LiveStreamController {
    override suspend fun start(droneSn: String) {
        // Stream-id must match what backend expects on ZLM. Backend's
        // FireDetectionController uses `drone_sn-0`, where drone_sn is the
        // real aircraft SN tracked by Cloud SDK. We honour AGENT_AIRCRAFT_SN
        // from BuildConfig so the agent advertises that same SN; the droneSn
        // arg is kept for unit-test seams that pass synthetic SNs.
        val effectiveSn = BuildConfig.AGENT_AIRCRAFT_SN.takeIf { it.isNotBlank() } ?: droneSn
        val streamUrl = "rtmp://$host:$rtmpPort/$streamApp/${effectiveSn}-0"
        if (liveStreamManager.isStreaming) {
            awaitCompletion { callback ->
                liveStreamManager.stopStream(callback)
            }
        }
        liveStreamManager.setLiveStreamSettings(
            LiveStreamSettings.Builder()
                .setLiveStreamType(LiveStreamType.RTMP)
                .setRtmpSettings(
                    RtmpSettings.Builder()
                        .setUrl(streamUrl)
                        .build(),
                )
                .build(),
        )
        liveStreamManager.setCameraIndex(PayloadSelectionRegistry.selectedComponentIndex())
        liveStreamManager.setLiveStreamQuality(StreamQuality.FULL_HD)
        liveStreamManager.setLiveAudioEnabled(false)
        awaitCompletion { callback ->
            liveStreamManager.startStream(callback)
        }
    }

    override suspend fun stop() {
        if (!liveStreamManager.isStreaming) {
            return
        }
        awaitCompletion { callback ->
            liveStreamManager.stopStream(callback)
        }
    }

    private suspend fun awaitCompletion(
        block: (CommonCallbacks.CompletionCallback) -> Unit,
    ) {
        withTimeout(MSDK_CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                block(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        continuation.takeIf { it.isActive }?.resume(Unit)
                    }

                    override fun onFailure(error: IDJIError) {
                        continuation.takeIf { it.isActive }
                            ?.resumeWithException(IllegalStateException(error.description()))
                    }
                })
            }
        }
    }

    companion object {
        private const val MSDK_CALLBACK_TIMEOUT_MS: Long = 8_000
    }
}
