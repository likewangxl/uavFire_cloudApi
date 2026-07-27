package com.yinxin.uavfir.benchmark

internal object YoloPostprocessor {
    private const val CANDIDATE_COUNT = 8400
    private const val CONFIDENCE_THRESHOLD = 0.25f
    private const val IOU_THRESHOLD = 0.7f

    fun process(output: FloatArray, input: PreparedInput): List<Detection> {
        require(output.size == 5 * CANDIDATE_COUNT) { "Unexpected YOLO output length ${output.size}" }
        val candidates = buildList {
            for (index in 0 until CANDIDATE_COUNT) {
                val confidence = output[4 * CANDIDATE_COUNT + index]
                if (confidence < CONFIDENCE_THRESHOLD) continue
                val mapped = RgbaTensorPreprocessor.mapToSource(
                    prepared = input,
                    cx = output[index],
                    cy = output[CANDIDATE_COUNT + index],
                    width = output[2 * CANDIDATE_COUNT + index],
                    height = output[3 * CANDIDATE_COUNT + index],
                ) ?: continue
                add(mapped.copy(confidence = confidence))
            }
        }
        return candidates.sortedByDescending(Detection::confidence).fold(mutableListOf()) { kept, candidate ->
            if (kept.none { intersectionOverUnion(it, candidate) > IOU_THRESHOLD }) kept += candidate
            kept
        }
    }

    fun intersectionOverUnion(first: Detection, second: Detection): Float {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val firstArea = (first.right - first.left) * (first.bottom - first.top)
        val secondArea = (second.right - second.left) * (second.bottom - second.top)
        return if (intersection == 0f) 0f else intersection / (firstArea + secondArea - intersection)
    }
}
