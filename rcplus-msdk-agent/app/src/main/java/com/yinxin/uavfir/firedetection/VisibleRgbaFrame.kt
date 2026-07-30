package com.yinxin.uavfir.firedetection

import java.util.concurrent.atomic.AtomicBoolean

/**
 * An owned copy of one MSDK visible-light RGBA_8888 frame.
 *
 * The receiver of a frame owns it and must call [release]. Release is idempotent,
 * drops the owner's reference and invokes the optional hook exactly once.
 */
class VisibleRgbaFrame(
    pixels: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtMillis: Long,
    private val onRelease: () -> Unit = {},
) : AutoCloseable {
    private val released = AtomicBoolean(false)
    @Volatile
    private var ownedPixels: ByteArray? = pixels
    val pixels: ByteArray
        get() = checkNotNull(ownedPixels) { "Visible frame is released" }

    init {
        require(width > 0 && height > 0) { "Visible frame dimensions must be positive" }
        require(pixels.size.toLong() == width.toLong() * height.toLong() * RGBA_CHANNELS) {
            "Visible frame must contain exactly width * height * 4 RGBA bytes"
        }
        require(capturedAtMillis >= 0L) { "Visible frame capture timestamp must be monotonic and non-negative" }
    }

    fun release() {
        if (released.compareAndSet(false, true)) {
            ownedPixels = null
            onRelease()
        }
    }

    override fun close() = release()

    private companion object {
        const val RGBA_CHANNELS = 4L
    }
}

/** Mirrors the Python/OpenCV BGR fire-color gate using MSDK RGBA channel order. */
internal object VisibleFireColor {
    fun isFireColoredRgba(
        red: Int,
        green: Int,
        blue: Int,
        redMin: Int = 180,
        greenMin: Int = 80,
        blueMax: Int = 120,
    ): Boolean = red >= redMin && green >= greenMin && blue <= blueMax && red >= green
}
