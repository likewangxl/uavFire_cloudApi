package com.yinxin.uavfir.firedetection

import kotlin.math.min
import kotlin.math.roundToInt

data class LetterboxResult(
    val tensor: FloatArray,
    val originalWidth: Int,
    val originalHeight: Int,
    val inputWidth: Int,
    val inputHeight: Int,
    val scale: Double,
    val padX: Double,
    val padY: Double,
)

object RgbaLetterboxPreprocessor {
    private const val PAD_VALUE = 114f / 255f

    fun preprocess(
        rgba: ByteArray,
        width: Int,
        height: Int,
        inputSize: Int = AgentFireModelSpec.INPUT_SIZE,
    ): LetterboxResult {
        require(width > 0 && height > 0) { "frame-size-invalid" }
        require(rgba.size >= width * height * 4) { "rgba-frame-too-small" }
        val scale = min(inputSize.toDouble() / width, inputSize.toDouble() / height)
        val resizedWidth = (width * scale).roundToInt().coerceIn(1, inputSize)
        val resizedHeight = (height * scale).roundToInt().coerceIn(1, inputSize)
        val padX = (inputSize - resizedWidth) / 2
        val padY = (inputSize - resizedHeight) / 2
        val planeSize = inputSize * inputSize
        val tensor = FloatArray(planeSize * 3) { PAD_VALUE }

        for (dy in 0 until resizedHeight) {
            val sourceY = ((dy + 0.5) / scale - 0.5).roundToInt().coerceIn(0, height - 1)
            for (dx in 0 until resizedWidth) {
                val sourceX = ((dx + 0.5) / scale - 0.5).roundToInt().coerceIn(0, width - 1)
                val source = (sourceY * width + sourceX) * 4
                val destination = (dy + padY) * inputSize + dx + padX
                tensor[destination] = (rgba[source].toInt() and 0xff) / 255f
                tensor[planeSize + destination] = (rgba[source + 1].toInt() and 0xff) / 255f
                tensor[planeSize * 2 + destination] = (rgba[source + 2].toInt() and 0xff) / 255f
            }
        }
        return LetterboxResult(
            tensor = tensor,
            originalWidth = width,
            originalHeight = height,
            inputWidth = inputSize,
            inputHeight = inputSize,
            scale = scale,
            padX = padX.toDouble(),
            padY = padY.toDouble(),
        )
    }
}
