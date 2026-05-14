package com.yinxin.uavfir.stream

import android.util.Log
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.camera.ThermalDisplayMode
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DjiMsdkStreamBinder : MsdkStreamBinder {
    private val tag = "DjiMsdkStreamBinder"
    private val keyManager: KeyManager
        get() = KeyManager.getInstance()
    private var visibleListener: ICameraStreamManager.ReceiveStreamListener? = null

    override suspend fun bindVisible(droneSn: String) {
        focusVisible(droneSn)
        val listener = ICameraStreamManager.ReceiveStreamListener { _, _, _, _ -> }
        MediaDataCenter.getInstance()
            .cameraStreamManager
            .addReceiveStreamListener(ComponentIndexType.LEFT_OR_MAIN, listener)
        visibleListener = listener
    }

    override suspend fun bindThermal(droneSn: String) {
        val availableSources = KeyManager.getInstance().getValue(
            KeyTools.createKey(
                CameraKey.KeyCameraVideoStreamSourceRange,
                ComponentIndexType.LEFT_OR_MAIN,
            ),
        ) as? List<*>

        val thermalSupported = availableSources.orEmpty().any {
            it?.toString() == "INFRARED_CAMERA"
        }

        if (!thermalSupported) {
            throw IllegalStateException("thermal-stream-source-unavailable")
        }

        throw UnsupportedOperationException(
            "msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding",
        )
    }

    override suspend fun focusVisible(droneSn: String) {
        setValue(
            KeyTools.createKey(
                CameraKey.KeyCameraVideoStreamSource,
                ComponentIndexType.LEFT_OR_MAIN,
            ),
            preferredVisibleSource(),
        )
        resetThermalDisplayModeToVisualOnly()
        logCurrentStreamSelection("focusVisible")
    }

    private suspend fun resetThermalDisplayModeToVisualOnly() {
        runCatching {
            setValue(
                KeyTools.createCameraKey(
                    DJICameraKey.KeyThermalDisplayMode,
                    ComponentIndexType.LEFT_OR_MAIN,
                    CameraLensType.CAMERA_LENS_THERMAL,
                ),
                ThermalDisplayMode.VISUAL_ONLY,
            )
        }.onFailure {
            Log.w(tag, "focusVisible failed to reset lens thermal display mode: ${it.message}", it)
            runCatching {
                setValue(
                    KeyTools.createKey(
                        DJICameraKey.KeyThermalDisplayMode,
                        ComponentIndexType.LEFT_OR_MAIN,
                    ),
                    ThermalDisplayMode.VISUAL_ONLY,
                )
            }.onFailure { fallbackError ->
                Log.w(tag, "focusVisible failed to reset camera thermal display mode: ${fallbackError.message}", fallbackError)
            }
        }
    }

    override suspend fun focusThermal(droneSn: String) {
        setValue(
            KeyTools.createKey(
                CameraKey.KeyCameraVideoStreamSource,
                ComponentIndexType.LEFT_OR_MAIN,
            ),
            CameraVideoStreamSourceType.INFRARED_CAMERA,
        )
        runCatching {
            setValue(
                KeyTools.createCameraKey(
                    DJICameraKey.KeyThermalDisplayMode,
                    ComponentIndexType.LEFT_OR_MAIN,
                    CameraLensType.CAMERA_LENS_THERMAL,
                ),
                ThermalDisplayMode.THERMAL_ONLY,
            )
        }.onFailure {
            Log.w(tag, "focusThermal failed to set thermal-only display mode: ${it.message}", it)
        }
        logCurrentStreamSelection("focusThermal")
    }

    override suspend fun unbindAll() {
        visibleListener?.let {
            MediaDataCenter.getInstance()
                .cameraStreamManager
                .removeReceiveStreamListener(it)
        }
        visibleListener = null
    }

    private fun preferredVisibleSource(): CameraVideoStreamSourceType {
        val sourceNames = loadAvailableSources()
        return sourceNames.firstOrNull { it != CameraVideoStreamSourceType.INFRARED_CAMERA }
            ?: CameraVideoStreamSourceType.DEFAULT_CAMERA
    }

    private fun loadAvailableSources(): List<CameraVideoStreamSourceType> {
        return (keyManager.getValue(
            KeyTools.createKey(
                CameraKey.KeyCameraVideoStreamSourceRange,
                ComponentIndexType.LEFT_OR_MAIN,
            ),
        ) as? List<*>)
            ?.filterIsInstance<CameraVideoStreamSourceType>()
            .orEmpty()
    }

    private fun logCurrentStreamSelection(action: String) {
        runCatching {
            val currentSource = keyManager.getValue(
                KeyTools.createKey(
                    CameraKey.KeyCameraVideoStreamSource,
                    ComponentIndexType.LEFT_OR_MAIN,
                ),
            )
            val sourceRange = loadAvailableSources()
            val displayMode = keyManager.getValue(
                KeyTools.createCameraKey(
                    DJICameraKey.KeyThermalDisplayMode,
                    ComponentIndexType.LEFT_OR_MAIN,
                    CameraLensType.CAMERA_LENS_THERMAL,
                ),
            )
            val pipPosition = keyManager.getValue(
                KeyTools.createCameraKey(
                    DJICameraKey.KeyThermalPIPPosition,
                    ComponentIndexType.LEFT_OR_MAIN,
                    CameraLensType.CAMERA_LENS_THERMAL,
                ),
            )
            Log.i(
                tag,
                "$action streamSource=$currentSource sourceRange=$sourceRange thermalDisplayMode=$displayMode thermalPipPosition=$pipPosition",
            )
        }.onFailure {
            Log.w(tag, "$action failed to read stream selection: ${it.message}", it)
        }
    }

    private suspend fun <T> setValue(key: dji.sdk.keyvalue.key.DJIKey<T>, value: T) {
        withTimeout(MSDK_CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                keyManager.setValue(key, value, object : CommonCallbacks.CompletionCallback {
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
