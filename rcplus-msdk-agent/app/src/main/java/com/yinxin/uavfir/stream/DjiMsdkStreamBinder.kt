package com.yinxin.uavfir.stream

import android.util.Log
import com.yinxin.uavfir.sdk.PayloadSelectionRegistry
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.camera.ThermalDisplayMode
import dji.sdk.keyvalue.value.camera.ThermalGainMode
import dji.sdk.keyvalue.value.camera.ThermalTemperatureMeasureMode
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.DoublePoint2D
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

class DjiMsdkStreamBinder(
    hotspotCandidateListener: ThermalHotspotCandidateListener = ThermalHotspotCandidateListener.NO_OP,
) : MsdkStreamBinder {
    private val tag = "DjiMsdkStreamBinder"
    private val keyManager: KeyManager
        get() = KeyManager.getInstance()
    private val componentIndex: ComponentIndexType
        get() = PayloadSelectionRegistry.selectedComponentIndex()
    private var visibleListener: ICameraStreamManager.ReceiveStreamListener? = null
    private val thermalFrameProbe by lazy {
        ThermalFrameProbe(
            componentIndex = PayloadSelectionRegistry.selectedComponentIndex(),
            hotspotCandidateListener = hotspotCandidateListener,
        )
    }

    override suspend fun bindVisible(droneSn: String) {
        focusVisible(droneSn)
        val listener = ICameraStreamManager.ReceiveStreamListener { _, _, _, _ -> }
        MediaDataCenter.getInstance()
            .cameraStreamManager
            .addReceiveStreamListener(componentIndex, listener)
        visibleListener = listener
        thermalFrameProbe.start()
    }

    override suspend fun bindThermal(droneSn: String) {
        val availableSources = KeyManager.getInstance().getValue(
            KeyTools.createKey(
                CameraKey.KeyCameraVideoStreamSourceRange,
                componentIndex,
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
                componentIndex,
            ),
            preferredVisibleSource(),
        )
        resetVisibleZoomToWide()
        logCurrentStreamSelection("focusVisible")
    }

    // 切回可见光时把变焦倍率重置为 1x，保证驾驶舱"每次进去"看到的可见光画面是默认 1 倍焦距，
    // 而不是上一次手动放大后的倍率。失败（个别镜头不支持）不阻断主流程。
    private suspend fun resetVisibleZoomToWide() {
        runCatching {
            setValue(
                KeyTools.createKey(
                    CameraKey.KeyCameraZoomRatios,
                    componentIndex,
                ),
                DEFAULT_VISIBLE_ZOOM_RATIO,
            )
        }.onFailure {
            Log.w(tag, "focusVisible failed to reset zoom ratio to 1x: ${it.message}", it)
        }
    }

    override suspend fun focusThermal(droneSn: String) {
        setValue(
            KeyTools.createKey(
                CameraKey.KeyCameraVideoStreamSource,
                componentIndex,
            ),
            CameraVideoStreamSourceType.INFRARED_CAMERA,
        )
        runCatching {
            setValue(
                KeyTools.createCameraKey(
                    DJICameraKey.KeyThermalDisplayMode,
                    componentIndex,
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
        // 测温数据总开关是前置条件：未开启时固件对一切测温设置返回 SYSTEM_ERROR(-7)。
        ensureThermalTemperatureDataEnabled()
        // 设置步骤降级后失败返回 null 而非抛异常，用空值链逐级回退：
        // 区域测温(lens) -> 点测温(lens) -> 全局最高温(lens,只读) -> 区域测温(camera)。
        val temperature = measureThermalRegionTemperatureWithLensKey(msdkRegion)
            ?: measureThermalSpotTemperatureWithLensKey(msdkRegion)
            ?: measureThermalGlobalMaxTemperature()
            ?: measureThermalRegionTemperatureWithCameraKey(msdkRegion)
        if (temperature != null) {
            Log.i(tag, "thermal region temperature measured: ${temperature}C region=$region")
        } else {
            Log.w(tag, "thermal region temperature unavailable after lens+camera key attempts region=$region")
        }
        return temperature
    }

    @Volatile
    private var thermalCapabilityProbed = false

    /** 一次性能力探针：区分“测温功能族被固件关闭”与“红外镜头 key 整体不可用”。 */
    private suspend fun probeThermalCapabilityOnce() {
        if (thermalCapabilityProbed) {
            return
        }
        thermalCapabilityProbed = true
        fun <T> lensKey(key: dji.sdk.keyvalue.key.DJIKeyInfo<T>) = KeyTools.createCameraKey(
            key,
            componentIndex,
            CameraLensType.CAMERA_LENS_THERMAL,
        )
        val measureParamExisted =
            getValueAsync(lensKey(DJICameraKey.KeyThermalMeasureParamExisted), "probe-measure-param-existed")
        val paletteExisted =
            getValueAsync(lensKey(DJICameraKey.KeyThermalPaletteExisted), "probe-palette-existed")
        val palette = getValueAsync(lensKey(DJICameraKey.KeyThermalPalette), "probe-palette")
        val gainMode = getValueAsync(lensKey(DJICameraKey.KeyThermalGainMode), "probe-gain-mode")
        val displayMode = getValueAsync(lensKey(DJICameraKey.KeyThermalDisplayMode), "probe-display-mode")
        val temperatureDataEnabled =
            getValueAsync(lensKey(DJICameraKey.KeyThermalTemperatureDataEnabled), "probe-temp-data-enabled")
        Log.i(
            tag,
            "thermal capability probe: measureParamExisted=$measureParamExisted paletteExisted=$paletteExisted " +
                "palette=$palette gainMode=$gainMode displayMode=$displayMode tempDataEnabled=$temperatureDataEnabled",
        )
    }

    private suspend fun ensureThermalTemperatureDataEnabled() {
        probeThermalCapabilityOnce()
        // 超清(SUPER_CLEAR)增益模式下固件禁用测温（2026-07-24 实测：一切测温 set 返回 -7）。
        // 火情任务里测温优先于显示效果：发现超清就自动切回 AUTO。
        val gainMode = getValueAsync(
            KeyTools.createCameraKey(
                DJICameraKey.KeyThermalGainMode,
                componentIndex,
                CameraLensType.CAMERA_LENS_THERMAL,
            ),
            "thermal-gain-mode(lens)",
        )
        if (gainMode == ThermalGainMode.SUPER_CLEAR) {
            Log.w(tag, "thermal gain mode is SUPER_CLEAR (blocks measurement); switching to AUTO")
            setValueBestEffort(
                KeyTools.createCameraKey(
                    DJICameraKey.KeyThermalGainMode,
                    componentIndex,
                    CameraLensType.CAMERA_LENS_THERMAL,
                ),
                ThermalGainMode.AUTO,
                "thermal-gain-mode-auto(lens)",
            )
        }
        setValueBestEffort(
            KeyTools.createCameraKey(
                DJICameraKey.KeyThermalTemperatureDataEnabled,
                componentIndex,
                CameraLensType.CAMERA_LENS_THERMAL,
            ),
            true,
            "thermal-temperature-data-enabled(lens)",
        )
    }

    private suspend fun measureThermalGlobalMaxTemperature(): Double? {
        // 全画面最高温：只读、无需设置测温区域，对“画面里有没有高温物”这一仲裁足够。
        return validTemperature(
            getValueAsync(
                KeyTools.createCameraKey(
                    DJICameraKey.KeyThermalGlobalMaxTemperature,
                    componentIndex,
                    CameraLensType.CAMERA_LENS_THERMAL,
                ),
                "thermal-global-max-temperature(lens)",
            ),
        )
    }

    private fun validTemperature(value: Double?): Double? {
        // 设置未生效时固件会回 0.0 占位值；夏季场景 0.0°C 视为无效读数。
        if (value == null || kotlin.math.abs(value) < INVALID_TEMPERATURE_EPSILON) {
            return null
        }
        return value
    }

    override suspend fun measureThermalRegionHotspotC(
        region: ThermalMeasureRegion,
    ): ThermalMeasurementResult? {
        val requestedAtMs = System.currentTimeMillis()
        // 混合测温：框级区域 max 被大框/坐标偏差稀释（2026-07-26 实飞盆火只读 39~68°C，
        // 亮块紧贴热核读 153°C）。帧探针近饱和亮块落在 YOLO 框内的优先测，
        // 框本身作兜底候选；≥120°C 走快速确认短路。
        val inRoiHotspots = thermalFrameProbe.latestHotspotRegions()
            .filter { it.centerWithinRegion(region, HOTSPOT_IN_ROI_MARGIN) }
        return measureThermalHotspotCandidates(
            requestedAtMs = requestedAtMs,
            latestFrameHotspotRegions = inRoiHotspots,
            seedRegion = region,
            measureTemperatureC = { candidate -> measureThermalRegionTemperatureC(candidate) },
            latestSnapshotPath = { minTimestampMs ->
                thermalFrameProbe.latestSnapshotPath(minTimestampMs = minTimestampMs)
            },
        )
    }

    override suspend fun locateAndMeasureThermalHotspotC(
        seedRegion: ThermalMeasureRegion?,
    ): ThermalMeasurementResult? {
        val requestedAtMs = System.currentTimeMillis()
        val result = measureThermalHotspotCandidates(
            requestedAtMs = requestedAtMs,
            latestFrameHotspotRegions = thermalFrameProbe.latestHotspotRegions(),
            seedRegion = seedRegion,
            measureTemperatureC = { region -> measureThermalRegionTemperatureC(region) },
            latestSnapshotPath = { minTimestampMs ->
                thermalFrameProbe.latestSnapshotPath(minTimestampMs = minTimestampMs)
            },
        )
        if (result != null) {
            Log.i(
                tag,
                "thermal hotspot candidates measured best=${result.temperatureC}C region=${result.region} " +
                    "measurements=${result.measurements}",
            )
            return result
        }
        return if (seedRegion != null) {
            measureThermalCenterTemperatureC()?.let { temperature ->
                ThermalMeasurementResult(
                    temperatureC = temperature,
                    region = ThermalMeasureRegion.CENTER,
                    measurements = listOf(ThermalMeasuredPoint(temperature, ThermalMeasureRegion.CENTER)),
                )
            }
        } else {
            null
        }
    }

    private suspend fun measureThermalRegionTemperatureWithLensKey(region: DoubleRect): Double? {
        // 模式/区域设置失败不再中断：部分机型固件对这两个 set 返回错误但区域测温读数仍可用。
        setValueBestEffort(
            KeyTools.createCameraKey(
                DJICameraKey.KeyThermalTemperatureMeasureMode,
                componentIndex,
                CameraLensType.CAMERA_LENS_THERMAL,
            ),
            ThermalTemperatureMeasureMode.REGION,
            "thermal-measure-mode(lens)",
        )
        setValueBestEffort(
            KeyTools.createCameraKey(
                DJICameraKey.KeyThermalRegionMetersureArea,
                componentIndex,
                CameraLensType.CAMERA_LENS_THERMAL,
            ),
            region,
            "thermal-measure-area(lens)",
        )
        delay(THERMAL_MEASURE_SETTLE_MS)
        return validTemperature(
            getValueAsync(
                KeyTools.createCameraKey(
                    DJICameraKey.KeyThermalRegionMetersureTemperature,
                    componentIndex,
                    CameraLensType.CAMERA_LENS_THERMAL,
                ),
                "thermal-region-temperature(lens)",
            )?.maxAreaTemperature,
        )
    }

    private suspend fun measureThermalSpotTemperatureWithLensKey(region: DoubleRect): Double? {
        setValueBestEffort(
            KeyTools.createCameraKey(
                DJICameraKey.KeyThermalTemperatureMeasureMode,
                componentIndex,
                CameraLensType.CAMERA_LENS_THERMAL,
            ),
            ThermalTemperatureMeasureMode.SPOT,
            "thermal-measure-mode-spot(lens)",
        )
        val center = DoublePoint2D(
            (region.x + region.width / 2.0).coerceIn(0.0, 1.0),
            (region.y + region.height / 2.0).coerceIn(0.0, 1.0),
        )
        setValueBestEffort(
            KeyTools.createCameraKey(
                DJICameraKey.KeyThermalSpotMetersurePoint,
                componentIndex,
                CameraLensType.CAMERA_LENS_THERMAL,
            ),
            center,
            "thermal-spot-point(lens)",
        )
        delay(THERMAL_MEASURE_SETTLE_MS)
        return validTemperature(
            getValueAsync(
                KeyTools.createCameraKey(
                    DJICameraKey.KeyThermalSpotMetersureTemperature,
                    componentIndex,
                    CameraLensType.CAMERA_LENS_THERMAL,
                ),
                "thermal-spot-temperature(lens)",
            ),
        )
    }

    private suspend fun measureThermalRegionTemperatureWithCameraKey(region: DoubleRect): Double? {
        setValueBestEffort(
            KeyTools.createKey(
                DJICameraKey.KeyThermalTemperatureMeasureMode,
                componentIndex,
            ),
            ThermalTemperatureMeasureMode.REGION,
            "thermal-measure-mode(camera)",
        )
        setValueBestEffort(
            KeyTools.createKey(
                DJICameraKey.KeyThermalRegionMetersureArea,
                componentIndex,
            ),
            region,
            "thermal-measure-area(camera)",
        )
        delay(THERMAL_MEASURE_SETTLE_MS)
        return validTemperature(
            getValueAsync(
                KeyTools.createKey(
                    DJICameraKey.KeyThermalRegionMetersureTemperature,
                    componentIndex,
                ),
                "thermal-region-temperature(camera)",
            )?.maxAreaTemperature,
        )
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
                componentIndex,
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
                    componentIndex,
                ),
            )
            val sourceRange = loadAvailableSources()
            val displayMode = keyManager.getValue(
                KeyTools.createCameraKey(
                    DJICameraKey.KeyThermalDisplayMode,
                    componentIndex,
                    CameraLensType.CAMERA_LENS_THERMAL,
                ),
            )
            val pipPosition = keyManager.getValue(
                KeyTools.createCameraKey(
                    DJICameraKey.KeyThermalPIPPosition,
                    componentIndex,
                    CameraLensType.CAMERA_LENS_THERMAL,
                ),
            )
            val zoomRatio = runCatching {
                keyManager.getValue(
                    KeyTools.createKey(
                        CameraKey.KeyCameraZoomRatios,
                        componentIndex,
                    ),
                )
            }.getOrNull()
            Log.i(
                tag,
                "$action streamSource=$currentSource sourceRange=$sourceRange zoomRatio=$zoomRatio thermalDisplayMode=$displayMode thermalPipPosition=$pipPosition",
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
                            ?.resumeWithException(IllegalStateException(formatDjiError(error)))
                    }
                })
            }
        }
    }

    private suspend fun <T> setValueBestEffort(
        key: dji.sdk.keyvalue.key.DJIKey<T>,
        value: T,
        action: String,
    ): Boolean {
        return runCatching { setValue(key, value) }
            .onFailure { Log.w(tag, "$action set failed: ${it.message}") }
            .isSuccess
    }

    private suspend fun <T> getValueAsync(key: dji.sdk.keyvalue.key.DJIKey<T>, action: String): T? {
        // 同步 getValue 读的是本地缓存，测温这类持续推送值大概率拿到 null；用异步回调取实时值。
        return runCatching {
            withTimeout(MSDK_CALLBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine<T?> { continuation ->
                    keyManager.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
                        override fun onSuccess(value: T?) {
                            continuation.takeIf { it.isActive }?.resume(value)
                        }

                        override fun onFailure(error: IDJIError) {
                            Log.w(tag, "$action get failed: ${formatDjiError(error)}")
                            continuation.takeIf { it.isActive }?.resume(null)
                        }
                    })
                }
            }
        }.onFailure { Log.w(tag, "$action get error: ${it.message}") }.getOrNull()
    }

    private fun formatDjiError(error: IDJIError?): String {
        if (error == null) {
            return "unknown-dji-error"
        }
        val parts = listOfNotNull(
            runCatching { error.errorType()?.toString() }.getOrNull(),
            runCatching { error.errorCode() }.getOrNull(),
            runCatching { error.innerCode() }.getOrNull(),
            runCatching { error.description() }.getOrNull(),
            runCatching { error.hint() }.getOrNull(),
        ).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "unknown-dji-error" else parts.joinToString(" | ")
    }

    companion object {
        private const val DEFAULT_VISIBLE_ZOOM_RATIO: Double = 1.0
        private const val MSDK_CALLBACK_TIMEOUT_MS: Long = 8_000
        private const val INVALID_TEMPERATURE_EPSILON: Double = 0.001
        private const val THERMAL_MEASURE_SETTLE_MS: Long = 400
        private const val VISIBLE_SNAPSHOT_POLL_MS: Long = 100
        private const val VISIBLE_SNAPSHOT_WAIT_ATTEMPTS: Int = 12
        private const val VISIBLE_SNAPSHOT_MAX_AGE_MS: Long = 3_000
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
): List<ThermalMeasureRegion> {
    val selected = mutableListOf<ThermalMeasureRegion>()
    selected += latestFrameHotspotRegions
        .clusterNearbyThermalRegions()
        .take(MAX_FRAME_HOTSPOT_MEASUREMENTS)
    if (seedRegion != null && selected.none { it.sameCell(seedRegion) }) {
        selected += seedRegion
    }
    return selected.distinctThermalRegions()
}

