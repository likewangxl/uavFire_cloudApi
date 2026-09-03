package com.yinxin.uavfir.firedetection

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnDeviceFireDetectionCoordinator private constructor(
    private val scope: CoroutineScope,
    private val enabled: Boolean,
    private val reporter: VisibleDetectionReporter,
    private val engineFactory: () -> VisibleFireDetectionEngine,
    private val calibrationSink: FireCalibrationSink,
    private val observationSink: FireDetectionObservationSink,
    private val inferenceIntervalMs: Long,
    @Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) : VisibleFrameConsumer, VisibleAiControl, AutoCloseable {
    constructor(
        context: Context,
        scope: CoroutineScope,
        enabled: Boolean,
        reporter: VisibleDetectionReporter,
    ) : this(
        scope = scope,
        enabled = enabled,
        reporter = reporter,
        engineFactory = { OnnxVisibleFireDetector(context.applicationContext) },
        calibrationSink = FireCalibrationRecorder(context.applicationContext),
        observationSink = FireDetectionObservationBus,
        inferenceIntervalMs = 400L,
        constructionMarker = Unit,
    )

    internal constructor(
        scope: CoroutineScope,
        enabled: Boolean,
        reporter: VisibleDetectionReporter,
        engineFactory: () -> VisibleFireDetectionEngine,
        calibrationSink: FireCalibrationSink,
        observationSink: FireDetectionObservationSink = FireDetectionObservationBus,
        inferenceIntervalMs: Long = 400L,
    ) : this(
        scope = scope,
        enabled = enabled,
        reporter = reporter,
        engineFactory = engineFactory,
        calibrationSink = calibrationSink,
        observationSink = observationSink,
        inferenceIntervalMs = inferenceIntervalMs,
        constructionMarker = Unit,
    )

    private data class Session(val droneSn: String, val taskId: String, val generation: Long)
    private data class OwnedFrame(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val timestampMs: Long,
        val session: Session,
    )

    private val lock = Any()
    private val tracker = VisibleDetectionTracker()
    private var activeSession: Session? = null
    private var generation = 0L
    private var inferenceRunning = false
    private var pendingFrame: OwnedFrame? = null
    private var lastAcceptedAtMs = Long.MIN_VALUE
    private var engine: VisibleFireDetectionEngine? = null
    private val reusableFrameBuffers = ArrayList<ByteArray>(MAX_REUSABLE_FRAME_BUFFERS)

    init {
        publishObservation(active = false)
    }

    override suspend fun start(droneSn: String): VisibleAiControlResult {
        if (!enabled) {
            publishObservation(active = false, failureMessage = "agent-fire-onnx-disabled")
            return VisibleAiControlResult(false, "agent-fire-onnx-disabled")
        }
        if (droneSn.isBlank()) {
            publishObservation(active = false, failureMessage = "drone-sn-required")
            return VisibleAiControlResult(false, "drone-sn-required")
        }
        val prepared = runCatching {
            withContext(Dispatchers.Default) {
                ensureEngine().prepare()
            }
        }.onFailure {
            Log.e(TAG, "agent ONNX initialization failed", it)
        }
        if (prepared.isFailure) {
            publishObservation(active = false, failureMessage = "agent-fire-onnx-prepare-failed")
            return VisibleAiControlResult(false, "agent-fire-onnx-prepare-failed")
        }
        synchronized(lock) {
            generation += 1
            activeSession = Session(droneSn, "fire-$droneSn", generation)
            pendingFrame?.let { recycleFrameBufferLocked(it.data) }
            pendingFrame = null
            lastAcceptedAtMs = Long.MIN_VALUE
            tracker.reset()
        }
        publishObservation(active = true)
        return VisibleAiControlResult(true, "agent-fire-onnx-enabled")
    }

    override suspend fun stop(droneSn: String): VisibleAiControlResult {
        synchronized(lock) {
            val current = activeSession
            if (current != null && (droneSn.isBlank() || current.droneSn == droneSn)) {
                generation += 1
                activeSession = null
                pendingFrame?.let { recycleFrameBufferLocked(it.data) }
                pendingFrame = null
                tracker.reset()
            }
        }
        publishObservation(active = false)
        return VisibleAiControlResult(true, "agent-fire-onnx-disabled")
    }

    override fun onVisibleRgbaFrame(
        data: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        timestampMs: Long,
    ) {
        if (!enabled || width <= 0 || height <= 0) return
        val expectedLength = width * height * 4
        if (offset < 0 || length < expectedLength || offset + expectedLength > data.size) return
        var launchFrame: OwnedFrame? = null
        synchronized(lock) {
            val session = activeSession ?: return
            if (lastAcceptedAtMs != Long.MIN_VALUE && timestampMs - lastAcceptedAtMs < inferenceIntervalMs) return
            lastAcceptedAtMs = timestampMs
            val replaceablePending = pendingFrame.takeIf { inferenceRunning }
            val frameBuffer = if (replaceablePending?.data?.size == expectedLength) {
                replaceablePending.data
            } else {
                replaceablePending?.let { recycleFrameBufferLocked(it.data) }
                takeFrameBufferLocked(expectedLength)
            }
            System.arraycopy(data, offset, frameBuffer, 0, expectedLength)
            val owned = OwnedFrame(
                data = frameBuffer,
                width = width,
                height = height,
                timestampMs = timestampMs,
                session = session,
            )
            if (inferenceRunning) {
                pendingFrame = owned
            } else {
                inferenceRunning = true
                launchFrame = owned
            }
        }
        launchFrame?.let { first -> scope.launch { drainFrames(first) } }
    }

    override fun close() {
        synchronized(lock) {
            generation += 1
            activeSession = null
            pendingFrame?.let { recycleFrameBufferLocked(it.data) }
            pendingFrame = null
            reusableFrameBuffers.clear()
            tracker.reset()
        }
        runCatching { engine?.close() }
        engine = null
        publishObservation(active = false)
    }

    private suspend fun drainFrames(first: OwnedFrame) {
        var current: OwnedFrame? = first
        while (current != null) {
            val processed = current
            try {
                processFrame(processed)
            } finally {
                current = synchronized(lock) {
                    recycleFrameBufferLocked(processed.data)
                    pendingFrame.also {
                        pendingFrame = null
                        if (it == null) inferenceRunning = false
                    }
                }
            }
        }
    }

    private fun takeFrameBufferLocked(requiredSize: Int): ByteArray {
        val reusableIndex = reusableFrameBuffers.indexOfFirst { it.size == requiredSize }
        return if (reusableIndex >= 0) reusableFrameBuffers.removeAt(reusableIndex) else ByteArray(requiredSize)
    }

    private fun recycleFrameBufferLocked(buffer: ByteArray) {
        if (reusableFrameBuffers.size < MAX_REUSABLE_FRAME_BUFFERS && reusableFrameBuffers.none { it === buffer }) {
            reusableFrameBuffers += buffer
        }
    }

    private suspend fun processFrame(frame: OwnedFrame) {
        val result = runCatching {
            withContext(Dispatchers.Default) {
                ensureEngine().detect(frame.data, frame.width, frame.height)
            }
        }.onFailure {
            Log.e(TAG, "agent ONNX inference failed", it)
            if (isSessionActive(frame.session)) {
                publishObservation(
                    active = true,
                    sourceWidth = frame.width,
                    sourceHeight = frame.height,
                    sourceTs = frame.timestampMs,
                    failureMessage = it.message ?: "agent-onnx-inference-failed",
                )
            }
        }.getOrNull() ?: return
        if (!isSessionActive(frame.session)) return
        publishObservation(
            active = true,
            sourceWidth = frame.width,
            sourceHeight = frame.height,
            sourceTs = frame.timestampMs,
            inferenceMs = result.inferenceMs,
            detections = result.detections,
        )
        val confirmed = tracker.accept(result.detections, frame.timestampMs)
        calibrationSink.record(
            frame.session.droneSn,
            frame.session.taskId,
            frame.timestampMs,
            result,
            confirmed,
        )
        confirmed ?: return
        if (!isSessionActive(frame.session)) return
        runCatching {
            val evidence = withContext(Dispatchers.Default) {
                FireEvidenceEncoder.encodeRgba(
                    rgba = frame.data,
                    width = frame.width,
                    height = frame.height,
                    detections = listOf(confirmed),
                )
            }
            reporter.report(
                VisibleDetectionReport(
                    taskId = frame.session.taskId,
                    droneSn = frame.session.droneSn,
                    sourceTs = frame.timestampMs,
                    detection = confirmed,
                    inferenceMs = result.inferenceMs,
                    modelVersion = AgentFireModelSpec.MODEL_VERSION,
                    modelSha256 = AgentFireModelSpec.MODEL_SHA256,
                    evidenceJpeg = evidence.jpeg,
                    evidenceSha256 = evidence.sha256,
                    evidenceCapturedAt = frame.timestampMs,
                ),
            )
            tracker.markReported(frame.timestampMs)
        }.onFailure {
            Log.e(TAG, "agent ONNX detection outbox enqueue failed", it)
        }
    }

    private fun ensureEngine(): VisibleFireDetectionEngine = synchronized(lock) {
        engine ?: engineFactory().also { engine = it }
    }

    private fun isSessionActive(session: Session): Boolean = synchronized(lock) {
        activeSession?.generation == session.generation
    }

    private fun publishObservation(
        active: Boolean,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
        sourceTs: Long = 0L,
        inferenceMs: Long? = null,
        detections: List<VisibleDetection> = emptyList(),
        failureMessage: String? = null,
    ) {
        observationSink.publish(
            FireDetectionObservation(
                enabled = enabled,
                active = active,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                sourceTs = sourceTs,
                inferenceMs = inferenceMs,
                detections = detections,
                failureMessage = failureMessage,
            ),
        )
    }

    private companion object {
        const val TAG = "OnDeviceFireDetection"
        const val MAX_REUSABLE_FRAME_BUFFERS = 2
    }
}
