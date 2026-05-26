package com.yinxin.uavfir.wayline

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

data class LocalKmzMissionResult(val ok: Boolean, val message: String)

data class LocalKmzPushResult(val ok: Boolean, val message: String?)

interface LocalKmzExecutor {
    fun pushKmz(missionId: String, kmzPath: String, onComplete: (LocalKmzPushResult) -> Unit)

    fun startMission(missionId: String, missionFileName: String, waylineIds: List<Int>?)
}

class WaypointLocalKmzExecutor(
    private val waypointExecutor: WaypointMissionExecutor,
) : LocalKmzExecutor {
    override fun pushKmz(missionId: String, kmzPath: String, onComplete: (LocalKmzPushResult) -> Unit) {
        waypointExecutor.pushKmz(missionId, kmzPath) { ok, err ->
            if (ok) {
                onComplete(LocalKmzPushResult(true, null))
                return@pushKmz
            }
            val code = err?.errorCode() ?: "?"
            val desc = err?.description() ?: "(no desc)"
            onComplete(LocalKmzPushResult(false, "push FAILURE code=$code desc=$desc"))
        }
    }

    override fun startMission(missionId: String, missionFileName: String, waylineIds: List<Int>?) {
        waypointExecutor.startMission(missionId, missionFileName, waylineIds)
    }
}

class LocalKmzMissionController(
    private val executor: LocalKmzExecutor,
    private val stagingDir: File? = null,
    private val defaultKmzFile: File = File(DEFAULT_KMZ_PATH),
) {
    suspend fun executeLocalKmz(
        kmzFile: File = defaultKmzFile,
        timeoutMs: Long = 60_000L,
    ): LocalKmzMissionResult {
        if (!kmzFile.isFile) {
            return LocalKmzMissionResult(false, "local-kmz-not-found: ${kmzFile.absolutePath}")
        }

        val missionFile = stageForMsdk(kmzFile)

        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<LocalKmzMissionResult> { cont ->
                val missionId = "local-kmz-${System.currentTimeMillis()}"
                executor.pushKmz(missionId, missionFile.absolutePath) { push ->
                    if (!cont.isActive) return@pushKmz
                    if (!push.ok) {
                        val message = push.message ?: "push FAILURE"
                        cont.resume(LocalKmzMissionResult(false, message))
                        return@pushKmz
                    }

                    val startMissionName = WaypointMissionFileNames.startMissionName(missionFile.name)
                    executor.startMission(missionId, startMissionName, null)
                    cont.resume(LocalKmzMissionResult(true, "push SUCCESS; startMission fired for $startMissionName"))
                }
            }
        }

        return outcome ?: LocalKmzMissionResult(
            false,
            "pushKMZFileToAircraft: TIMEOUT after ${timeoutMs}ms for ${kmzFile.absolutePath}",
        )
    }

    private fun stageForMsdk(kmzFile: File): File {
        val dir = stagingDir ?: return kmzFile
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("local-kmz-stage-dir-create-failed: ${dir.absolutePath}")
        }
        val staged = File(dir, kmzFile.name)
        if (kmzFile.canonicalPath == staged.canonicalPath) {
            return kmzFile
        }
        kmzFile.copyTo(staged, overwrite = true)
        return staged
    }

    companion object {
        const val DEFAULT_KMZ_PATH = "/data/user/0/com.yinxin.uavfir/files/wayline-local/Kmz2.kmz"
    }
}
