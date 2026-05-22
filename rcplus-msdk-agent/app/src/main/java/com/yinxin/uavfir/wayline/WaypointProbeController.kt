package com.yinxin.uavfir.wayline

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * One-shot probe: drops a known-good M4T KMZ to disk and calls
 * [WaypointMissionExecutor.pushKmz] to see whether MSDK v5's
 * IWaypointMissionManager actually accepts the file on this hardware.
 *
 * Three reasons the probe is needed even after Pilot 2 accepts the KMZ:
 * 1) Pilot 2 uses DJI-internal APIs; third-party MSDK is a different gate.
 * 2) Cloud-API product-support matrix doesn't explicitly list the M4 series
 *    for waypoint missions.
 * 3) A NOT_SUPPORTED return on M4T means the agent dispatch path needs a
 *    different strategy (e.g. handing the KMZ to Pilot 2 over IPC).
 */
class WaypointProbeController(
    private val executor: WaypointMissionExecutor,
    private val probeKmzBytes: ByteArray,
    private val cacheDir: File,
) {
    data class ProbeResult(val ok: Boolean, val message: String)

    suspend fun probePush(timeoutMs: Long = 60_000L): ProbeResult {
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            return ProbeResult(false, "cache-dir-create-failed: ${cacheDir.absolutePath}")
        }
        val file = File(cacheDir, "m4t_probe.kmz")
        file.writeBytes(probeKmzBytes)
        Log.i(TAG, "probePush start path=${file.absolutePath} size=${probeKmzBytes.size}")

        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<ProbeResult> { cont ->
                val missionId = "msdk-probe-${System.currentTimeMillis()}"
                executor.pushKmz(missionId, file.absolutePath) { pushOk, pushErr ->
                    if (!cont.isActive) return@pushKmz
                    if (!pushOk) {
                        val code = pushErr?.errorCode() ?: "?"
                        val desc = pushErr?.description() ?: "(no desc)"
                        val msg = "push FAILURE code=$code desc=$desc"
                        Log.i(TAG, msg)
                        cont.resume(ProbeResult(false, msg))
                        return@pushKmz
                    }
                    Log.i(TAG, "push SUCCESS — now trying startMission")
                    // Diagnostic: also call startMission to isolate whether MSDK accepts the KMZ
                    // for execution. WaypointMissionExecutor.startMission is fire-and-forget,
                    // so we synthesize a result via a side-channel listener attached at construction.
                    executor.startMission(missionId, "m4t_probe.kmz", null)
                    cont.resume(ProbeResult(true, "push SUCCESS; startMission fired — watch logcat for WaylineEventForwarder"))
                }
            }
        }
        return outcome ?: ProbeResult(
            false,
            "pushKMZFileToAircraft: TIMEOUT after ${timeoutMs}ms (no callback). " +
                    "Likely aircraft not connected or MSDK in unexpected state.",
        )
    }

    companion object {
        private const val TAG = "WaypointProbe"
    }
}
