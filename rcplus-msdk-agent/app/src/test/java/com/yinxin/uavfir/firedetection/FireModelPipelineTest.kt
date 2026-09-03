package com.yinxin.uavfir.firedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class FireModelPipelineTest {
    @Test
    fun preprocessorProducesRgbChwAndLetterboxPadding() {
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 255.toByte(),
        )

        val output = RgbaLetterboxPreprocessor.preprocess(rgba, width = 2, height = 1, inputSize = 2)

        assertEquals(12, output.tensor.size)
        assertEquals(0.0, output.padY, 0.0)
        assertEquals(1f, output.tensor[0], 0.0001f)
        assertEquals(1f, output.tensor[4 + 1], 0.0001f)
    }

    @Test
    fun preprocessorReusesCallerOwnedTensor() {
        val tensor = FloatArray(12)
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 255.toByte(),
        )

        val first = RgbaLetterboxPreprocessor.preprocess(rgba, 2, 1, 2, tensor)
        val second = RgbaLetterboxPreprocessor.preprocess(rgba, 2, 1, 2, tensor)

        assertTrue(first.tensor === tensor)
        assertTrue(second.tensor === tensor)
        assertEquals(1f, tensor[0], 0.0001f)
    }

    @Test
    fun postprocessorDecodesRoiAndSuppressesSameClassOverlap() {
        val channels = Array(6) { FloatArray(2) }
        channels[0][0] = 208f
        channels[1][0] = 208f
        channels[2][0] = 208f
        channels[3][0] = 208f
        channels[4][0] = 0.9f
        channels[0][1] = 210f
        channels[1][1] = 210f
        channels[2][1] = 208f
        channels[3][1] = 208f
        channels[4][1] = 0.8f
        val letterbox = LetterboxResult(FloatArray(0), 416, 416, 416, 416, 1.0, 0.0, 0.0)

        val detections = YoloV8Postprocessor.decode(channels, letterbox)

        assertEquals(1, detections.size)
        assertEquals("fire", detections.single().label)
        assertEquals(0.25, detections.single().roi.x, 0.0001)
        assertEquals(0.50, detections.single().roi.width, 0.0001)
    }

    @Test
    fun postprocessorDecodesReusableFlatOutputBuffer() {
        val candidateCount = 2
        val output = ByteBuffer.allocateDirect(6 * candidateCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        output.put(0, 208f)
        output.put(candidateCount, 208f)
        output.put(candidateCount * 2, 208f)
        output.put(candidateCount * 3, 208f)
        output.put(candidateCount * 4, 0.9f)
        output.put(1, 210f)
        output.put(candidateCount + 1, 210f)
        output.put(candidateCount * 2 + 1, 208f)
        output.put(candidateCount * 3 + 1, 208f)
        output.put(candidateCount * 4 + 1, 0.8f)
        val letterbox = LetterboxResult(FloatArray(0), 416, 416, 416, 416, 1.0, 0.0, 0.0)

        val detections = YoloV8Postprocessor.decode(output, candidateCount, letterbox)

        assertEquals(1, detections.size)
        assertEquals("fire", detections.single().label)
    }

    @Test
    fun visibleModelsUseRequestedPerClassConfidenceThresholds() {
        assertEquals(0.45, AgentFireModelProfiles.VISIBLE_960.confidenceThresholdFor(0), 0.0)
        assertEquals(0.55, AgentFireModelProfiles.VISIBLE_960.confidenceThresholdFor(1), 0.0)
        assertEquals(0.45, AgentFireModelProfiles.VISIBLE_1088.confidenceThresholdFor(0), 0.0)
        assertEquals(0.55, AgentFireModelProfiles.VISIBLE_1088.confidenceThresholdFor(1), 0.0)
        assertEquals(0.25, AgentFireModelProfiles.LEGACY_416.confidenceThresholdFor(0), 0.0)
        assertEquals(0.25, AgentFireModelProfiles.LEGACY_416.confidenceThresholdFor(1), 0.0)
    }

    @Test
    fun trackerRequiresTwoNearbyFramesAndDebouncesReports() {
        val tracker = VisibleDetectionTracker()
        val first = detection(0.8, 0.10)
        val second = detection(0.9, 0.12)

        assertNull(tracker.accept(listOf(first), 1_000L))
        assertEquals(second, tracker.accept(listOf(second), 2_000L))
        tracker.markReported(2_000L)
        assertNull(tracker.accept(listOf(second), 3_000L))
        assertNull(tracker.accept(listOf(second), 11_000L))
        assertEquals(second, tracker.accept(listOf(second), 12_001L))
    }

    @Test
    fun bundledModelsAndManifestsMatchPinnedSha256() {
        listOf(
            AgentFireModelProfiles.LEGACY_416,
            AgentFireModelProfiles.VISIBLE_960,
            AgentFireModelProfiles.VISIBLE_1088,
        ).forEach { profile ->
            assertBundledProfile(profile)
        }
    }

    @Test
    fun modelProfilesResolveExplicitlyAndRejectUnknownNames() {
        assertEquals(416, AgentFireModelProfiles.resolve("legacy416").inputSize)
        assertEquals(960, AgentFireModelProfiles.resolve("VISIBLE960").inputSize)
        assertEquals(1088, AgentFireModelProfiles.resolve("VISIBLE1088").inputSize)
        val failure = runCatching { AgentFireModelProfiles.resolve("unknown") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    private fun assertBundledProfile(profile: AgentFireModelProfile) {
        val model = Paths.get("src/main/assets", profile.assetPath)
        val manifest = Paths.get("src/main/assets", profile.manifestPath)
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(model))
            .joinToString("") { "%02x".format(it) }
        val manifestText = String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)

        assertEquals(profile.modelSha256, sha)
        assertTrue(manifestText.contains(profile.modelSha256))
        assertTrue(manifestText.contains("\"shape\": [1, 3, ${profile.inputSize}, ${profile.inputSize}]"))
        assertTrue(manifestText.contains("\"classes\": [\"fire\", \"smoke\"]"))
    }

    private fun detection(score: Double, x: Double) = VisibleDetection(
        classId = 0,
        label = "fire",
        confidence = score,
        roi = NormalizedRoi(x, 0.20, 0.20, 0.20),
    )
}
