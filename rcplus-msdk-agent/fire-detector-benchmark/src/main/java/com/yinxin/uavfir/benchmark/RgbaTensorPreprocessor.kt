package com.yinxin.uavfir.benchmark

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts an existing RGBA frame directly into the model tensors. */
internal object RgbaTensorPreprocessor {
    private const val INPUT_SIZE = 640
    private const val CHANNELS = 3

    fun prepare(frame: RgbaFrame): PreparedInput {
        val scale = minOf(INPUT_SIZE.toFloat() / frame.width, INPUT_SIZE.toFloat() / frame.height)
        val resizedWidth = frame.width * scale
        val resizedHeight = frame.height * scale
        val padX = (INPUT_SIZE - resizedWidth) / 2f
        val padY = (INPUT_SIZE - resizedHeight) / 2f
        val elementCount = INPUT_SIZE * INPUT_SIZE * CHANNELS
        val nhwc = ByteBuffer.allocateDirect(elementCount * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val nchw = ByteBuffer.allocateDirect(elementCount * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val sourceX = ((x - padX) / scale).toInt()
                val sourceY = ((y - padY) / scale).toInt()
                val rgb = if (sourceX in 0 until frame.width && sourceY in 0 until frame.height) {
                    val pixelOffset = (sourceY * frame.width + sourceX) * 4
                    intArrayOf(
                        frame.pixels[pixelOffset].toInt() and 0xff,
                        frame.pixels[pixelOffset + 1].toInt() and 0xff,
                        frame.pixels[pixelOffset + 2].toInt() and 0xff,
                    )
                } else {
                    intArrayOf(114, 114, 114)
                }
                val position = y * INPUT_SIZE + x
                for (channel in 0 until CHANNELS) {
                    val value = rgb[channel] / 255f
                    nhwc.putFloat(value)
                    nchw.putFloat((channel * INPUT_SIZE * INPUT_SIZE + position) * Float.SIZE_BYTES, value)
                }
            }
        }
        nhwc.rewind()
        nchw.rewind()
        return PreparedInput(nhwc, nchw, scale, padX, padY, frame.width, frame.height)
    }

    fun mapToSource(prepared: PreparedInput, cx: Float, cy: Float, width: Float, height: Float): Detection? {
        val left = ((cx - width / 2f - prepared.padX) / prepared.scale / prepared.sourceWidth).coerceIn(0f, 1f)
        val top = ((cy - height / 2f - prepared.padY) / prepared.scale / prepared.sourceHeight).coerceIn(0f, 1f)
        val right = ((cx + width / 2f - prepared.padX) / prepared.scale / prepared.sourceWidth).coerceIn(0f, 1f)
        val bottom = ((cy + height / 2f - prepared.padY) / prepared.scale / prepared.sourceHeight).coerceIn(0f, 1f)
        return Detection(left, top, right, bottom, confidence = 0f).takeIf { it.right > it.left && it.bottom > it.top }
    }
}
