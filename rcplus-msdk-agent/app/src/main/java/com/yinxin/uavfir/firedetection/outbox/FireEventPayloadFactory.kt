package com.yinxin.uavfir.firedetection.outbox

import com.yinxin.uavfir.api.DualStreamEventRequest
import com.yinxin.uavfir.firedetection.VisibleDetectionReport
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object FireEventPayloadFactory {
    fun stableEventId(report: VisibleDetectionReport): String {
        val key = listOf(
            report.droneSn,
            report.taskId,
            report.sourceTs.toString(),
            report.detection.classId.toString(),
            report.detection.label,
            report.modelVersion,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "agent-${digest.take(48)}"
    }

    fun request(report: VisibleDetectionReport): DualStreamEventRequest {
        val detection = report.detection
        val score = detection.confidence.coerceIn(0.0, 1.0)
        return DualStreamEventRequest(
            eventId = stableEventId(report),
            taskId = report.taskId,
            droneSn = report.droneSn,
            sourceTs = report.sourceTs,
            visibleScore = score,
            thermalScore = 0.0,
            fusionScore = score,
            riskLevel = when {
                score >= 0.70 -> "HIGH"
                score >= 0.40 -> "MEDIUM"
                else -> "LOW"
            },
            analysisChannel = "agent-visible-onnx",
            reviewStatus = "VISIBLE_CANDIDATE",
            visibleRoi = mapOf(
                "x" to detection.roi.x,
                "y" to detection.roi.y,
                "width" to detection.roi.width,
                "height" to detection.roi.height,
            ),
            visibleClass = detection.label,
            modelVersion = report.modelVersion,
            modelSha256 = report.modelSha256,
            inferenceMs = report.inferenceMs,
        )
    }
}
