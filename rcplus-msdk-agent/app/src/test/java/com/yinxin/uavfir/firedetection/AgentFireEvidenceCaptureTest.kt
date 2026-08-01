package com.yinxin.uavfir.firedetection

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFireEvidenceCaptureTest {
    @Test
    fun `bounded immutable snapshot materializes after RGBA owner is released`() = runTest {
        val directory = Files.createTempDirectory("agent-fire-evidence").toFile()
        try {
            val frame = VisibleRgbaFrame(ByteArray(200 * 100 * 4) { it.toByte() }, 200, 100, 900, sourceGeneration = 7)
            val confirmation = VisibleConfirmation(
                DetectionKind.FIRE, .9f, NormalizedRoi(.1f, .1f, .9f, .9f), 800, 900, "policy-v1",
            )
            val capture = BoundedVisibleEvidenceCapture(
                directory, elapsedRealtimeMillis = { 1_000 }, wallTimeMillis = { 10_000 },
                io = Dispatchers.Unconfined,
            )
            val pending = capture.snapshot(frame, confirmation)
            frame.release()
            val reference = capture.materialize("event-1", pending)

            assertTrue(pending.width <= 96 && pending.height <= 96)
            assertEquals(9_900, reference.capturedAtWallMillis)
            assertEquals(pending.sha256, reference.sha256)
            assertTrue(java.io.File(reference.path).isFile)
        } finally {
            directory.deleteRecursively()
        }
    }
}
