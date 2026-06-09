package com.yinxin.uavfir.stream

import android.graphics.Bitmap
import android.util.Log
import com.yinxin.uavfir.AppContextHolder
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.camera.StreamInfo
import dji.v5.manager.interfaces.ICameraStreamManager
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ThermalFrameProbe(
    private val componentIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN,
    private val keyManager: KeyManager = KeyManager.getInstance(),
    private val cameraStreamManager: ICameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager,
    private val hotspotDetector: ThermalHotspotFrameDetector = ThermalHotspotFrameDetector(),
) {
    private val running = AtomicBoolean(false)
    private val saveExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "thermal-frame-probe-writer").apply { isDaemon = true }
    }
    private var streamFrameCount = 0
    private var decodedFrameCount = 0
    private var lastStatsAtMs = 0L
    private var lastThermalSavedAtMs = 0L
    private var lastVisibleSavedAtMs = 0L
    private var lastDetectedAtMs = 0L
    private var savedCount = 0
    private val immediateVisibleSnapshotRequestedAtMs = AtomicLong(0L)
    @Volatile
    private var latestHotspot: TimedHotspot? = null
    @Volatile
    private var latestThermalSnapshot: TimedSnapshot? = null
    @Volatile
    private var latestVisibleSnapshot: TimedSnapshot? = null

    private val receiveStreamListener = ICameraStreamManager.ReceiveStreamListener { data, offset, length, info ->
        onReceiveStream(data, offset, length, info)
    }

    private val frameListener = ICameraStreamManager.CameraFrameListener { frameData, offset, length, width, height, format ->
        onFrame(frameData, offset, length, width, height, format)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        runCatching {
            cameraStreamManager.addReceiveStreamListener(componentIndex, receiveStreamListener)
            cameraStreamManager.addFrameListener(
                componentIndex,
                ICameraStreamManager.FrameFormat.RGBA_8888,
                frameListener,
            )
            Log.i(TAG, "started component=$componentIndex sampleDir=${sampleDir().absolutePath}")
        }.onFailure {
            running.set(false)
            Log.w(TAG, "start failed: ${it.message}", it)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        runCatching { cameraStreamManager.removeReceiveStreamListener(receiveStreamListener) }
            .onFailure { Log.w(TAG, "remove receive listener failed: ${it.message}", it) }
        runCatching { cameraStreamManager.removeFrameListener(frameListener) }
            .onFailure { Log.w(TAG, "remove frame listener failed: ${it.message}", it) }
        Log.i(TAG, "stopped")
    }

    fun latestHotspotRegion(maxAgeMs: Long = HOTSPOT_MAX_AGE_MS): ThermalMeasureRegion? {
        val hotspot = latestHotspot ?: return null
        return hotspot.region.takeIf { System.currentTimeMillis() - hotspot.timestampMs <= maxAgeMs }
    }

    fun latestHotspotRegions(maxAgeMs: Long = HOTSPOT_MAX_AGE_MS): List<ThermalMeasureRegion> {
        val hotspot = latestHotspot ?: return emptyList()
        if (System.currentTimeMillis() - hotspot.timestampMs > maxAgeMs) {
            return emptyList()
        }
        return hotspot.regions
    }

    fun latestSnapshotPath(
        minTimestampMs: Long = 0L,
        maxAgeMs: Long = SNAPSHOT_MAX_AGE_MS,
    ): String? {
        val snapshot = latestThermalSnapshot ?: return null
        return snapshot.path.takeIf {
            snapshot.timestampMs >= minTimestampMs && System.currentTimeMillis() - snapshot.timestampMs <= maxAgeMs
        }
    }

    fun latestVisibleSnapshotPath(
        minTimestampMs: Long = 0L,
        maxAgeMs: Long = SNAPSHOT_MAX_AGE_MS,
    ): String? {
        val snapshot = latestVisibleSnapshot ?: return null
        val now = System.currentTimeMillis()
        return snapshot.path.takeIf {
            snapshot.timestampMs >= minTimestampMs && now - snapshot.timestampMs <= maxAgeMs
        }
    }

    fun requestImmediateVisibleSnapshot(): Long {
        val requestedAtMs = System.currentTimeMillis()
        immediateVisibleSnapshotRequestedAtMs.set(requestedAtMs)
        return requestedAtMs
    }

    private fun onReceiveStream(
        data: ByteArray?,
        offset: Int,
        length: Int,
        info: StreamInfo?,
    ) {
        if (!running.get()) {
            return
        }
        streamFrameCount += 1
        maybeLogStats(
            encodedLength = length,
            encodedOffset = offset,
            streamInfo = info,
            rawDataSize = data?.size ?: 0,
        )
    }

    private fun onFrame(
        frameData: ByteArray?,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        format: ICameraStreamManager.FrameFormat,
    ) {
        if (!running.get() || frameData == null) {
            return
        }
        decodedFrameCount += 1
        maybeLogStats(
            decodedWidth = width,
            decodedHeight = height,
            decodedLength = length,
            decodedOffset = offset,
            decodedFormat = format,
        )
        val now = System.currentTimeMillis()
        val source = currentSource()
        if (format != ICameraStreamManager.FrameFormat.RGBA_8888 || width <= 0 || height <= 0) {
            return
        }
        val expectedLength = width * height * BYTES_PER_RGBA_PIXEL
        if (length < expectedLength || offset < 0 || offset + expectedLength > frameData.size) {
            Log.w(
                TAG,
                "skip invalid frame source=$source width=$width height=$height offset=$offset length=$length dataSize=${frameData.size}",
            )
            return
        }
        if (source == CameraVideoStreamSourceType.INFRARED_CAMERA &&
            !ThermalFrameClassifier.looksLikeThermalFrame(frameData, offset, expectedLength, width, height)
        ) {
            Log.w(TAG, "skip thermal sample because frame looks visible width=$width height=$height timestamp=$now")
            return
        }
        if (source == CameraVideoStreamSourceType.INFRARED_CAMERA) {
            maybeDetectHotspot(frameData, offset, expectedLength, width, height, now)
        }
        val kind = if (source == CameraVideoStreamSourceType.INFRARED_CAMERA) "thermal" else "visible"
        val immediateVisibleSnapshot = kind == "visible" && consumeImmediateVisibleSnapshotRequest(now)
        val lastSavedAtMs = if (kind == "thermal") lastThermalSavedAtMs else lastVisibleSavedAtMs
        if (!immediateVisibleSnapshot && now - lastSavedAtMs < SAMPLE_INTERVAL_MS) {
            return
        }
        if (kind == "thermal") {
            lastThermalSavedAtMs = now
        } else {
            lastVisibleSavedAtMs = now
        }
        val copy = frameData.copyOfRange(offset, offset + expectedLength)
        saveExecutor.execute {
            saveRgbaFrame(
                data = copy,
                width = width,
                height = height,
                timestampMs = now,
                kind = kind,
            )
        }
    }

    private fun consumeImmediateVisibleSnapshotRequest(now: Long): Boolean {
        val requestedAtMs = immediateVisibleSnapshotRequestedAtMs.get()
        return requestedAtMs > 0L &&
            now >= requestedAtMs &&
            immediateVisibleSnapshotRequestedAtMs.compareAndSet(requestedAtMs, 0L)
    }

    private fun maybeDetectHotspot(
        frameData: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        now: Long,
    ) {
        if (now - lastDetectedAtMs < HOTSPOT_DETECT_INTERVAL_MS) {
            return
        }
        lastDetectedAtMs = now
        val results = hotspotDetector.detectHotspots(frameData, offset, length, width, height)
        if (results.isEmpty()) {
            latestHotspot = null
            Log.i(TAG, "hotspot none source=${currentSource()} width=$width height=$height")
            return
        }
        val result = results.first()
        latestHotspot = TimedHotspot(results.map { it.region }, now)
        Log.i(
            TAG,
            "hotspot rois=${results.map { it.region }} max=${result.maxBrightness} threshold=${result.threshold} " +
                "areaRatio=${"%.4f".format(result.componentAreaRatio)} detectCostMs=${result.detectCostMs}",
        )
    }

    private fun maybeLogStats(
        encodedLength: Int? = null,
        encodedOffset: Int? = null,
        streamInfo: StreamInfo? = null,
        rawDataSize: Int? = null,
        decodedWidth: Int? = null,
        decodedHeight: Int? = null,
        decodedLength: Int? = null,
        decodedOffset: Int? = null,
        decodedFormat: ICameraStreamManager.FrameFormat? = null,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastStatsAtMs < STATS_INTERVAL_MS) {
            return
        }
        val elapsedMs = (now - lastStatsAtMs).takeIf { lastStatsAtMs > 0 } ?: STATS_INTERVAL_MS
        val streamFps = streamFrameCount * 1000.0 / elapsedMs
        val decodedFps = decodedFrameCount * 1000.0 / elapsedMs
        Log.i(
            TAG,
            "stats source=${currentSource()} streamFps=${"%.1f".format(streamFps)} decodedFps=${"%.1f".format(decodedFps)} " +
                "encoded=${encodedLength ?: "-"} encodedOffset=${encodedOffset ?: "-"} rawDataSize=${rawDataSize ?: "-"} " +
                "streamInfo=${streamInfo ?: "-"} decoded=${decodedWidth ?: "-"}x${decodedHeight ?: "-"} " +
                "decodedLength=${decodedLength ?: "-"} decodedOffset=${decodedOffset ?: "-"} decodedFormat=${decodedFormat ?: "-"}",
        )
        streamFrameCount = 0
        decodedFrameCount = 0
        lastStatsAtMs = now
    }

    private fun saveRgbaFrame(
        data: ByteArray,
        width: Int,
        height: Int,
        timestampMs: Long,
        kind: String,
    ) {
        runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data))
            val dir = sampleDir(kind).also { it.mkdirs() }
            pruneOldSamples(dir, kind)
            val file = File(dir, "$kind-frame-$timestampMs.jpg")
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            bitmap.recycle()
            savedCount += 1
            val snapshot = TimedSnapshot(file.absolutePath, timestampMs)
            if (kind == "thermal") {
                latestThermalSnapshot = snapshot
            } else {
                latestVisibleSnapshot = snapshot
            }
            Log.i(TAG, "saved $kind sample file=${file.absolutePath} width=$width height=$height bytes=${data.size}")
        }.onFailure {
            Log.w(TAG, "save $kind sample failed: ${it.message}", it)
        }
    }

    private fun pruneOldSamples(dir: File, kind: String) {
        val samples = dir.listFiles { file -> file.name.startsWith("$kind-frame-") && file.name.endsWith(".jpg") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        samples.drop(MAX_SAMPLE_FILES - 1).forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun currentSource(): CameraVideoStreamSourceType? {
        return runCatching {
            keyManager.getValue(
                KeyTools.createKey(
                    CameraKey.KeyCameraVideoStreamSource,
                    componentIndex,
                ),
            )
        }.getOrNull()
    }

    private fun sampleDir(kind: String = "thermal"): File {
        val root = AppContextHolder.get()?.getExternalFilesDir(null)
            ?: AppContextHolder.get()?.filesDir
            ?: File("/sdcard/Android/data/com.yinxin.uavfir/files")
        return File(root, "probe/$kind-frames")
    }

    companion object {
        private const val TAG = "ThermalFrameProbe"
        private const val STATS_INTERVAL_MS = 1_000L
        private const val SAMPLE_INTERVAL_MS = 2_000L
        private const val JPEG_QUALITY = 92
        private const val BYTES_PER_RGBA_PIXEL = 4
        private const val MAX_SAMPLE_FILES = 20
        private const val HOTSPOT_DETECT_INTERVAL_MS = 500L
        private const val HOTSPOT_MAX_AGE_MS = 2_500L
        private const val SNAPSHOT_MAX_AGE_MS = 5_000L
    }

    private data class TimedHotspot(
        val regions: List<ThermalMeasureRegion>,
        val timestampMs: Long,
    ) {
        val region: ThermalMeasureRegion
            get() = regions.first()
    }

    private data class TimedSnapshot(
        val path: String,
        val timestampMs: Long,
    )
}