internal suspend fun measureThermalHotspotCandidates(
    requestedAtMs: Long,
    latestFrameHotspotRegions: List<ThermalMeasureRegion>,
    seedRegion: ThermalMeasureRegion?,
    measureTemperatureC: suspend (ThermalMeasureRegion) -> Double?,
    latestSnapshotPath: (minTimestampMs: Long) -> String?,
): ThermalMeasurementResult? {
    val candidates = selectThermalHotspotRegions(
        latestFrameHotspotRegions = latestFrameHotspotRegions,
        seedRegion = seedRegion,
    )
    if (candidates.isEmpty()) {
        return null
    }
    val measured = mutableListOf<ThermalMeasuredPoint>()
    for (region in candidates) {
        val temperature = measureTemperatureC(region) ?: continue
        val point = ThermalMeasuredPoint(temperature, region)
        measured += point
        if (shouldFastConfirmThermalHotspot(temperature)) {
            return ThermalMeasurementResult(
                temperatureC = point.temperatureC,
                region = point.region,
                thermalSnapshotPath = latestSnapshotPath(requestedAtMs - THERMAL_SNAPSHOT_CLOCK_SKEW_MS),
                measurements = measured.sortedByDescending { it.temperatureC },
            )
        }
    }
    val best = measured.maxByOrNull { it.temperatureC } ?: return null
    return ThermalMeasurementResult(
        temperatureC = best.temperatureC,
        region = best.region,
        thermalSnapshotPath = latestSnapshotPath(requestedAtMs - THERMAL_SNAPSHOT_CLOCK_SKEW_MS),
        measurements = measured.sortedByDescending { it.temperatureC },
    )
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

internal fun ThermalMeasureRegion.centerWithinRegion(
    roi: ThermalMeasureRegion,
    margin: Double,
): Boolean {
    val cx = x + width / 2
    val cy = y + height / 2
    return cx >= roi.x - margin && cx <= roi.x + roi.width + margin &&
        cy >= roi.y - margin && cy <= roi.y + roi.height + margin
}

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
private const val MAX_FRAME_HOTSPOT_MEASUREMENTS = 3
private const val FAST_CONFIRM_TEMPERATURE_C = 120.0
// YOLO 框与探针亮块的坐标各有换算误差，框沿外扩一点再判"亮块在框内"
internal const val HOTSPOT_IN_ROI_MARGIN = 0.05
private const val THERMAL_SNAPSHOT_CLOCK_SKEW_MS: Long = 1_000

private fun ThermalMeasureRegion.toDoubleRect(): DoubleRect {
    val normalizedX = x.coerceIn(0.0, 0.99)
    val normalizedY = y.coerceIn(0.0, 0.99)
    val normalizedWidth = width.coerceIn(0.01, 1.0 - normalizedX)
    val normalizedHeight = height.coerceIn(0.01, 1.0 - normalizedY)
    return DoubleRect(normalizedX, normalizedY, normalizedWidth, normalizedHeight)
}
