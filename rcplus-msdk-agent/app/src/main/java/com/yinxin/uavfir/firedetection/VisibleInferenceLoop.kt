package com.yinxin.uavfir.firedetection

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/**
 * A single sequential consumer capped at 5 FPS. Detector work never runs on
 * the MSDK callback; only this coroutine takes ownership from the buffer.
 */
class VisibleInferenceLoop(
    private val buffer: LatestVisibleFrameBuffer,
    private val detector: VisibleFireDetector,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    private val processing = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile
    private var metrics = VisibleInferenceMetrics()
    private val mutableHealth = MutableStateFlow(metrics)
    val health: StateFlow<VisibleInferenceMetrics> = mutableHealth.asStateFlow()
    @Volatile
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        check(!closed.get()) { "Visible inference loop is closed" }
        if (!started.compareAndSet(false, true)) return
        job = scope.launch {
            while (isActive) {
                processLatest()
                delay(TARGET_INTERVAL_MILLIS)
            }
        }
    }

    internal suspend fun processLatest(): Boolean {
        if (closed.get()) return false
        if (!processing.compareAndSet(false, true)) {
            publish(metrics.copy(busySkippedCycles = metrics.busySkippedCycles + 1))
            return false
        }
        try {
            val frame = buffer.takeLatest() ?: return false
            try {
                val startedAt = nowMillis()
                val age = (startedAt - frame.capturedAtMillis).coerceAtLeast(0L)
                publish(metrics.copy(
                    lastCapturedAtMillis = frame.capturedAtMillis,
                    lastFrameAgeMillis = age,
                ))
                if (age > MAX_FRAME_AGE_MILLIS) {
                    publish(metrics.copy(
                        status = VisibleInferenceStatus.DEGRADED,
                        staleRejectedFrames = metrics.staleRejectedFrames + 1,
                    ))
                    return true
                }
                publish(metrics.copy(
                    lastStartedAtMillis = startedAt,
                    inFlight = true,
                    lastFailure = null,
                ))
                detector.detect(frame)
                publish(metrics.copy(
                    status = VisibleInferenceStatus.HEALTHY,
                    lastCompletedAtMillis = nowMillis(),
                    completedInferences = metrics.completedInferences + 1,
                    inFlight = false,
                ))
            } catch (cancelled: CancellationException) {
                publish(metrics.copy(inFlight = false))
                throw cancelled
            } catch (error: Exception) {
                publish(metrics.copy(
                    status = VisibleInferenceStatus.DEGRADED,
                    lastCompletedAtMillis = nowMillis(),
                    failedInferences = metrics.failedInferences + 1,
                    inFlight = false,
                    lastFailure = error.message ?: error::class.java.simpleName,
                ))
            } finally {
                frame.release()
            }
            return true
        } finally {
            processing.set(false)
        }
    }

    fun snapshot(): VisibleInferenceMetrics = metrics

    private fun publish(value: VisibleInferenceMetrics) {
        metrics = value
        mutableHealth.value = value
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
        buffer.close()
        val closeFailure = runCatching(detector::close).exceptionOrNull()
        publish(
            metrics.copy(
                status = VisibleInferenceStatus.CLOSED,
                inFlight = false,
                lastFailure = closeFailure?.message ?: closeFailure?.javaClass?.simpleName,
            ),
        )
    }

    companion object {
        const val TARGET_FPS = 5
        const val TARGET_INTERVAL_MILLIS = 1_000L / TARGET_FPS
        const val MAX_FRAME_AGE_MILLIS = 300L
    }
}
