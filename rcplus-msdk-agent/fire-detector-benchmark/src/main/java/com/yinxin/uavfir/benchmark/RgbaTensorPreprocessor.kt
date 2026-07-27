package com.yinxin.uavfir.benchmark

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reuses direct tensor buffers and converts an existing RGBA frame without Bitmap allocation. */
internal class RgbaTensorPreprocessor(private val manifest: ModelManifest) {
    private val pixelCount = manifest.inputWidth * manifest.inputHeight
    private val nhwc = ByteBuffer.allocateDirect(pixelCount * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val nchw = ByteBuffer.allocateDirect(pixelCount * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

    fun prepare(frame: RgbaFrame): PreparedInput {
        val scale = minOf(manifest.inputWidth.toFloat() / frame.width, manifest.inputHeight.toFloat() / frame.height)
        val padX = (manifest.inputWidth - frame.width * scale) / 2f
        val padY = (manifest.inputHeight - frame.height * scale) / 2f
        nhwc.clear()
        for (y in 0 until manifest.inputHeight) {
            for (x in 0 until manifest.inputWidth) {
                val sourceX = ((x - padX) / scale).toInt()
                val sourceY = ((y - padY) / scale).toInt()
                val pixelOffset = (sourceY * frame.width + sourceX) * 4
                val red: Int
                val green: Int
                val blue: Int
                if (sourceX in 0 until frame.width && sourceY in 0 until frame.height) {
                    red = frame.pixels[pixelOffset].toInt() and 0xff
                    green = frame.pixels[pixelOffset + 1].toInt() and 0xff
                    blue = frame.pixels[pixelOffset + 2].toInt() and 0xff
                } else {
                    red = 114
                    green = 114
                    blue = 114
                }
                val position = y * manifest.inputWidth + x
                putRgb(position, red, green, blue)
            }
        }
        nhwc.rewind()
        nchw.rewind()
        return PreparedInput(nhwc, nchw, scale, padX, padY, frame.width, frame.height)
    }

    private fun putRgb(position: Int, red: Int, green: Int, blue: Int) {
        val scale = manifest.normalizationScale
        nhwc.putFloat(red * scale)
        nhwc.putFloat(green * scale)
        nhwc.putFloat(blue * scale)
        nchw.putFloat(position * Float.SIZE_BYTES, red * scale)
        nchw.putFloat((pixelCount + position) * Float.SIZE_BYTES, green * scale)
        nchw.putFloat((2 * pixelCount + position) * Float.SIZE_BYTES, blue * scale)
    }

    fun mapToSource(prepared: PreparedInput, cx: Float, cy: Float, width: Float, height: Float): Detection? {
        val left = ((cx - width / 2f - prepared.padX) / prepared.scale / prepared.sourceWidth).coerceIn(0f, 1f)
        val top = ((cy - height / 2f - prepared.padY) / prepared.scale / prepared.sourceHeight).coerceIn(0f, 1f)
        val right = ((cx + width / 2f - prepared.padX) / prepared.scale / prepared.sourceWidth).coerceIn(0f, 1f)
        val bottom = ((cy + height / 2f - prepared.padY) / prepared.scale / prepared.sourceHeight).coerceIn(0f, 1f)
        return Detection(left, top, right, bottom, confidence = 0f).takeIf { it.right > it.left && it.bottom > it.top }
    }
}
