package com.yinxin.uavfir.wayline

import com.yinxin.uavfir.sdk.PayloadSelectionRegistry
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DjiPatrolZoomPort : PatrolZoomPort {
    private val component get() = PayloadSelectionRegistry.selectedComponentIndex()
    private val ratioKey get() = KeyTools.createCameraKey(
        CameraKey.KeyCameraZoomRatios, component, CameraLensType.CAMERA_LENS_ZOOM,
    )
    override suspend fun relativeHeightM(): Double? =
        read(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D))?.altitude

    override suspend fun pitchDegrees(): Double? =
        read(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, component))?.pitch

    override suspend fun range(): PatrolZoomRange {
        val value = read(KeyTools.createCameraKey(
            CameraKey.KeyCameraZoomRatiosRange, component, CameraLensType.CAMERA_LENS_ZOOM,
        )) ?: error("zoom-range-unavailable")
        return PatrolZoomRange(value.isContinuous, value.gears.map { it.toDouble() })
    }

    override suspend fun currentRatio(): Double? = read(ratioKey)

    override suspend fun prepareVisibleZoom(): Boolean {
        val key = KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, component)
        val source = read(key) ?: return false
        if (source == CameraVideoStreamSourceType.ZOOM_CAMERA) return true
        if (source != CameraVideoStreamSourceType.WIDE_CAMERA &&
            source != CameraVideoStreamSourceType.DEFAULT_CAMERA) return false
        val sources = read(KeyTools.createKey(CameraKey.KeyCameraVideoStreamSourceRange, component))
        if (sources?.contains(CameraVideoStreamSourceType.ZOOM_CAMERA) != true) return false
        write(key, CameraVideoStreamSourceType.ZOOM_CAMERA)
        return read(key) == CameraVideoStreamSourceType.ZOOM_CAMERA
    }

    override suspend fun setRatio(ratio: Double) = write(ratioKey, ratio)

    private suspend fun <T> read(key: DJIKey<T>): T? = withTimeout(3000) {
        suspendCancellableCoroutine { continuation ->
            KeyManager.getInstance().getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(result: T?) { if (continuation.isActive) continuation.resume(result) }
                override fun onFailure(error: IDJIError) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error.description()))
                }
            })
        }
    }

    // Drain an already-submitted camera command before yielding to fire confirmation.
    private suspend fun <T> write(key: DJIKey<T>, value: T) = withContext(NonCancellable) {
        withTimeout(3000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                KeyManager.getInstance().setValue(key, value, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                    override fun onFailure(error: IDJIError) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error.description()))
                    }
                })
            }
        }
    }
}
