package com.yinxin.uavfir.firedetection

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleConfirmationTrackerTest {
    private val policy = VisibleConfirmationPolicy(
        maxCenterDistance = 0.08,
        fireConfidence = 0.70f,
        smokeConfidence = 0.65f,
        nmsIou = VisibleDetectorContract.NMS_IOU_THRESHOLD,
    )

    @Test
    fun confirmsFireOnlyAfterTwoFreshNearbyFireColoredFrames() {
        val tracker = VisibleConfirmationTracker(policy)

        assertNull(tracker.observe(input(1_000, 1_100, fire(), fireColored = true)).confirmation)
        val outcome = tracker.observe(input(1_200, 1_300, fire(left = 0.22f), fireColored = true))

        assertEquals(DetectionKind.FIRE, outcome.confirmation?.kind)
        assertEquals(listOf(1_000L, 1_200L), outcome.confirmation?.frameTimestampsMillis)
    }

    @Test
    fun exactlyMaximumAgeIsFreshButOlderFrameResetsSequence() {
        val tracker = VisibleConfirmationTracker(policy)

        assertNull(tracker.observe(input(1_000, 1_001, smoke())).confirmation)
        assertEquals(
            DetectionKind.SMOKE,
            tracker.observe(input(1_200, 1_300, smoke(left = 0.22f))).confirmation?.kind,
        )

        assertNull(tracker.observe(input(2_000, 2_301, smoke())).confirmation)
        assertNull(tracker.observe(input(2_200, 2_400, smoke(left = 0.22f))).confirmation)
    }

    @Test
    fun fireColorFailureResetsFireButNeverRejectsSmoke() {
        val fireTracker = VisibleConfirmationTracker(policy)
        assertNull(fireTracker.observe(input(1_000, 1_050, fire(), fireColored = true)).confirmation)
        assertNull(fireTracker.observe(input(1_100, 1_150, fire(), fireColored = false)).confirmation)
        assertNull(fireTracker.observe(input(1_200, 1_250, fire(), fireColored = true)).confirmation)

        val smokeTracker = VisibleConfirmationTracker(policy)
        assertNull(smokeTracker.observe(input(2_000, 2_050, smoke(), fireColored = false)).confirmation)
        assertEquals(
            DetectionKind.SMOKE,
            smokeTracker.observe(input(2_100, 2_150, smoke(left = 0.22f), fireColored = false))
                .confirmation?.kind,
        )
    }

    @Test
    fun unhealthyInputsMissingCandidatesKindChangesAndLargeJumpsReset() {
        val healthFields = listOf<(ConfirmationHealth) -> ConfirmationHealth>(
            { it.copy(frameHealthy = false) },
            { it.copy(modelHealthy = false) },
            { it.copy(runtimeHealthy = false) },
            { it.copy(storeHealthy = false) },
        )
        healthFields.forEach { unhealthy ->
            val tracker = VisibleConfirmationTracker(policy)
            assertNull(tracker.observe(input(1_000, 1_050, fire(), fireColored = true)).confirmation)
            assertNull(
                tracker.observe(
                    input(
                        1_100,
                        1_150,
                        fire(),
                        health = unhealthy(defaultHealth()),
                        fireColored = true,
                    ),
                ).confirmation,
            )
            assertNull(tracker.observe(input(1_200, 1_250, fire(), fireColored = true)).confirmation)
        }

        val tracker = VisibleConfirmationTracker(policy)
        assertNull(tracker.observe(input(2_000, 2_050, fire(), fireColored = true)).confirmation)
        assertNull(tracker.observe(input(2_100, 2_150)).confirmation)
        assertNull(tracker.observe(input(2_200, 2_250, fire(), fireColored = true)).confirmation)
        assertNull(tracker.observe(input(2_300, 2_350, smoke())).confirmation)
        assertNull(tracker.observe(input(2_400, 2_450, smoke(left = 0.70f))).confirmation)
        assertEquals(
            DetectionKind.SMOKE,
            tracker.observe(input(2_500, 2_550, smoke(left = 0.71f))).confirmation?.kind,
        )
    }

    @Test
    fun nonIncreasingAndFutureTimestampsFailClosedAndConfirmationEmitsOnce() {
        val tracker = VisibleConfirmationTracker(policy)
        assertNull(tracker.observe(input(1_000, 1_050, smoke())).confirmation)
        assertNull(tracker.observe(input(1_000, 1_060, smoke(left = 0.22f))).confirmation)
        assertNull(tracker.observe(input(1_100, 1_050, smoke())).confirmation)
        assertNull(tracker.observe(input(1_200, 1_250, smoke())).confirmation)
        assertEquals(
            DetectionKind.SMOKE,
            tracker.observe(input(1_300, 1_350, smoke(left = 0.22f))).confirmation?.kind,
        )
        assertNull(tracker.observe(input(1_300, 1_360, smoke(left = 0.22f))).confirmation)
        assertNull(tracker.observe(input(1_400, 1_450, smoke(left = 0.22f))).confirmation)
    }

    @Test
    fun choosesHighestConfidenceCandidateAndUsesRgbaFireColorGate() {
        val tracker = VisibleConfirmationTracker(policy)
        val low = fire(left = 0.05f, confidence = 0.71f)
        val high = fire(left = 0.60f, confidence = 0.92f)

        assertNull(tracker.observe(input(1_000, 1_050, low, high, fireColored = true)).confirmation)
        val result = tracker.observe(
            input(1_100, 1_150, low.copy(left = 0.07f, right = 0.27f), high.copy(left = 0.61f, right = 0.81f), fireColored = true),
        )

        assertEquals(0.61f, result.confirmation?.roi?.left ?: -1f, 0.0001f)
        assertTrue(result.confirmation?.confidence ?: 0f >= 0.92f)
        assertFalse(result.reset)
    }

    @Test
    fun fireColorGateMatchesSharedPythonOpenCvBgrFixture() {
        val fixture = JsonParser.parseReader(
            File("../../test-fixtures/visible-fire-color-bgr.json").reader(),
        ).asJsonObject
        fixture.getAsJsonArray("cases").forEach { element ->
            val case = element.asJsonObject
            val bgr = case.getAsJsonArray("bgr").map { it.asInt }
            val rgba = intArrayOf(bgr[2], bgr[1], bgr[0], 255)
            val tracker = VisibleConfirmationTracker(policy)

            tracker.observe(inputWithRgba(1_000, 1_050, fire(), rgba))
            val confirmation = tracker.observe(inputWithRgba(1_100, 1_150, fire(left = 0.22f), rgba))

            assertEquals(
                case.get("name").asString,
                case.get("expected").asBoolean,
                confirmation.confirmation?.kind == DetectionKind.FIRE,
            )
        }
    }

    @Test
    fun belowThresholdCandidateResetsSequence() {
        val tracker = VisibleConfirmationTracker(policy)
        assertNull(tracker.observe(input(1_000, 1_050, smoke())).confirmation)
        assertNull(
            tracker.observe(input(1_100, 1_150, smoke(confidence = policy.smokeConfidence - 0.01f)))
                .confirmation,
        )
        assertNull(tracker.observe(input(1_200, 1_250, smoke())).confirmation)
    }

    @Test
    fun revalidatesFirstFrameFreshnessAtSecondObservationInclusiveBoundary() {
        val inclusive = VisibleConfirmationTracker(policy)
        assertNull(inclusive.observe(input(1_000, 1_001, smoke())).confirmation)
        assertEquals(
            DetectionKind.SMOKE,
            inclusive.observe(input(1_200, 1_300, smoke(left = 0.22f))).confirmation?.kind,
        )

        val expired = VisibleConfirmationTracker(policy)
        assertNull(expired.observe(input(2_000, 2_001, smoke())).confirmation)
        val reset = expired.observe(input(2_200, 2_301, smoke(left = 0.22f)))
        assertNull(reset.confirmation)
        assertTrue(reset.reset)
        assertEquals(
            DetectionKind.SMOKE,
            expired.observe(input(2_300, 2_302, smoke(left = 0.23f))).confirmation?.kind,
        )
    }

    @Test
    fun invalidFutureAndMismatchedInputsDoNotPoisonAcceptedTimestampWatermarks() {
        val tracker = VisibleConfirmationTracker(policy)
        val futureFrame = solidFrame(9_999_999, intArrayOf(20, 20, 20, 255))
        assertNull(
            tracker.observe(
                VisibleConfirmationInput(
                    frame = futureFrame,
                    result = VisibleDetectionResult(9_999_999, listOf(smoke())),
                    observedAtMillis = 100,
                    health = defaultHealth(),
                ),
            ).confirmation,
        )
        val mismatchedFrame = solidFrame(150, intArrayOf(20, 20, 20, 255))
        assertNull(
            tracker.observe(
                VisibleConfirmationInput(
                    frame = mismatchedFrame,
                    result = VisibleDetectionResult(8_888_888, listOf(smoke())),
                    observedAtMillis = 200,
                    health = defaultHealth(),
                ),
            ).confirmation,
        )

        assertNull(tracker.observe(input(300, 350, smoke())).confirmation)
        assertEquals(
            DetectionKind.SMOKE,
            tracker.observe(input(400, 450, smoke(left = 0.22f))).confirmation?.kind,
        )
        assertNull(tracker.observe(input(400, 460, smoke(left = 0.22f))).confirmation)
    }

    @Test
    fun observationClockMustIncreaseButInvalidObservationDoesNotPoisonWatermark() {
        val tracker = VisibleConfirmationTracker(policy)
        assertNull(tracker.observe(input(1_000, 1_100, smoke())).confirmation)
        assertNull(tracker.observe(input(1_050, 1_100, smoke(left = 0.21f))).confirmation)
        assertNull(tracker.observe(input(1_100, 1_099, smoke(left = 0.22f))).confirmation)
        assertNull(tracker.observe(input(1_200, 1_201, smoke(left = 0.22f))).confirmation)
        assertEquals(
            DetectionKind.SMOKE,
            tracker.observe(input(1_300, 1_301, smoke(left = 0.23f))).confirmation?.kind,
        )
    }

    @Test
    fun coherentCandidateFreeFrameAdvancesWatermarkAndRejectsOlderReplay() {
        val tracker = VisibleConfirmationTracker(policy)
        assertNull(tracker.observe(input(100, 150, smoke())).confirmation)
        assertNull(tracker.observe(input(300, 350)).confirmation)
        assertNull(tracker.observe(input(200, 250, smoke(left = 0.21f))).confirmation)
        assertNull(tracker.observe(input(250, 260, smoke(left = 0.22f))).confirmation)
        assertNull(tracker.observe(input(400, 450, smoke(left = 0.23f))).confirmation)
        assertEquals(
            DetectionKind.SMOKE,
            tracker.observe(input(500, 550, smoke(left = 0.24f))).confirmation?.kind,
        )
    }

    private fun input(
        capturedAt: Long,
        observedAt: Long,
        vararg detections: VisibleDetection,
        health: ConfirmationHealth = defaultHealth(),
        fireColored: Boolean = true,
    ): VisibleConfirmationInput {
        val frame = solidFrame(capturedAt, if (fireColored) intArrayOf(220, 80, 10, 255) else intArrayOf(20, 20, 20, 255))
        return VisibleConfirmationInput(
            frame = frame,
            result = VisibleDetectionResult(capturedAt, detections.toList()),
            observedAtMillis = observedAt,
            health = health,
        )
    }

    private fun solidFrame(capturedAt: Long, rgba: IntArray): VisibleRgbaFrame {
        val pixels = ByteArray(20 * 20 * 4)
        for (offset in pixels.indices step 4) {
            pixels[offset] = rgba[0].toByte()
            pixels[offset + 1] = rgba[1].toByte()
            pixels[offset + 2] = rgba[2].toByte()
            pixels[offset + 3] = rgba[3].toByte()
        }
        return VisibleRgbaFrame(pixels, 20, 20, capturedAt)
    }

    private fun inputWithRgba(
        capturedAt: Long,
        observedAt: Long,
        detection: VisibleDetection,
        rgba: IntArray,
    ) = VisibleConfirmationInput(
        frame = solidFrame(capturedAt, rgba),
        result = VisibleDetectionResult(capturedAt, listOf(detection)),
        observedAtMillis = observedAt,
        health = defaultHealth(),
    )

    private fun fire(
        left: Float = 0.20f,
        confidence: Float = 0.90f,
    ) = detection("fire", 0, left, confidence)

    private fun smoke(
        left: Float = 0.20f,
        confidence: Float = 0.85f,
    ) = detection("smoke", 1, left, confidence)

    private fun detection(name: String, index: Int, left: Float, confidence: Float) = VisibleDetection(
        left = left,
        top = 0.20f,
        right = left + 0.20f,
        bottom = 0.40f,
        confidence = confidence,
        classIndex = index,
        className = name,
    )

    private fun defaultHealth() = ConfirmationHealth(
        frameHealthy = true,
        modelHealthy = true,
        runtimeHealthy = true,
        storeHealthy = true,
    )
}
