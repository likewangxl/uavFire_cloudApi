package com.yinxin.uavfir.firedetection

import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

object YoloV8Postprocessor {
    fun decode(
        channels: Array<FloatArray>,
        letterbox: LetterboxResult,
        confidenceThreshold: Double? = null,
        iouThreshold: Double = AgentFireModelSpec.IOU_THRESHOLD,
    ): List<VisibleDetection> {
        require(channels.size == 4 + AgentFireModelSpec.CLASS_NAMES.size) { "unexpected-output-channels=${channels.size}" }
        val candidateCount = channels.firstOrNull()?.size ?: 0
        require(channels.all { it.size == candidateCount }) { "inconsistent-output-candidates" }
        val candidates = ArrayList<VisibleDetection>()
        for (index in 0 until candidateCount) {
            var classId = 0
            var score = channels[4][index].toDouble()
            for (candidateClass in 1 until AgentFireModelSpec.CLASS_NAMES.size) {
                val candidateScore = channels[4 + candidateClass][index].toDouble()
                if (candidateScore > score) {
                    score = candidateScore
                    classId = candidateClass
                }
            }
            val requiredConfidence = confidenceThreshold ?: AgentFireModelSpec.confidenceThresholdFor(classId)
            if (!score.isFinite() || score < requiredConfidence) continue
            val centerX = channels[0][index].toDouble()
            val centerY = channels[1][index].toDouble()
            val boxWidth = channels[2][index].toDouble()
            val boxHeight = channels[3][index].toDouble()
            val left = ((centerX - boxWidth / 2.0) - letterbox.padX) / letterbox.scale
            val top = ((centerY - boxHeight / 2.0) - letterbox.padY) / letterbox.scale
            val right = ((centerX + boxWidth / 2.0) - letterbox.padX) / letterbox.scale
            val bottom = ((centerY + boxHeight / 2.0) - letterbox.padY) / letterbox.scale
            val x1 = left.coerceIn(0.0, letterbox.originalWidth.toDouble())
            val y1 = top.coerceIn(0.0, letterbox.originalHeight.toDouble())
            val x2 = right.coerceIn(0.0, letterbox.originalWidth.toDouble())
            val y2 = bottom.coerceIn(0.0, letterbox.originalHeight.toDouble())
            if (x2 <= x1 || y2 <= y1) continue
            candidates += VisibleDetection(
                classId = classId,
                label = AgentFireModelSpec.CLASS_NAMES[classId],
                confidence = score.coerceIn(0.0, 1.0),
                roi = NormalizedRoi(
                    x = x1 / letterbox.originalWidth,
                    y = y1 / letterbox.originalHeight,
                    width = (x2 - x1) / letterbox.originalWidth,
                    height = (y2 - y1) / letterbox.originalHeight,
                ),
            )
        }
        return classAwareNms(candidates, iouThreshold)
    }

    fun decode(
        channels: FloatBuffer,
        candidateCount: Int,
        letterbox: LetterboxResult,
        confidenceThreshold: Double? = null,
        iouThreshold: Double = AgentFireModelSpec.IOU_THRESHOLD,
    ): List<VisibleDetection> {
        val channelCount = 4 + AgentFireModelSpec.CLASS_NAMES.size
        require(candidateCount >= 0) { "candidate-count-invalid=$candidateCount" }
        require(channels.capacity() >= channelCount * candidateCount) {
            "output-buffer-too-small expected=${channelCount * candidateCount} actual=${channels.capacity()}"
        }
        val candidates = ArrayList<VisibleDetection>()
        for (index in 0 until candidateCount) {
            var classId = 0
            var score = channels.get(4 * candidateCount + index).toDouble()
            for (candidateClass in 1 until AgentFireModelSpec.CLASS_NAMES.size) {
                val candidateScore = channels.get((4 + candidateClass) * candidateCount + index).toDouble()
                if (candidateScore > score) {
                    score = candidateScore
                    classId = candidateClass
                }
            }
            val requiredConfidence = confidenceThreshold ?: AgentFireModelSpec.confidenceThresholdFor(classId)
            if (!score.isFinite() || score < requiredConfidence) continue
            val centerX = channels.get(index).toDouble()
            val centerY = channels.get(candidateCount + index).toDouble()
            val boxWidth = channels.get(candidateCount * 2 + index).toDouble()
            val boxHeight = channels.get(candidateCount * 3 + index).toDouble()
            val left = ((centerX - boxWidth / 2.0) - letterbox.padX) / letterbox.scale
            val top = ((centerY - boxHeight / 2.0) - letterbox.padY) / letterbox.scale
            val right = ((centerX + boxWidth / 2.0) - letterbox.padX) / letterbox.scale
            val bottom = ((centerY + boxHeight / 2.0) - letterbox.padY) / letterbox.scale
            val x1 = left.coerceIn(0.0, letterbox.originalWidth.toDouble())
            val y1 = top.coerceIn(0.0, letterbox.originalHeight.toDouble())
            val x2 = right.coerceIn(0.0, letterbox.originalWidth.toDouble())
            val y2 = bottom.coerceIn(0.0, letterbox.originalHeight.toDouble())
            if (x2 <= x1 || y2 <= y1) continue
            candidates += VisibleDetection(
                classId = classId,
                label = AgentFireModelSpec.CLASS_NAMES[classId],
                confidence = score.coerceIn(0.0, 1.0),
                roi = NormalizedRoi(
                    x = x1 / letterbox.originalWidth,
                    y = y1 / letterbox.originalHeight,
                    width = (x2 - x1) / letterbox.originalWidth,
                    height = (y2 - y1) / letterbox.originalHeight,
                ),
            )
        }
        return classAwareNms(candidates, iouThreshold)
    }

    private fun classAwareNms(candidates: List<VisibleDetection>, iouThreshold: Double): List<VisibleDetection> {
        val remaining = candidates.sortedByDescending { it.confidence }.toMutableList()
        val selected = ArrayList<VisibleDetection>()
        while (remaining.isNotEmpty()) {
            val best = remaining.removeAt(0)
            selected += best
            remaining.removeAll { candidate ->
                candidate.classId == best.classId && intersectionOverUnion(candidate.roi, best.roi) > iouThreshold
            }
        }
        return selected
    }

    internal fun intersectionOverUnion(first: NormalizedRoi, second: NormalizedRoi): Double {
        val intersectionLeft = max(first.x, second.x)
        val intersectionTop = max(first.y, second.y)
        val intersectionRight = min(first.x + first.width, second.x + second.width)
        val intersectionBottom = min(first.y + first.height, second.y + second.height)
        val intersection = max(0.0, intersectionRight - intersectionLeft) *
            max(0.0, intersectionBottom - intersectionTop)
        val union = first.width * first.height + second.width * second.height - intersection
        return if (union <= 0.0) 0.0 else intersection / union
    }
}
