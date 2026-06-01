package com.yinxin.uavfir.stream

import android.util.Log
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.camera.ThermalDisplayMode
import dji.sdk.keyvalue.value.camera.ThermalTemperatureMeasureMode
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.DoubleRect
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DjiMsdkStreamBinder : MsdkStreamBinder {
    private val tag = "DjiMsdkStreamBinder"
    private val keyManager: KeyManager
        get() = KeyManager.getInstance()
    private var visibleListener: ICameraStreamManager.ReceiveStreamListener? = null
    private val thermalFrameProbe = ThermalFrameProbe()

    override suspend fun bindVisible(droneSn: String) {
        focusVisible(droneSn)
        val listener = ICameraStreamManager.ReceiveStreamListener { _, _, _, _ -> }
        MediaDataCenter.getInstance()
            .cameraStreamManager
            .addReceiveStreamListener(ComponentIndexType.LEFT_OR_MAIN, listener)
        visibleListener = listener
        thermalFrameProbe.start()
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
            "m4t-single-gimbal-only-exposes-single-component-index",
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
        logCurrentStreamSelection("focusVisible")
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

    override suspend fun captureVisibleSnapshot(droneSn: String): String? {
        focusVisible(droneSn)
        val requestedAtMs = thermalFrameProbe.requestImmediateVisibleSnapshot()
        repeat(VISIBLE_SNAPSHOT_WAIT_ATTEMPTS) {
            delay(VISIBLE_SNAPSHOT_POLL_MS)
            thermalFrameProbe.latestVisibleSnapshotPath(
                minTimestampMs = requestedAtMs,
                maxAgeMs = VISIBLE_SNAPSHOT_MAX_AGE_MS,
            )?.let { path ->
                Log.i(tag, "visible snapshot captured path=$path")
                return path
            }
        }
        Log.w(tag, "visible snapshot unavailable after focus visible")
        return thermalFrameProbe.latestVisibleSnapshotPath(maxAgeMs = VISIBLE_SNAPSHOT_MAX_AGE_MS)
    }

    override suspend fun measureThermalCenterTemperatureC(): Double? {
        return measureThermalRegionTemperatureC(ThermalMeasureRegion.CENTER)
    }

    override suspend fun measureThermalRegionTemperatureC(region: ThermalMeasureRegion): Double? {
        val msdkRegion = region.toDoubleRect()
        return runCatching {
            measureThermalRegionTemperatureWithLensKey(msdkRegion)
        }.recoverCatching { lensError ->
            Log.w(tag, "thermal region temperature lens-key measurement failed: ${lensError.message}", lensError)
            measureThermalRegionTemperatureWithCameraKey(msdkRegion)
        }.onSuccess { temperature ->
            if (temperature != null) {
                Log.i(tag, "thermal region temperature measured: ${temperature}C region=$region")
            } else {
                Log.w(tag, "thermal region temperature unavailable: empty MSDK temperature value")
            }
        }.onFailure {
            Log.w(tag, "thermal region temperature measurement failed: ${it.message}", it)
        }.getOrNull()
    }

    override suspend fun locateAndMeasureThermalHotspotC(
        seedRegion: ThermalMeasureRegion?,
    ): ThermalMeasurementResult? {
        val requestedAtMs = System.currentTimeMillis()
        val frameHotspotRegions = selectThermalHotspotRegions(
            latestFrameHotspotRegions = thermalFrameProbe.latestHotspotRegions(),
            seedRegion = seedRegion,
        )
        if (frameHotspotRegions.isNotEmpty()) {
            val measured = mutableListOf<ThermalMeasuredPoint>()
            for (region in frameHotspotRegions.distinctThermalRegions()) {
                val temperature = measureThermalRegionTemperatureC(region) ?: continue
                val point = ThermalMeasuredPoint(temperature, region)
                measured += point
                if (shouldFastConfirmThermalHotspot(temperature)) {
                    val result = ThermalMeasurementResult(
                        temperatureC = point.temperatureC,
                        region = point.region,
                        thermalSnapshotPath = thermalFrameProbe.latestSnapshotPath(
                            minTimestampMs = requestedAtMs - THERMAL_SNAPSHOT_CLOCK_SKEW_MS,
                        ),
                        measurements = measured.sortedByDescending { it.temperatureC },
                    )
                    Log.i(
                        tag,
                        "thermal frame hotspot fast-confirmed best=${result.temperatureC}C region=${result.region} " +
                            "measurements=${result.measurements}",
                    )
                    return result
                }
            }
            val best = measured.maxByOrNull { it.temperatureC }
            if (best != null) {
                val result = ThermalMeasurementResult(
                    temperatureC = best.temperatureC,
                    region = best.region,
                    thermalSnapshotPath = thermalFrameProbe.latestSnapshotPath(
                        minTimestampMs = requestedAtMs - THERMAL_SNAPSHOT_CLOCK_SKEW_MS,
                    ),
                    measurements = measured.sortedByDescending { it.temperatureC },
                )
                Log.i(
                    tag,
                    "thermal frame hotspots measured best=${result.temperatureC}C region=${result.region} " +
                        "measurements=${result.measurements}",
                )
                return result
            }
        }

        val measured = mutableListOf<ThermalMeasurementResult>()
        val primaryFrameHotspotRegion = frameHotspotRegions.firstOrNull()
        val initialCandidates = if (primaryFrameHotspotRegion != null) {
            localThermalScanRegions(primaryFrameHotspotRegion.centerX(), primaryFrameHotspotRegion.centerY()).distinctThermalRegions()
        } else {
            coarseThermalScanRegions().distinctThermalRegions()
        }

        for (candidate in initialCandidates) {
            val temperature = measureThermalRegionTemperatureC(candidate) ?: continue
            measured += ThermalMeasurementResult(
                temperatureC = temperature,
                region = candidate,
                measurements = listOf(ThermalMeasuredPoint(temperature, candidate)),
            )
        }

        val coarseBest = measured.maxByOrNull { it.temperatureC }
        if (coarseBest != null && primaryFrameHotspotRegion == null) {
            val refinedCandidates = localThermalScanRegions(
                coarseBest.region.centerX(),
                coarseBest.region.centerY(),
            ).distinctThermalRegions()
            for (candidate in refinedCandidates) {
                if (measured.any { it.region.sameCell(candidate) }) {
                    continue
                }
                val temperature = measureThermalRegionTemperatureC(candidate) ?: continue
                measured += ThermalMeasurementResult(
                    temperatureC = temperature,
                    region = candidate,
                    measurements = listOf(ThermalMeasuredPoint(temperature, candidate)),
                )
            }
        }

        val best = measured.maxByOrNull { it.temperatureC }
        if (best != null) {
            Log.i(tag, "thermal hotspot temperature measured: ${best.temperatureC}C region=${best.region}")
            return best.copy(
                measurements = measured
                    .map { ThermalMeasuredPoint(it.temperatureC, it.region) }
                    .sortedByDescending { it.temperatureC },
            )
        }
        return seedRegion?.let { region ->
            measureThermalRegionTemperatureC(region)?.let { temperature ->
                ThermalMeasurementResult(
                    temperatureC = temperature,
                    region = region,
                    measurements = listOf(ThermalMeasuredPoint(temperature, region)),
                )
            }
        } ?: measureThermalCenterTemperatureC()?.let { temperature ->
            ThermalMeasurementResult(
                temperatureC = temperature,
                region = ThermalMeasureRegion.CENTER,
                measurements = listOf(ThermalMeasuredPoint(temperature, ThermalMeasureRegion.CENTER)),
            )
        }
    }

    private suspend fun measureThermalRegionTemperatureWithLensKey(region: DoubleRect): Double? {
        setValue(
            KeyTools.createCameraKey(
                DJICameraKey.KeyThermalTemperatureMeasureMode,
                ComponentIndexType.LEFT_OR_MAIN,
                CameraLensType.CAMERA_LENS_THERMAL,
            ),
            ThermalTemperatureMeasureMode.REGION,
        )
        setValue(
            KeyTools.createCameraKey(
                DJICameraKey.KeyThermalRegionMetersureArea,
                ComponentIndexType.LEFT_OR_MAIN,
                CameraLensType.CAMERA_LENS_THERMAL,
            ),
            region,
        )
        delay(THERMAL_MEASURE_SETTLE_MS)
        return keyManager.getValue(
            KeyTools.createCameraKey(
                DJICameraKey.KeyThermalRegionMetersureTemperature,
                ComponentIndexType.LEFT_OR_MAIN,
                CameraLensType.CAMERA_LENS_THERMAL,
            ),
        )?.maxAreaTemperature
    }

    private suspend fun measureThermalRegionTemperatureWithCameraKey(region: DoubleRect): Double? {
        setValue(
            KeyTools.createKey(
                DJICameraKey.KeyThermalTemperatureMeasureMode,
                ComponentIndexType.LEFT_OR_MAIN,
            ),
            ThermalTemperatureMeasureMode.REGION,
        )
        setValue(
            KeyTools.createKey(
                DJICameraKey.KeyThermalRegionMetersureArea,
                ComponentIndexType.LEFT_OR_MAIN,
            ),
            region,
        )
        delay(THERMAL_MEASURE_SETTLE_MS)
        return keyManager.getValue(
            KeyTools.createKey(
                DJICameraKey.KeyThermalRegionMetersureTemperature,
                ComponentIndexType.LEFT_OR_MAIN,
            ),
        )?.maxAreaTemperature
    }

    override suspend fun unbindAll() {
        visibleListener?.let {
            MediaDataCenter.getInstance()
                .cameraStreamManager
                .removeReceiveStreamListener(it)
        }
        visibleListener = null
        thermalFrameProbe.stop()
    }

    private fun preferredVisibleSource(): CameraVideoStreamSourceType {
        val sourceNames = loadAvailableSources()
        sourceNames.firstOrNull { it == CameraVideoStreamSourceType.ZOOM_CAMERA }?.let {
            return it
        }
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
        private const val THERMAL_MEASURE_SETTLE_MS: Long = 400
        private const val VISIBLE_SNAPSHOT_POLL_MS: Long = 100
        private const val VISIBLE_SNAPSHOT_WAIT_ATTEMPTS: Int = 12
        private const val VISIBLE_SNAPSHOT_MAX_AGE_MS: Long = 3_000
        private const val THERMAL_SNAPSHOT_CLOCK_SKEW_MS: Long = 1_000
        const val COARSE_SCAN_COLUMNS = 5
        const val COARSE_SCAN_ROWS = 4
        const val COARSE_SCAN_REGION_SIZE = 0.18
        const val LOCAL_SCAN_REGION_SIZE = 0.08
        const val LOCAL_SCAN_STEP = 0.07
    }
}

private fun coarseThermalScanRegions(): List<ThermalMeasureRegion> {
    val regions = mutableListOf<ThermalMeasureRegion>()
    for (row in 0 until DjiMsdkStreamBinder.COARSE_SCAN_ROWS) {
        val y = axisCenter(row, DjiMsdkStreamBinder.COARSE_SCAN_ROWS)
        for (column in 0 until DjiMsdkStreamBinder.COARSE_SCAN_COLUMNS) {
            val x = axisCenter(column, DjiMsdkStreamBinder.COARSE_SCAN_COLUMNS)
            regions += regionAround(
                centerX = x,
                centerY = y,
                size = DjiMsdkStreamBinder.COARSE_SCAN_REGION_SIZE,
            )
        }
    }
    return regions
}

private fun localThermalScanRegions(centerX: Double, centerY: Double): List<ThermalMeasureRegion> {
    val regions = mutableListOf<ThermalMeasureRegion>()
    for (dy in -1..1) {
        for (dx in -1..1) {
            regions += regionAround(
                centerX = centerX + dx * DjiMsdkStreamBinder.LOCAL_SCAN_STEP,
                centerY = centerY + dy * DjiMsdkStreamBinder.LOCAL_SCAN_STEP,
                size = DjiMsdkStreamBinder.LOCAL_SCAN_REGION_SIZE,
            )
        }
    }
    return regions
}

private fun axisCenter(index: Int, count: Int): Double {
    if (count <= 1) {
        return 0.5
    }
    return (index + 0.5) / count
}

internal fun selectThermalHotspotRegion(
    latestFrameHotspotRegion: ThermalMeasureRegion?,
    seedRegion: ThermalMeasureRegion?,
): ThermalMeasureRegion? = latestFrameHotspotRegion ?: seedRegion

internal fun selectThermalHotspotRegions(
    latestFrameHotspotRegions: List<ThermalMeasureRegion>,
    seedRegion: ThermalMeasureRegion?,
): List<ThermalMeasureRegion> =
    if (latestFrameHotspotRegions.isNotEmpty()) {
        latestFrameHotspotRegions.clusterNearbyThermalRegions()
    } else {
        seedRegion?.let { listOf(it) }.orEmpty()
    }

internal fun shouldFastConfirmThermalHotspot(temperatureC: Double): Boolean =
    temperatureC >= FAST_CONFIRM_TEMPERATURE_C

private fun List<ThermalMeasureRegion>.clusterNearbyThermalRegions(): List<ThermalMeasureRegion> {
    val selected = mutableListOf<ThermalMeasureRegion>()
    for (region in this) {
        if (selected.none { it.centerDistanceTo(region) <= HOTSPOT_CLUSTER_DISTANCE }) {
            selected += region
        }
    }
    return selected
}

private fun regionAround(centerX: Double, centerY: Double, size: Double): ThermalMeasureRegion {
    val width = size.coerceIn(0.01, 1.0)
    val height = size.coerceIn(0.01, 1.0)
    val x = (centerX - width / 2).coerceIn(0.0, 1.0 - width)
    val y = (centerY - height / 2).coerceIn(0.0, 1.0 - height)
    return ThermalMeasureRegion(
        x = roundMeasureCoordinate(x),
        y = roundMeasureCoordinate(y),
        width = roundMeasureCoordinate(width),
        height = roundMeasureCoordinate(height),
    )
}

private fun ThermalMeasureRegion.centerX(): Double = x + width / 2

private fun ThermalMeasureRegion.centerY(): Double = y + height / 2

private fun ThermalMeasureRegion.centerDistanceTo(other: ThermalMeasureRegion): Double {
    val dx = centerX() - other.centerX()
    val dy = centerY() - other.centerY()
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private fun List<ThermalMeasureRegion>.distinctThermalRegions(): List<ThermalMeasureRegion> {
    val seen = mutableSetOf<String>()
    return filter { seen.add(it.cellKey()) }
}

private fun ThermalMeasureRegion.sameCell(other: ThermalMeasureRegion): Boolean = cellKey() == other.cellKey()

private fun ThermalMeasureRegion.cellKey(): String =
    "${roundMeasureCoordinate(x)}:${roundMeasureCoordinate(y)}:${roundMeasureCoordinate(width)}:${roundMeasureCoordinate(height)}"

private fun roundMeasureCoordinate(value: Double): Double =
    kotlin.math.round(value * 10_000.0) / 10_000.0

private const val HOTSPOT_CLUSTER_DISTANCE = 0.075
private const val FAST_CONFIRM_TEMPERATURE_C = 120.0

private fun ThermalMeasureRegion.toDoubleRect(): DoubleRect {
    val normalizedX = x.coerceIn(0.0, 0.99)
    val normalizedY = y.coerceIn(0.0, 0.99)
    val normalizedWidth = width.coerceIn(0.01, 1.0 - normalizedX)
    val normalizedHeight = height.coerceIn(0.01, 1.0 - normalizedY)
    return DoubleRect(normalizedX, normalizedY, normalizedWidth, normalizedHeight)
}
