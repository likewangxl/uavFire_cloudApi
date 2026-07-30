package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.api.DjiLocalTargetAlignmentAction
import com.yinxin.uavfir.api.TapZoomClient
import com.yinxin.uavfir.api.VisibleFireTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVisibleTargetAimerTest {
    @Test
    fun acceptsOnlyPostActionSameGenerationHealthySameKindDetectionNearestPriorTarget() = runTest {
        val action = RecordingAlignmentAction(
            receipts = ArrayDeque(
                listOf(TargetActionReceipt(100, 7, 0.52, 0.52)),
            ),
        )
        val source = SequenceLocalDetectionSource(
            ArrayDeque(
                listOf(
                    observation(100, 7), // equal timestamp is not post-action
                    observation(101, 8), // wrong source generation
                    observation(102, 7, healthy = false),
                    observation(
                        capturedAt = 103,
                        generation = 7,
                        detections = listOf(
                            detection("smoke", .95f, .45f, .45f, .60f, .60f),
                            detection("fire", .99f, .75f, .75f, .90f, .90f),
                            detection("fire", .70f, .47f, .47f, .57f, .57f),
                        ),
                    ),
                ),
            ),
        )
        val aimer = LocalVisibleTargetAimer(action, source, minimumConfidence = .6f)

        val result = aimer.align(request())

        assertTrue(result is LocalTargetAimResult.Aligned)
        result as LocalTargetAimResult.Aligned
        assertEquals(7, result.sourceGeneration)
        assertEquals(.52, result.roi.centerX, .0001)
        assertEquals(1, action.calls)
        assertEquals(4, source.requests.size)
        assertTrue(source.requests.all { it.kind == DetectionKind.FIRE })
    }

    @Test
    fun performsAtMostThreeCyclesAndRequiresReticleInsideNewestRoi() = runTest {
        val action = RecordingAlignmentAction(
            ArrayDeque(
                listOf(
                    TargetActionReceipt(10, 1, .1, .1),
                    TargetActionReceipt(20, 1, .1, .1),
                    TargetActionReceipt(30, 1, .1, .1),
                    TargetActionReceipt(40, 1, .5, .5),
                ),
            ),
        )
        val source = SequenceLocalDetectionSource(
            ArrayDeque(
                listOf(
                    observation(11, 1),
                    observation(21, 1),
                    observation(31, 1),
                    observation(41, 1),
                ),
            ),
        )

        val result = LocalVisibleTargetAimer(action, source).align(request())

        assertEquals(LocalTargetAimFailure.RETICLE_OUTSIDE_ROI, (result as LocalTargetAimResult.Failed).reason)
        assertEquals(3, action.calls)
        assertEquals(3, source.requests.size)
    }

    @Test
    fun preservesSmokeKindThroughFullAlignment() = runTest {
        val action = RecordingAlignmentAction(
            ArrayDeque(listOf(TargetActionReceipt(50, 3, .52, .52))),
        )
        val source = SequenceLocalDetectionSource(
            ArrayDeque(listOf(observation(51, 3, kind = DetectionKind.SMOKE))),
        )
        val result = LocalVisibleTargetAimer(action, source).align(
            request().copy(kind = DetectionKind.SMOKE),
        )

        assertEquals(DetectionKind.SMOKE, (result as LocalTargetAimResult.Aligned).kind)
    }

    @Test
    fun hardwareActionTimestampIsAfterTapCompletionAndRejectsSourceSwitchRace() = runTest {
        var generation = 4L
        var now = 10L
        val action = DjiLocalTargetAlignmentAction(
            tapZoomClient = TapZoomClient { _, _ ->
                now = 20L
            },
            currentVisibleGeneration = { generation },
            time = object : VisibleFireTime {
                override fun nowMs(): Long = now
                override suspend fun delayMs(durationMs: Long) = Unit
            },
        )
        assertEquals(20L, action.alignAt(.5, .5).completedAtMonotonicMs)

        val switched = DjiLocalTargetAlignmentAction(
            tapZoomClient = TapZoomClient { _, _ -> generation = 5L },
            currentVisibleGeneration = { generation },
        )
        assertTrue(runCatching { switched.alignAt(.5, .5) }.isFailure)
    }

    private fun request() = LocalTargetAimRequest(
        sessionId = "session-1",
        eventId = "event-1",
        kind = DetectionKind.FIRE,
        priorRoi = NormalizedRoi(.4f, .4f, .6f, .6f),
    )

    private fun observation(
        capturedAt: Long,
        generation: Long,
        kind: DetectionKind = DetectionKind.FIRE,
        healthy: Boolean = true,
        detections: List<VisibleDetection> = listOf(
            detection(kind.name.lowercase(), .8f, .47f, .47f, .57f, .57f),
        ),
    ) = LocalVisibleDetectionObservation(
        sessionId = "session-1",
        eventId = "event-1",
        sourceGeneration = generation,
        capturedAtMonotonicMs = capturedAt,
        healthy = healthy,
        detections = detections,
    )

    private fun detection(
        name: String,
        confidence: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = VisibleDetection(left, top, right, bottom, confidence, if (name == "fire") 0 else 1, name)

    private class RecordingAlignmentAction(
        private val receipts: ArrayDeque<TargetActionReceipt>,
    ) : LocalTargetAlignmentAction {
        var calls = 0
        override suspend fun alignAt(x: Double, y: Double): TargetActionReceipt {
            calls++
            return receipts.removeFirst()
        }
    }

    private class SequenceLocalDetectionSource(
        private val observations: ArrayDeque<LocalVisibleDetectionObservation>,
    ) : LocalVisibleDetectionSource {
        val requests = mutableListOf<LocalDetectionAwaitRequest>()
        override suspend fun await(request: LocalDetectionAwaitRequest): LocalVisibleDetectionObservation? {
            requests += request
            return observations.removeFirstOrNull()
        }
    }
}
