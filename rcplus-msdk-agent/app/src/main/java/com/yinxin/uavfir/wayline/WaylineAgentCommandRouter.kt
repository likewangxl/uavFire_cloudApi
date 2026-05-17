package com.yinxin.uavfir.wayline

import android.util.Log
import com.yinxin.uavfir.api.CommandPoller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Routes commands fetched from `WaylineAgentClient.pollCommand()` into the
 * MSDK executor. HTTP ack fires immediately per contract §3.5 (it means
 * "command received and accepted", not "mission completed"); final execution
 * outcome flows back through MQTT `wayline_dispatch_result` (via [forwarder])
 * and state-change events (via the executor's Listener).
 *
 * For wayline_dispatch: downloads the KMZ via [downloader] (verifies MD5),
 * calls [WaypointMissionExecutor.pushKmz] to ship it to the aircraft, then on
 * push success calls [WaypointMissionExecutor.startMission] so the aircraft
 * actually flies the wayline. Each terminal outcome publishes a
 * `wayline_dispatch_result` MQTT event.
 *
 * Pause/resume/stop just route to the executor.
 */
class WaylineAgentCommandRouter(
    private val client: WaylineAgentClient,
    private val executor: WaypointMissionExecutor,
    private val downloader: WaylineKmzDownloader,
    private val forwarder: WaylineEventForwarder,
) : CommandPoller {

    override suspend fun pollOnce(droneSn: String) {
        val cmd = client.pollCommand(droneSn) ?: return
        Log.i(TAG, "received method=${cmd.method} tid=${cmd.tid} drone=$droneSn")

        when (cmd.method) {
            WaylineAgentMethod.DISPATCH -> handleDispatch(droneSn, cmd)
            WaylineAgentMethod.PAUSE -> {
                executor.pauseMission()
                client.ack(droneSn, cmd.tid, RESULT_OK)
            }
            WaylineAgentMethod.RESUME -> {
                executor.resumeMission()
                client.ack(droneSn, cmd.tid, RESULT_OK)
            }
            WaylineAgentMethod.STOP -> {
                executor.stopActiveMission()
                client.ack(droneSn, cmd.tid, RESULT_OK)
            }
            WaylineAgentMethod.QUERY_BREAKPOINT -> {
                executor.queryActiveBreakpoint { _, err ->
                    // TODO: forward breakpoint info via MQTT event
                    Log.i(TAG, "breakpoint result err=${err?.errorCode()}")
                }
                client.ack(droneSn, cmd.tid, RESULT_OK)
            }
            else -> {
                client.ack(droneSn, cmd.tid, RESULT_UNKNOWN_METHOD, "unknown method ${cmd.method}")
            }
        }
    }

    private suspend fun handleDispatch(droneSn: String, cmd: WaylineAgentCommand) {
        val data = cmd.data ?: run {
            client.ack(droneSn, cmd.tid, RESULT_BAD_PAYLOAD, "dispatch missing data")
            return
        }
        val kmzUrl = data["kmz_url"] as? String ?: run {
            client.ack(droneSn, cmd.tid, RESULT_BAD_PAYLOAD, "dispatch missing kmz_url")
            return
        }
        val missionId = data["mission_id"] as? String ?: run {
            client.ack(droneSn, cmd.tid, RESULT_BAD_PAYLOAD, "dispatch missing mission_id")
            return
        }
        val expectedMd5 = data["kmz_md5"] as? String
        // startMission(fileName, ...) expects the same basename pushKMZFileToAircraft
        // wrote on the drone. Per contract §3.3 backend supplies it as kmz_filename.
        val basename = (data["kmz_filename"] as? String)?.takeIf { it.isNotBlank() } ?: run {
            client.ack(droneSn, cmd.tid, RESULT_BAD_PAYLOAD, "dispatch missing kmz_filename")
            return
        }
        @Suppress("UNCHECKED_CAST")
        val waylineIds = (data["wayline_ids"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toInt() }
            ?.takeIf { it.isNotEmpty() }

        val token = client.ensureToken(droneSn)
        val downloadResult = withContext(Dispatchers.IO) {
            downloader.download(kmzUrl, token, expectedMd5, missionId, basename)
        }
        when (downloadResult) {
            is WaylineKmzDownloader.Result.Failure -> {
                Log.w(TAG, "kmz download failed mission=$missionId reason=${downloadResult.reason}")
                forwarder.publishDispatchResult(missionId, RESULT_DOWNLOAD_FAILED, downloadResult.reason, basename)
                client.ack(droneSn, cmd.tid, RESULT_DOWNLOAD_FAILED, downloadResult.reason)
            }
            is WaylineKmzDownloader.Result.Success -> {
                executor.pushKmz(missionId, downloadResult.file.absolutePath) { ok, err ->
                    if (!ok) {
                        Log.w(TAG, "pushKmz failed mission=$missionId err=${err?.errorCode()}")
                        forwarder.publishDispatchResult(
                            missionId,
                            RESULT_PUSH_FAILED,
                            "pushKmz:${err?.errorCode()}:${err?.description()}",
                            basename,
                        )
                        return@pushKmz
                    }
                    Log.i(TAG, "kmz pushed mission=$missionId path=${downloadResult.file.absolutePath}")
                    // Per contract §7.2 the agent should auto-start so the aircraft flies the
                    // KMZ that was just pushed; a manual UI gate can be added later if needed.
                    executor.startMission(missionId, basename, waylineIds)
                    forwarder.publishDispatchResult(missionId, RESULT_OK, "kmz-md5:${downloadResult.md5}", basename)
                }
                // ACK = command accepted; outcome via wayline_dispatch_result above.
                client.ack(droneSn, cmd.tid, RESULT_OK, "kmz-md5:${downloadResult.md5}")
            }
        }
    }

    companion object {
        private const val TAG = "WaylineAgentRouter"
        private const val RESULT_OK = 0
        private const val RESULT_BAD_PAYLOAD = 2000
        private const val RESULT_UNKNOWN_METHOD = 2002
        private const val RESULT_DOWNLOAD_FAILED = 2003
        private const val RESULT_PUSH_FAILED = 2004
    }
}
