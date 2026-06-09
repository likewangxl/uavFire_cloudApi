package com.yinxin.uavfir.stream

internal object ThermalFrameClassifier {
    fun looksLikeThermalFrame(
        frameData: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
    ): Boolean {
        if (width <= 0 || height <= 0 || offset < 0) {
            return false
        }
        val pixelCount = width * height
        val expectedLength = pixelCount * BYTES_PER_RGBA_PIXEL
        if (length < expectedLength || offset + expectedLength > frameData.size) {
            return false
        }
        var monochromePixels = 0
        var minIntensity = 255
        var maxIntensity = 0
        var sum = 0.0
        var sumSquares = 0.0
        for (i in 0 until pixelCount) {
            val pixelOffset = offset + i * BYTES_PER_RGBA_PIXEL
            val r = frameData[pixelOffset].toInt() and 0xff
            val g = frameData[pixelOffset + 1].toInt() and 0xff
            val b = frameData[pixelOffset + 2].toInt() and 0xff
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max - min < MONOCHROME_CHANNEL_DELTA) {
                monochromePixels += 1
            }
            val intensity = (r + g + b) / 3
            minIntensity = minOf(minIntensity, intensity)
            maxIntensity = maxOf(maxIntensity, intensity)
            sum += intensity
            sumSquares += intensity * intensity
        }
        val grayscaleRatio = monochromePixels.toDouble() / pixelCount.toDouble()
        val mean = sum / pixelCount.toDouble()
        val variance = (sumSquares / pixelCount.toDouble()) - mean * mean
        val std = kotlin.math.sqrt(variance.coerceAtLeast(0.0))
        return grayscaleRatio >= THERMAL_GRAYSCALE_RATIO
            && maxIntensity - minIntensity >= THERMAL_INTENSITY_RANGE
            && std >= THERMAL_INTENSITY_STD
    }

    private const val BYTES_PER_RGBA_PIXEL = 4
    private const val MONOCHROME_CHANNEL_DELTA = 4
    private const val THERMAL_GRAYSCALE_RATIO = 0.95
    private const val THERMAL_INTENSITY_RANGE = 35
    private const val THERMAL_INTENSITY_STD = 12.0
}
