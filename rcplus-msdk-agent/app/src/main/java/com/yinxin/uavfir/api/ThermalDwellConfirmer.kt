package com.yinxin.uavfir.api

import android.util.Log
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import kotlinx.coroutines.delay

class ThermalDwellConfirmer(
    private val sessionManager: DualStreamSessionManager,
    private val missionHold: MissionHoldControl,
    private val enabled: Boolean = DEFAULT_ENABLED,
    private val stabilizeMs: Long = DEFAULT_STABILIZE_MS,
    private val sampleCount: Int = DEFAULT_SAMPLE_COUNT,
    private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
    private val failureGraceAttempts: Int = DEFAULT_FAILURE_GRACE_ATTEMPTS,
    private val confirmMinHits: Int = DEFAULT_CONFIRM_MIN_HITS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun confirm(
        droneSn: String,
        thresholdC: Double,
        firstSample: DwellSample,
        seedRegion: ThermalMeasureRegion,
    ): DwellConfirmResult {
        if (!enabled) {
            return buildResult(
                confirmed = true,
                degraded = false,
                samples = listOf(firstSample),
            )
        }

        val samples = mutableListOf(firstSample)
        var hitCount = if (firstSample.temperatureC >= thresholdC) 1 else 0
        var consecutiveFailures = 0

        try {
            runCatching { missionHold.holdForConfirmation() }
                .onFailure { warn("dwell hold failed drone=$droneSn message=${it.message}", it) }
            delay(stabilizeMs)

            val additionalSamples = (sampleCount - 1).coerceAtLeast(0)
            val maxAttempts = additionalSamples + failureGraceAttempts.coerceAtLeast(0)
            var validAdditionalSamples = 0
            var attempts = 0
            while (validAdditionalSamples < additionalSamples && attempts < maxAttempts) {
                attempts += 1
                val result = runCatching {
                    sessionManager.measureThermalHotspot(droneSn, seedRegion)
                }.getOrElse {
                    warn("dwell measure threw drone=$droneSn sample=${validAdditionalSamples + 2} attempt=$attempts message=${it.message}", it)
                    DualStreamSessionManager.CommandExecutionResult(
                        status = "failed",
                        message = it.message,
                    )
                }

                val temperature = result.thermalCenterTemperatureC
                val region = result.thermalMeasureRegion
                if (result.status.equals("applied", ignoreCase = true) && temperature != null && region != null) {
                    val sample = DwellSample(
                        temperatureC = temperature,
                        region = region,
                        atMs = clockMs(),
                    )
                    samples += sample
                    validAdditionalSamples += 1
                    if (temperature >= thresholdC) {
                        hitCount += 1
                    }
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures += 1
                    warn(
                        "dwell sample failed drone=$droneSn sample=${validAdditionalSamples + 2} " +
                            "attempt=$attempts status=${result.status} message=${result.message}",
                    )
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        return buildResult(
                            confirmed = false,
                            degraded = true,
                            samples = samples,
                            bestSample = firstSample,
                        )
                    }
                }

                if (validAdditionalSamples < additionalSamples && attempts < maxAttempts) {
                    delay(sampleIntervalMs)
                }
            }

            return buildResult(
                confirmed = hitCount >= confirmMinHits,
                degraded = false,
                samples = samples,
            )
        } finally {
            runCatching { missionHold.resumeAfterConfirmation() }
                .onFailure { warn("dwell resume failed drone=$droneSn message=${it.message}", it) }
        }
    }

    private fun buildResult(
        confirmed: Boolean,
        degraded: Boolean,
        samples: List<DwellSample>,
        bestSample: DwellSample? = samples.maxByOrNull { it.temperatureC },
    ): DwellConfirmResult {
        val minC = samples.minOfOrNull { it.temperatureC }
        val maxC = samples.maxOfOrNull { it.temperatureC }
        return DwellConfirmResult(
            confirmed = confirmed,
            degraded = degraded,
            samples = samples,
            bestSample = bestSample,
            minC = minC,
            maxC = maxC,
            spreadC = if (minC != null && maxC != null) maxC - minC else null,
        )
    }

    private fun warn(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable == null) {
                Log.w(TAG, message)
            } else {
                Log.w(TAG, message, throwable)
            }
        }.onFailure {
            println("$TAG: $message")
        }
    }

    companion object {
        private const val TAG = "ThermalDwellConfirmer"
        const val DEFAULT_ENABLED: Boolean = true
        const val DEFAULT_STABILIZE_MS: Long = 2_000L
        const val DEFAULT_SAMPLE_COUNT: Int = 4
        const val DEFAULT_SAMPLE_INTERVAL_MS: Long = 1_500L
        const val DEFAULT_FAILURE_GRACE_ATTEMPTS: Int = 2
        const val DEFAULT_CONFIRM_MIN_HITS: Int = 3
        private const val MAX_CONSECUTIVE_FAILURES: Int = 2
    }
}

data class DwellSample(
    val temperatureC: Double,
    val region: ThermalMeasureRegion,
    val atMs: Long,
)

data class DwellConfirmResult(
    val confirmed: Boolean,
    val degraded: Boolean,
    val samples: List<DwellSample>,
    val bestSample: DwellSample?,
    val minC: Double?,
    val maxC: Double?,
    val spreadC: Double?,
)
