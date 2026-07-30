package com.yinxin.uavfir.benchmark

internal class YoloPostprocessor(private val manifest: ModelManifest, private val preprocessor: RgbaTensorPreprocessor) {
    private val candidateCount = manifest.candidateCount
    private val outputChannels = manifest.outputChannels

    fun process(output: FloatArray, input: PreparedInput): List<Detection> {
        require(output.size == outputChannels * candidateCount) { "Unexpected YOLO output length ${output.size}" }
        return processCandidates(input) { channel, index -> output[channel * candidateCount + index] }
    }

    fun process(output: Array<FloatArray>, input: PreparedInput): List<Detection> {
        require(output.size == outputChannels && output.all { it.size == candidateCount }) { "Unexpected YOLO output shape" }
        return processCandidates(input) { channel, index -> output[channel][index] }
    }

    private fun processCandidates(input: PreparedInput, value: (Int, Int) -> Float): List<Detection> {
        val candidates = ArrayList<Detection>()
        for (index in 0 until candidateCount) {
            val classIndex = manifest.classNames.indices.maxBy { value(4 + it, index) }
            val confidence = value(4 + classIndex, index)
            if (confidence < manifest.confidenceThreshold) continue
            val mapped = preprocessor.mapToSource(input, value(0, index), value(1, index), value(2, index), value(3, index)) ?: continue
            candidates += mapped.copy(confidence = confidence, classIndex = classIndex)
        }
        candidates.sortByDescending(Detection::confidence)
        return candidates.fold(mutableListOf()) { kept, candidate ->
            if (kept.none { it.classIndex == candidate.classIndex && intersectionOverUnion(it, candidate) > manifest.iouThreshold }) {
                kept += candidate
            }
            kept
        }
    }

    companion object {
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
}
