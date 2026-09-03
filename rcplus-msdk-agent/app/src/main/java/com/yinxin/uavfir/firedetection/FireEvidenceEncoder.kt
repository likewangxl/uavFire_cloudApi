package com.yinxin.uavfir.firedetection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

data class EncodedFireEvidence(
    val jpeg: ByteArray,
    val sha256: String,
)

object FireEvidenceEncoder {
    fun encodeRgba(
        rgba: ByteArray,
        width: Int,
        height: Int,
        detections: List<VisibleDetection> = emptyList(),
    ): EncodedFireEvidence {
        require(width > 0 && height > 0 && rgba.size == width * height * 4)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            drawDetections(bitmap, detections)
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                "evidence-jpeg-encode-failed"
            }
            val jpeg = output.toByteArray()
            EncodedFireEvidence(jpeg, sha256(jpeg))
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawDetections(bitmap: Bitmap, detections: List<VisibleDetection>) {
        if (detections.isEmpty()) return
        val canvas = Canvas(bitmap)
        val shortestSide = minOf(bitmap.width, bitmap.height).toFloat()
        val strokeWidth = (shortestSide * 0.004f).coerceAtLeast(3f)
        val textSize = (shortestSide * 0.035f).coerceAtLeast(24f)
        val padding = (textSize * 0.30f).coerceAtLeast(6f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        detections.forEach { detection ->
            val color = if (detection.label.equals("fire", ignoreCase = true)) FIRE_COLOR else SMOKE_COLOR
            boxPaint.color = color
            labelBackgroundPaint.color = color
            val roi = detection.roi
            val rect = RectF(
                (roi.x * bitmap.width).toFloat().coerceIn(0f, bitmap.width.toFloat()),
                (roi.y * bitmap.height).toFloat().coerceIn(0f, bitmap.height.toFloat()),
                ((roi.x + roi.width) * bitmap.width).toFloat().coerceIn(0f, bitmap.width.toFloat()),
                ((roi.y + roi.height) * bitmap.height).toFloat().coerceIn(0f, bitmap.height.toFloat()),
            )
            if (rect.width() <= 0f || rect.height() <= 0f) return@forEach
            canvas.drawRect(rect, boxPaint)
            val label = String.format(
                Locale.US,
                "%s %.0f%%",
                detection.label.uppercase(Locale.US),
                detection.confidence * 100.0,
            )
            val labelHeight = textSize + padding * 2f
            val labelTop = (rect.top - labelHeight).coerceAtLeast(0f)
            val labelRight = (rect.left + labelPaint.measureText(label) + padding * 2f)
                .coerceAtMost(bitmap.width.toFloat())
            canvas.drawRect(rect.left, labelTop, labelRight, labelTop + labelHeight, labelBackgroundPaint)
            canvas.drawText(label, rect.left + padding, labelTop + textSize + padding / 2f, labelPaint)
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private const val FIRE_COLOR = 0xFFFF3B30.toInt()
    private const val SMOKE_COLOR = 0xFFFFC107.toInt()
}
