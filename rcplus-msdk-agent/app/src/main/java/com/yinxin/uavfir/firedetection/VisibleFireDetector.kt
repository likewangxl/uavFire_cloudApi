package com.yinxin.uavfir.firedetection

data class VisibleRgbaFrame(
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtMillis: Long,
) {
    init {
        require(width > 0 && height > 0) { "Visible frame dimensions must be positive" }
        require(pixels.size.toLong() == width.toLong() * height * RGBA_CHANNELS) {
            "Visible frame must contain exactly width * height * 4 RGBA bytes"
        }
    }

    private companion object {
        const val RGBA_CHANNELS = 4L
    }
}

data class VisibleDetection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val classIndex: Int,
    val className: String,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "Visible detection coordinates must be normalized"
        }
        require(right > left && bottom > top) { "Visible detection box must have positive area" }
        require(confidence in 0f..1f) { "Visible detection confidence must be normalized" }
        require(classIndex >= 0 && className.isNotBlank()) { "Visible detection class must be identified" }
    }
}

data class VisibleDetectionResult(
    val frameCapturedAtMillis: Long,
    val detections: List<VisibleDetection>,
)

interface VisibleFireDetector : AutoCloseable {
    suspend fun detect(frame: VisibleRgbaFrame): VisibleDetectionResult
}

sealed class VisibleFireDetectionFailure(message: String) : IllegalStateException(message) {
    class Closed : VisibleFireDetectionFailure("Visible fire detector is closed")
}

internal object VisibleBoxMapper {
    fun mapToSource(
        modelWidth: Int,
        modelHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
    ): VisibleDetection? {
        require(modelWidth > 0 && modelHeight > 0 && sourceWidth > 0 && sourceHeight > 0)
        val scale = minOf(modelWidth.toFloat() / sourceWidth, modelHeight.toFloat() / sourceHeight)
        val padX = (modelWidth - sourceWidth * scale) / 2f
        val padY = (modelHeight - sourceHeight * scale) / 2f
        val left = ((centerX - width / 2f - padX) / scale / sourceWidth).coerceIn(0f, 1f)
        val top = ((centerY - height / 2f - padY) / scale / sourceHeight).coerceIn(0f, 1f)
        val right = ((centerX + width / 2f - padX) / scale / sourceWidth).coerceIn(0f, 1f)
        val bottom = ((centerY + height / 2f - padY) / scale / sourceHeight).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        return VisibleDetection(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            confidence = 0f,
            classIndex = 0,
            className = "unassigned",
        )
    }
}
