package com.yinxin.uavfir.wayline

import android.util.Log
import com.yinxin.uavfir.api.CommandPoller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Routes commands fetched from `WaylineAgentClient.pollCommand()` into the
 * MSDK executor. The HTTP ack happens immediately (skeleton always result=0);
 * actual execution results flow back through MQTT events via the executor's
 * Listener.
 *
 * For wayline_dispatch: downloads the KMZ via [downloader] (verifies MD5),
 * then calls [WaypointMissionExecutor.pushKmz] to ship it to the aircraft.
 * Pause/resume/stop just route to the executor.
 */
class WaylineAgentCommandRouter(
    private val client: WaylineAgentClient,
    private val executor: WaypointMissionExecutor,
    private val downloader: WaylineKmzDownloader,
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

        val token = client.ensureToken(droneSn)
        val downloadResult = withContext(Dispatchers.IO) {
            downloader.download(kmzUrl, token, expectedMd5, missionId)
        }
        when (downloadResult) {
            is WaylineKmzDownloader.Result.Failure -> {
                Log.w(TAG, "kmz download failed mission=$missionId reason=${downloadResult.reason}")
                client.ack(droneSn, cmd.tid, RESULT_DOWNLOAD_FAILED, downloadResult.reason)
            }
            is WaylineKmzDownloader.Result.Success -> {
                executor.pushKmz(missionId, downloadResult.file.absolutePath) { ok, err ->
                    if (ok) {
                        Log.i(TAG, "kmz pushed mission=$missionId path=${downloadResult.file.absolutePath}")
                    } else {
                        Log.w(TAG, "pushKmz failed mission=$missionId err=${err?.errorCode()}")
                    }
                }
                client.ack(droneSn, cmd.tid, RESULT_OK, "kmz-md5:${downloadResult.md5}")
            }
        }
    }

    companion object {
        private const val TAG = "WaylineAgentRouter"
        private const val RESULT_OK = 0
        private const val RESULT_BAD_PAYLOAD = 2000
        private const val RESULT_DOWNLOAD_FAILED = 2003
        private const val RESULT_UNKNOWN_METHOD = 2002
    }
}
