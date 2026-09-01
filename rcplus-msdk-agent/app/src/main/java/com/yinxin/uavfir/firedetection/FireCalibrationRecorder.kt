package com.yinxin.uavfir.firedetection

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

fun interface FireCalibrationSink {
    fun record(
        droneSn: String,
        taskId: String,
        sourceTs: Long,
        result: VisibleInferenceResult,
        confirmed: VisibleDetection?,
    )

    companion object {
        val NO_OP = FireCalibrationSink { _, _, _, _, _ -> }
    }
}

class FireCalibrationRecorder(context: Context) : FireCalibrationSink {
    private val directory = File(context.getExternalFilesDir(null), "fire-calibration")
    private val logFile = File(directory, "m300-inference.jsonl")

    @Synchronized
    override fun record(
        droneSn: String,
        taskId: String,
        sourceTs: Long,
        result: VisibleInferenceResult,
        confirmed: VisibleDetection?,
    ) {
        runCatching {
            directory.mkdirs()
            if (logFile.length() >= MAX_LOG_BYTES) {
                val previous = File(directory, "m300-inference.previous.jsonl")
                previous.delete()
                logFile.renameTo(previous)
            }
            val top = result.detections.maxByOrNull { it.confidence }
            val record = JSONObject()
                .put("source_ts", sourceTs)
                .put("drone_sn", droneSn)
                .put("task_id", taskId)
                .put("model_version", AgentFireModelSpec.MODEL_VERSION)
                .put("model_sha256", AgentFireModelSpec.MODEL_SHA256)
                .put("inference_ms", result.inferenceMs)
                .put("detection_count", result.detections.size)
                .put("confirmed", confirmed != null)
                .put("top_class", top?.label ?: JSONObject.NULL)
                .put("top_score", top?.confidence ?: JSONObject.NULL)
            logFile.appendText(record.toString() + "\n", Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "calibration record failed", it) }
    }

    private companion object {
        const val TAG = "FireCalibration"
        const val MAX_LOG_BYTES = 20L * 1024 * 1024
    }
}
