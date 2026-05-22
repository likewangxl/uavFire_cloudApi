package com.yinxin.uavfir.sdk

import android.util.Log
import com.yinxin.uavfir.wayline.WaylineMqttPublisher
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.v5.et.create
import dji.v5.et.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Periodically harvest aircraft OSD telemetry from MSDK and publish it to
 * the Cloud SDK standard topic `thing/product/{aircraftSn}/osd` so the
 * existing backend Cloud SDK subscriber consumes it unchanged.
 *
 * This impersonates the Pilot 2 ↔ Cloud SDK protocol — the JSON shape must
 * stay compatible with [com.dji.sdk.cloudapi.device.OsdRcDrone] (snake_case
 * field names, Cloud SDK envelope with bid/tid/timestamp/gateway/data).
 *
 * NOT a full-fidelity OSD reporter — only the fields cockpit's leadership
 * dashboard reads today (position, height, attitude, mode, battery, speed).
 * Phase 2 will extend coverage based on what real-hardware testing exposes.
 *
 * Threading: MQTT publish performs blocking I/O — runs on [Dispatchers.IO].
 */
class OsdReporter(
    private val publisher: WaylineMqttPublisher,
    private val scope: CoroutineScope,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    @Volatile
    private var aircraftSn: String? = null

    @Volatile
    private var gatewaySn: String? = null

    private var job: Job? = null

    /**
     * Start periodic OSD push.
     *
     * @param aircraftSn SN of the paired M4T aircraft (e.g. 1581F7K3D249E00AM3Q3).
     *   Backend subscribes to `thing/product/{aircraftSn}/osd`, so this must
     *   match the SN the workspace has bound. If unknown at boot time the
     *   caller should re-invoke [start] once it becomes available.
     * @param gatewaySn SN of the RC Plus 2 the agent runs on (e.g. 9N9CMA500100B8).
     *   Goes into the `gateway` envelope field.
     */
    fun start(aircraftSn: String, gatewaySn: String) {
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

        // All getValue calls are synchronous and may return defaults when the
        // aircraft is not yet connected. We tolerate nulls and emit whatever
        // fields are available — backend Jackson defaults missing fields to null.
        val location: LocationCoordinate3D? = runCatching {
            FlightControllerKey.KeyAircraftLocation3D.create().get(LocationCoordinate3D(0.0, 0.0, 0.0))
        }.getOrNull()
        val attitude: Attitude? = runCatching {
            FlightControllerKey.KeyAircraftAttitude.create().get(Attitude())
        }.getOrNull()
        val velocity: Velocity3D? = runCatching {
            FlightControllerKey.KeyAircraftVelocity.create().get(Velocity3D(0.0, 0.0, 0.0))
        }.getOrNull()
        val altitude: Double? = runCatching {
            FlightControllerKey.KeyAltitude.create().get(0.0)
        }.getOrNull()
        val mode: FlightMode? = runCatching {
            FlightControllerKey.KeyFlightMode.create().get(FlightMode.UNKNOWN)
        }.getOrNull()
        val batteryPercent: Int? = runCatching {
            BatteryKey.KeyChargeRemainingInPercent.create().get(0)
        }.getOrNull()
        val compass: Double? = runCatching {
            FlightControllerKey.KeyCompassHeading.create().get(0.0)
        }.getOrNull()

        val horizontalSpeed: Float? = velocity?.let {
            sqrt(it.x * it.x + it.y * it.y).toFloat()
        }

        val data = mutableMapOf<String, Any?>().apply {
            put("latitude", location?.latitude)
            put("longitude", location?.longitude)
            // Cloud SDK OsdRcDrone `height` is altitude above takeoff point.
            // MSDK's LocationCoordinate3D.altitude is also relative to takeoff,
            // so we map directly. `elevation` is absolute MSL altitude.
            put("height", location?.altitude)
            put("elevation", altitude)
            put("attitude_head", compass)
            put("attitude_pitch", attitude?.pitch)
            put("attitude_roll", attitude?.roll)
            put("horizontal_speed", horizontalSpeed)
            put("vertical_speed", velocity?.z)
            // MSDK FlightMode.ordinal is NOT compatible with Cloud SDK
            // DroneModeCodeEnum int values — sending ordinal would risk
            // backend Jackson rejecting the whole OSD message. Send null
            // until phase 2 builds the translation table from real-hardware
            // observation. `mode` is still read above so the listener stays
            // registered and the value is observable in logcat for that work.
            Log.v(TAG, "current FlightMode (untranslated): $mode")
            put("mode_code", null)
            if (batteryPercent != null) {
                put("battery", mapOf("capacity_percent" to batteryPercent))
            }
        }

        publisher.publishCloudOsd(aircraft, gateway, data)
    }

    companion object {
        private const val TAG = "OsdReporter"
        const val DEFAULT_INTERVAL_MS: Long = 1_000
    }
}
