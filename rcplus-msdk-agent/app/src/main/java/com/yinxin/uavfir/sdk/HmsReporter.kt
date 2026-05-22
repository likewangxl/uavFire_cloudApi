package com.yinxin.uavfir.sdk

import android.util.Log
import com.yinxin.uavfir.wayline.WaylineMqttPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Publishes the Cloud SDK protocol HMS events on
 * `thing/product/{aircraftSn}/events` so backend treats the agent as a
 * drop-in Pilot 2 replacement for health/maintenance signalling.
 *
 * Phase 1: subscription scaffolding only — publishing is gated behind an
 * `enabled` flag that defaults to false. Reason: backend's HMS handler
 * may treat `{"data":{"list":[]}}` as an authoritative "clear all alarms"
 * snapshot (Pilot 2 sends the same shape and the semantics aren't
 * documented). Until we verify backend's HmsHandler against an empty list
 * during real-hardware testing, blindly heart-beating could silently drop
 * genuine alarms. The class stays wired so phase 2 only needs to flip the
 * flag and add real alarm translation.
 *
 * Phase 2 TODO:
 *   1. Audit backend HmsHandler — confirm empty list is a no-op, not a wipe.
 *   2. Subscribe MSDK's DeviceHealthManager
 *      (`dji.v5.manager.diagnostic.DeviceHealthManager`) and translate
 *      DJIDeviceHealthInfo entries into Cloud SDK HMS list items.
 *   3. Set `enabled = true` once both above are verified.
 */
class HmsReporter(
    private val publisher: WaylineMqttPublisher,
    private val scope: CoroutineScope,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    /**
     * Default false. Flip to true only after backend HmsHandler semantics
     * have been audited against an empty list — see class kdoc.
     */
    private val enabled: Boolean = false,
) {
    @Volatile
    private var aircraftSn: String? = null

    @Volatile
    private var gatewaySn: String? = null

    private var job: Job? = null

    fun start(aircraftSn: String, gatewaySn: String) {
        if (!enabled) {
            Log.i(TAG, "HmsReporter disabled (phase 1 default) — skip start aircraftSn=$aircraftSn")
            return
        }
        if (job?.isActive == true) {
            this.aircraftSn = aircraftSn
            this.gatewaySn = gatewaySn
            return
        }
        this.aircraftSn = aircraftSn
        this.gatewaySn = gatewaySn
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { publishOnce() }
                    .onFailure { Log.w(TAG, "publishOnce failed: ${it.message}", it) }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun publishOnce() {
        val aircraft = aircraftSn ?: return
        val gateway = gatewaySn ?: return
        val data = mapOf("list" to emptyList<Map<String, Any?>>())
        publisher.publishCloudEvent(aircraft, gateway, "hms", data)
    }

    companion object {
        private const val TAG = "HmsReporter"
        const val DEFAULT_INTERVAL_MS: Long = 10_000
    }
}
