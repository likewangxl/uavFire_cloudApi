package com.yinxin.uavfir.firedetection

import java.util.concurrent.CopyOnWriteArraySet

data class FireDetectionObservation(
    val enabled: Boolean,
    val active: Boolean,
    val modelVersion: String = AgentFireModelSpec.MODEL_VERSION,
    val modelInputSize: Int = AgentFireModelSpec.INPUT_SIZE,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val sourceTs: Long = 0L,
    val inferenceMs: Long? = null,
    val detections: List<VisibleDetection> = emptyList(),
    val failureMessage: String? = null,
)

fun interface FireDetectionObservationSink {
    fun publish(observation: FireDetectionObservation)
}

fun interface FireDetectionObservationListener {
    fun onObservation(observation: FireDetectionObservation)
}

/**
 * Process-local, read-only observation channel for the RC Plus validation overlay.
 * It never feeds commands back into the detector or flight-control path.
 */
object FireDetectionObservationBus : FireDetectionObservationSink {
    private val listeners = CopyOnWriteArraySet<FireDetectionObservationListener>()

    @Volatile
    private var latest = FireDetectionObservation(enabled = false, active = false)

    override fun publish(observation: FireDetectionObservation) {
        latest = observation.copy(detections = observation.detections.toList())
        listeners.forEach { it.onObservation(latest) }
    }

    fun addListener(listener: FireDetectionObservationListener) {
        listeners += listener
        listener.onObservation(latest)
    }

    fun removeListener(listener: FireDetectionObservationListener) {
        listeners -= listener
    }
}
