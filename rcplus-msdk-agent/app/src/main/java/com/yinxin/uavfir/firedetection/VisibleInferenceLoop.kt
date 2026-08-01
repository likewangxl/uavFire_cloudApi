package com.yinxin.uavfir.firedetection

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class VisibleInferenceStatus {
    IDLE,
    HEALTHY,
    DEGRADED,
    CLOSED,
}

data class VisibleInferenceMetrics(
    val status: VisibleInferenceStatus = VisibleInferenceStatus.IDLE,
    val lastCapturedAtMillis: Long? = null,
    val lastStartedAtMillis: Long? = null,
    val lastCompletedAtMillis: Long? = null,
    val lastFrameAgeMillis: Long? = null,
    val completedInferences: Long = 0,
    val staleRejectedFrames: Long = 0,
    val failedInferences: Long = 0,
    val busySkippedCycles: Long = 0,
    val inFlight: Boolean = false,
    val lastFailure: String? = null,
)

internal data class VisibleInferenceLoopHooks(
    val beforeJobPublication: () -> Unit = {},
)

fun interface VisibleInferenceConfirmationObserver {
    /** Called on the inference worker while [frame] is still valid. Must not retain it. */
    fun onInference(frame: VisibleRgbaFrame, result: VisibleDetectionResult, completedAtMillis: Long)

    companion object {
        val NO_OP = VisibleInferenceConfirmationObserver { _, _, _ -> }
    }
}

/**
 * Single sequential consumer capped at 5 FPS. Lifecycle publication is
 * linearized under [lifecycleMonitor]; detector/metric work stays outside it.
 */
class VisibleInferenceLoop internal constructor(
    private val buffer: LatestVisibleFrameBuffer,
    private val detector: VisibleFireDetector,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val hooks: VisibleInferenceLoopHooks = VisibleInferenceLoopHooks(),
    private val resultPublisher: VisibleInferenceResultPublisher =
        VisibleInferenceResultPublisher.NO_OP,
    private val confirmationObserver: VisibleInferenceConfirmationObserver =
        VisibleInferenceConfirmationObserver.NO_OP,
) : AutoCloseable {
    private val processing = AtomicBoolean(false)
    private val lifecycleMonitor = Any()
    private var closed = false
    private var job: Job? = null
    private val mutableHealth = MutableStateFlow(VisibleInferenceMetrics())
    val health: StateFlow<VisibleInferenceMetrics> = mutableHealth.asStateFlow()

    fun start(scope: CoroutineScope) {
        synchronized(lifecycleMonitor) {
            check(!closed) { "Visible inference loop is closed" }
            if (job != null) return
            val newJob = scope.launch(start = CoroutineStart.LAZY) {
                while (isActive && !isClosed()) {
                    processLatest()
                    delay(TARGET_INTERVAL_MILLIS)
                }
            }
            hooks.beforeJobPublication()
            job = newJob
            if (closed) {
                newJob.cancel()
            } else {
                newJob.start()
            }
        }
    }

    internal suspend fun processLatest(): Boolean {
        if (isClosed()) return false
        if (!processing.compareAndSet(false, true)) {
            updateMetrics { it.copy(busySkippedCycles = it.busySkippedCycles + 1) }
            return false
        }
        try {
            if (isClosed()) return false
            val frame = buffer.takeLatest() ?: return false
            try {
                val startedAt = nowMillis()
                val age = (startedAt - frame.capturedAtMillis).coerceAtLeast(0L)
                updateMetrics {
                    it.copy(
                        lastCapturedAtMillis = frame.capturedAtMillis,
                        lastFrameAgeMillis = age,
                    )
                }
                if (age > MAX_FRAME_AGE_MILLIS) {
                    updateMetrics {
                        it.copy(
                            status = VisibleInferenceStatus.DEGRADED,
                            staleRejectedFrames = it.staleRejectedFrames + 1,
                        )
                    }
                    return true
                }
                updateMetrics {
                    it.copy(
                        lastStartedAtMillis = startedAt,
                        inFlight = true,
                        lastFailure = null,
                    )
                }
                val result = detector.detect(frame)
                if (result.frameCapturedAtMillis != frame.capturedAtMillis) {
                    error("detector-result-frame-timestamp-mismatch")
                }
                val completedAt = nowMillis()
                confirmationObserver.onInference(frame, result, completedAt)
                resultPublisher.publish(
                    VisibleInferencePublication(
                        sourceGeneration = frame.sourceGeneration,
                        capturedAtMonotonicMs = frame.capturedAtMillis,
                        startedAtMonotonicMs = startedAt,
                        completedAtMonotonicMs = completedAt,
                        outcome = VisibleInferenceOutcome.SUCCESS,
                        health = VisibleInferenceStatus.HEALTHY,
                        detections = result.detections,
                        failure = null,
                    ),
                )
                updateMetrics {
                    it.copy(
                        status = VisibleInferenceStatus.HEALTHY,
                        lastCompletedAtMillis = completedAt,
                        completedInferences = it.completedInferences + 1,
                        inFlight = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                updateMetrics { it.copy(inFlight = false) }
                throw cancelled
            } catch (error: Exception) {
                val completedAt = nowMillis()
                val failure = error.message ?: error::class.java.simpleName
                val capturedAt = frame.capturedAtMillis
                val startedAt = snapshot().lastStartedAtMillis ?: completedAt
                if (frame.sourceGeneration > 0 && capturedAt <= startedAt && startedAt <= completedAt) {
                    resultPublisher.publish(
                        VisibleInferencePublication(
                            sourceGeneration = frame.sourceGeneration,
                            capturedAtMonotonicMs = capturedAt,
                            startedAtMonotonicMs = startedAt,
                            completedAtMonotonicMs = completedAt,
                            outcome = VisibleInferenceOutcome.FAILURE,
                            health = VisibleInferenceStatus.DEGRADED,
                            detections = emptyList(),
                            failure = failure,
                        ),
                    )
                }
                updateMetrics {
                    it.copy(
                        status = VisibleInferenceStatus.DEGRADED,
                        lastCompletedAtMillis = completedAt,
                        failedInferences = it.failedInferences + 1,
                        inFlight = false,
                        lastFailure = failure,
                    )
                }
            } finally {
                frame.release()
            }
            return true
        } finally {
            processing.set(false)
        }
    }

    fun snapshot(): VisibleInferenceMetrics = mutableHealth.value

    private fun updateMetrics(transform: (VisibleInferenceMetrics) -> VisibleInferenceMetrics) {
        mutableHealth.update { current ->
            if (current.status == VisibleInferenceStatus.CLOSED) current else transform(current)
        }
    }

    private fun isClosed(): Boolean = synchronized(lifecycleMonitor) { closed }

    internal fun hasLiveJobForTesting(): Boolean =
        synchronized(lifecycleMonitor) { job?.isActive == true }

    override fun close() {
        val publishedJob = synchronized(lifecycleMonitor) {
            if (closed) return
            closed = true
            job
        }
        publishedJob?.cancel()
        buffer.close()
        val closeFailure = runCatching(detector::close).exceptionOrNull()
        mutableHealth.update {
            it.copy(
                status = VisibleInferenceStatus.CLOSED,
                inFlight = false,
                lastFailure = closeFailure?.message ?: closeFailure?.javaClass?.simpleName,
            )
        }
    }

    companion object {
        const val TARGET_FPS = 5
        const val TARGET_INTERVAL_MILLIS = 1_000L / TARGET_FPS
        const val MAX_FRAME_AGE_MILLIS = 300L
    }
}
