package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.firedetection.store.FireEvidenceReference
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PendingVisibleEvidence(
    val ppmBytes: ByteArray,
    val sha256: String,
    val width: Int,
    val height: Int,
    val capturedAtMonotonicMs: Long,
)

interface AgentFireEvidenceCapture {
    fun snapshot(frame: VisibleRgbaFrame, confirmation: VisibleConfirmation): PendingVisibleEvidence
    suspend fun materialize(eventId: String, pending: PendingVisibleEvidence): FireEvidenceReference
}

class BoundedVisibleEvidenceCapture(
    private val directory: File,
    private val elapsedRealtimeMillis: () -> Long,
    private val wallTimeMillis: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxSide: Int = 96,
) : AgentFireEvidenceCapture {
    init { require(maxSide in 16..256) }

    override fun snapshot(frame: VisibleRgbaFrame, confirmation: VisibleConfirmation): PendingVisibleEvidence {
        val roi = confirmation.roi
        val left = (roi.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val top = (roi.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val right = (roi.right * frame.width).toInt().coerceIn(left + 1, frame.width)
        val bottom = (roi.bottom * frame.height).toInt().coerceIn(top + 1, frame.height)
        val width = minOf(maxSide, right - left)
        val height = minOf(maxSide, bottom - top)
        val header = "P6\n$width $height\n255\n".toByteArray(Charsets.US_ASCII)
        val bytes = ByteArray(header.size + width * height * 3)
        header.copyInto(bytes)
        val pixels = frame.pixels
        var destination = header.size
        repeat(height) { y ->
            val sourceY = top + y * (bottom - top) / height
            repeat(width) { x ->
                val sourceX = left + x * (right - left) / width
                val offset = (sourceY * frame.width + sourceX) * 4
                bytes[destination++] = pixels[offset]
                bytes[destination++] = pixels[offset + 1]
                bytes[destination++] = pixels[offset + 2]
            }
        }
        return PendingVisibleEvidence(bytes, sha256(bytes), width, height, frame.capturedAtMillis)
    }

    override suspend fun materialize(eventId: String, pending: PendingVisibleEvidence): FireEvidenceReference =
        withContext(io) {
            val safeEvent = eventId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            check(safeEvent.isNotBlank() && (directory.isDirectory || directory.mkdirs()))
            val target = File(directory, "$safeEvent-${pending.sha256}.ppm")
            if (!target.exists()) {
                val temporary = File.createTempFile(".$safeEvent-", ".tmp", directory)
                try {
                    FileOutputStream(temporary).use { output ->
                        output.write(pending.ppmBytes)
                        output.fd.sync()
                    }
                    check(temporary.renameTo(target) || target.exists())
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
            }
            check(target.length() == pending.ppmBytes.size.toLong())
            check(sha256(target.readBytes()) == pending.sha256)
            val elapsed = elapsedRealtimeMillis()
            require(pending.capturedAtMonotonicMs in 0..elapsed)
            val capturedWall = (wallTimeMillis() - (elapsed - pending.capturedAtMonotonicMs))
                .coerceAtLeast(0)
            FireEvidenceReference(
                target.absolutePath,
                pending.sha256,
                "image/x-portable-pixmap",
                capturedWall,
                pending.ppmBytes.size.toLong(),
                pending.width,
                pending.height,
            )
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
