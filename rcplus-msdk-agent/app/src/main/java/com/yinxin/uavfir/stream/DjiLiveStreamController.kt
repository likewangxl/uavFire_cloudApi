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
import dji.v5.manager.datacenter.livestream.LiveVideoBitrateMode
import dji.v5.manager.datacenter.livestream.LiveStreamStatus
import dji.v5.manager.datacenter.livestream.LiveStreamStatusListener
import dji.v5.manager.datacenter.livestream.settings.RtmpSettings
import dji.v5.manager.interfaces.ILiveStreamManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.SystemClock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DjiLiveStreamController(
    private val liveStreamManager: ILiveStreamManager = LiveStreamManager.getInstance(),
    private val host: String = BuildConfig.AGENT_MEDIA_HOST,
    private val rtmpPort: Int = BuildConfig.AGENT_MEDIA_RTMP_PORT,
    private val streamApp: String = BuildConfig.AGENT_MEDIA_STREAM_APP,
    private val policy: VideoPolicyState = VideoPolicyState(SystemClock::elapsedRealtime),
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) : LiveStreamController, VideoPolicyPort {
    private val mutex = Mutex()
    private val encoding = VideoEncodingGuard(::applyProfile, ::stopLocked, clockMs)
    @Volatile private var sample: LiveStreamStatus? = null
    @Volatile private var sampleAtMs: Long? = null
    private var listenerRegistered = false
    private val listener = object : LiveStreamStatusListener {
        override fun onLiveStreamStatusUpdate(status: LiveStreamStatus) {
            sample = status
            sampleAtMs = clockMs()
        }
        override fun onError(error: IDJIError) {
            encoding.reportError(error.description())
        }
    }

    override suspend fun start(droneSn: String) = mutex.withLock {
        // Stream-id must match what backend expects on ZLM. Backend's
        // FireDetectionController uses `drone_sn-0`, where drone_sn is the
        // real aircraft SN tracked by Cloud SDK. We honour AGENT_AIRCRAFT_SN
        // from BuildConfig so the agent advertises that same SN; the droneSn
        // arg is kept for unit-test seams that pass synthetic SNs.
        val effectiveSn = BuildConfig.AGENT_AIRCRAFT_SN.takeIf { it.isNotBlank() } ?: droneSn
        policy.selectAircraft(effectiveSn)
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
        if (!listenerRegistered) {
            liveStreamManager.addLiveStreamStatusListener(listener)
            listenerRegistered = true
        }
        encoding.configureForStart(policy.desiredProfile())
        liveStreamManager.setLiveAudioEnabled(false)
        awaitCompletion { callback ->
            liveStreamManager.startStream(callback)
        }
    }

    override suspend fun stop() = mutex.withLock {
        stopLocked()
    }

    private suspend fun stopLocked() {
        if (listenerRegistered) {
            liveStreamManager.removeLiveStreamStatusListener(listener)
            listenerRegistered = false
        }
        sample = null
        sampleAtMs = null
        if (!liveStreamManager.isStreaming) {
            return
        }
        awaitCompletion { callback ->
            liveStreamManager.stopStream(callback)
        }
    }

    override suspend fun enforcePolicy() = mutex.withLock {
        if (!liveStreamManager.isStreaming) return@withLock
        encoding.enforce(policy.desiredProfile())
    }

    private fun applyProfile(profile: VideoProfile) {
        liveStreamManager.setLiveStreamQuality(if (profile == VideoProfile.HIGH) StreamQuality.FULL_HD else StreamQuality.SD)
        liveStreamManager.setLiveVideoBitrateMode(LiveVideoBitrateMode.MANUAL)
        liveStreamManager.setLiveVideoBitrate(profile.bitrateBps)
        check(liveStreamManager.getLiveVideoBitrateMode() == LiveVideoBitrateMode.MANUAL
            && liveStreamManager.getLiveVideoBitrate() == profile.bitrateBps
            && liveStreamManager.getLiveStreamQuality() == if (profile == VideoProfile.HIGH) StreamQuality.FULL_HD else StreamQuality.SD
        ) { "video-profile-readback-mismatch" }
    }

    override fun videoReport(instanceId: String): VideoPolicyReport {
        val observed = sample
        val age = sampleAtMs?.let { (clockMs() - it).coerceAtLeast(0) }
        return VideoPolicyReport(
            instanceId = instanceId,
            appliedProfile = encoding.appliedProfile?.name,
            streaming = liveStreamManager.isStreaming,
            configuredBitrateBps = encoding.appliedProfile?.bitrateBps,
            sdkVbps = observed?.vbps,
            sdkFps = observed?.fps,
            sdkWidth = observed?.resolution?.width,
            sdkHeight = observed?.resolution?.height,
            sdkSampleAgeMs = age,
            error = encoding.error(liveStreamManager.isStreaming),
        )
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
