package com.yinxin.uavfir.firedetection

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnDeviceFireDetectionCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val enabled: Boolean,
    private val reporter: VisibleDetectionReporter,
    private val engineFactory: () -> VisibleFireDetectionEngine = { OnnxVisibleFireDetector(context.applicationContext) },
    private val calibrationSink: FireCalibrationSink = FireCalibrationRecorder(context.applicationContext),
    private val inferenceIntervalMs: Long = 400L,
) : VisibleFrameConsumer, VisibleAiControl, AutoCloseable {
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

    override suspend fun start(droneSn: String): VisibleAiControlResult {
        if (!enabled) return VisibleAiControlResult(false, "agent-fire-onnx-disabled")
        if (droneSn.isBlank()) return VisibleAiControlResult(false, "drone-sn-required")
        val prepared = runCatching {
            withContext(Dispatchers.Default) {
                ensureEngine().prepare()
            }
        }.onFailure {
            Log.e(TAG, "agent ONNX initialization failed", it)
        }
        if (prepared.isFailure) {
            return VisibleAiControlResult(false, "agent-fire-onnx-prepare-failed")
        }
        synchronized(lock) {
            generation += 1
            activeSession = Session(droneSn, "fire-$droneSn", generation)
            pendingFrame = null
            lastAcceptedAtMs = Long.MIN_VALUE
            tracker.reset()
        }
        return VisibleAiControlResult(true, "agent-fire-onnx-enabled")
    }

    override suspend fun stop(droneSn: String): VisibleAiControlResult {
        synchronized(lock) {
            val current = activeSession
            if (current != null && (droneSn.isBlank() || current.droneSn == droneSn)) {
                generation += 1
                activeSession = null
                pendingFrame = null
                tracker.reset()
            }
        }
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
            val owned = OwnedFrame(
                data = data.copyOfRange(offset, offset + expectedLength),
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
            pendingFrame = null
            tracker.reset()
        }
        runCatching { engine?.close() }
        engine = null
    }

    private suspend fun drainFrames(first: OwnedFrame) {
        var current: OwnedFrame? = first
        while (current != null) {
            processFrame(current)
            current = synchronized(lock) {
                pendingFrame.also {
                    pendingFrame = null
                    if (it == null) inferenceRunning = false
                }
            }
        }
    }

    private suspend fun processFrame(frame: OwnedFrame) {
        val result = runCatching {
            withContext(Dispatchers.Default) {
                ensureEngine().detect(frame.data, frame.width, frame.height)
            }
        }.onFailure {
            Log.e(TAG, "agent ONNX inference failed", it)
        }.getOrNull() ?: return
        val confirmed = tracker.accept(result.detections, frame.timestampMs)
        calibrationSink.record(
            frame.session.droneSn,
            frame.session.taskId,
            frame.timestampMs,
            result,
            confirmed,
        )
        confirmed ?: return
        val stillActive = synchronized(lock) {
            activeSession?.generation == frame.session.generation
        }
        if (!stillActive) return
        runCatching {
            val evidence = withContext(Dispatchers.Default) {
                FireEvidenceEncoder.encodeRgba(frame.data, frame.width, frame.height)
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

    private companion object {
        const val TAG = "OnDeviceFireDetection"
    }
}
