package com.yinxin.uavfir.benchmark

import java.nio.ByteBuffer

data class RgbaFrame(
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtMs: Long,
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height * 4)
    }
}

data class Detection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val classIndex: Int = 0,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
    }
}

interface EngineAdapter : AutoCloseable {
    val engine: Engine
    fun infer(frame: RgbaFrame): List<Detection>
}

internal data class PreparedInput(
    val nhwc: ByteBuffer,
    val nchw: ByteBuffer,
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
)
