package com.yinxin.uavfir.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.yinxin.uavfir.firedetection.FireDetectionObservation
import com.yinxin.uavfir.firedetection.VisibleDetection
import java.util.Locale

class FireDetectionOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val statusBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC101820.toInt()
        style = Paint.Style.FILL
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    @Volatile
    private var observation = FireDetectionObservation(enabled = false, active = false)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    fun update(next: FireDetectionObservation) {
        observation = next
        removeCallbacks(clearStaleBoxes)
        postInvalidateOnAnimation()
        if (next.sourceTs > 0L) {
            postDelayed(clearStaleBoxes, STALE_AFTER_MS)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snapshot = observation
        drawStatus(canvas, snapshot)
        if (!snapshot.active || isStale(snapshot)) return
        snapshot.detections.forEach { drawDetection(canvas, it) }
    }

    private fun drawStatus(canvas: Canvas, snapshot: FireDetectionObservation) {
        val status = when {
            !snapshot.enabled -> "AI观察：构建未启用"
            snapshot.failureMessage != null -> "AI观察：异常 ${snapshot.failureMessage}"
            !snapshot.active -> "AI观察：待启动"
            snapshot.inferenceMs == null -> "AI观察：等待可见光帧"
            isStale(snapshot) -> "AI观察：视频帧已中断"
            else -> String.format(
                Locale.US,
                "AI观察：运行中  %d ms  %dx%d  目标 %d",
                snapshot.inferenceMs,
                snapshot.sourceWidth,
                snapshot.sourceHeight,
                snapshot.detections.size,
            )
        }
        val paddingX = 12f * density
        val paddingY = 8f * density
        val textWidth = statusPaint.measureText(status)
        val left = (width - textWidth) / 2f - paddingX
        // Keep the observation pill below UXSDK's camera exposure/status row.
        val top = 58f * density
        val bottom = top + statusPaint.textSize + paddingY * 2f
        canvas.drawRoundRect(left, top, left + textWidth + paddingX * 2f, bottom, 8f * density, 8f * density, statusBackgroundPaint)
        canvas.drawText(status, left + paddingX, bottom - paddingY, statusPaint)
    }

    private fun drawDetection(canvas: Canvas, detection: VisibleDetection) {
        val color = if (detection.label.equals("fire", ignoreCase = true)) FIRE_COLOR else SMOKE_COLOR
        boxPaint.color = color
        labelBackgroundPaint.color = color
        val roi = detection.roi
        val rect = RectF(
            (roi.x * width).toFloat().coerceIn(0f, width.toFloat()),
            (roi.y * height).toFloat().coerceIn(0f, height.toFloat()),
            ((roi.x + roi.width) * width).toFloat().coerceIn(0f, width.toFloat()),
            ((roi.y + roi.height) * height).toFloat().coerceIn(0f, height.toFloat()),
        )
        canvas.drawRect(rect, boxPaint)
        val label = String.format(Locale.US, "%s %.0f%%", detection.label.uppercase(Locale.US), detection.confidence * 100.0)
        val padding = 5f * density
        val labelWidth = labelPaint.measureText(label) + padding * 2f
        val labelHeight = labelPaint.textSize + padding * 2f
        val labelTop = (rect.top - labelHeight).coerceAtLeast(0f)
        canvas.drawRect(rect.left, labelTop, (rect.left + labelWidth).coerceAtMost(width.toFloat()), labelTop + labelHeight, labelBackgroundPaint)
        canvas.drawText(label, rect.left + padding, labelTop + labelPaint.textSize + padding / 2f, labelPaint)
    }

    private fun isStale(snapshot: FireDetectionObservation): Boolean =
        snapshot.sourceTs > 0L && System.currentTimeMillis() - snapshot.sourceTs > STALE_AFTER_MS

    private val clearStaleBoxes = Runnable { invalidate() }

    private companion object {
        const val STALE_AFTER_MS = 1_500L
        const val FIRE_COLOR = 0xFFFF3B30.toInt()
        const val SMOKE_COLOR = 0xFFFFC107.toInt()
    }
}
