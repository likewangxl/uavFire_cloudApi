package com.yinxin.uavfir.firedetection

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

data class EncodedFireEvidence(
    val jpeg: ByteArray,
    val sha256: String,
)

object FireEvidenceEncoder {
    fun encodeRgba(rgba: ByteArray, width: Int, height: Int): EncodedFireEvidence {
        require(width > 0 && height > 0 && rgba.size == width * height * 4)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
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

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }
}
