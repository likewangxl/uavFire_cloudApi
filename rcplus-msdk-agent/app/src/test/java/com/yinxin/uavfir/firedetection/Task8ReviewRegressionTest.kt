package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.api.LaserRangefinderResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Task8ReviewRegressionTest {
    @Test
    fun journalRetainsResultPublishedInActionReturnSubscriptionGap() = runTest {
        val journal = VisibleInferenceResultJournal(capacity = 4)
        val aimer = LocalVisibleTargetAimer(
            alignmentAction = LocalTargetAlignmentAction { _, _ ->
                journal.publish(successPublication(captured = 101, started = 102, completed = 103))
                TargetActionReceipt(100, 7, .5, .5)
            },
            detectionSource = journal,
        )

        val result = aimer.align(aimRequest())

        assertTrue(result is LocalTargetAimResult.Aligned)
    }

    @Test
    fun journalSnapshotIsImmutableOrderedAndRejectsOverflow() = runTest {
        val mutable = mutableListOf(detection())
        val publication = successPublication(10, 11, 12, mutable)
        mutable.clear()
        assertEquals(1, publication.detections.size)
        assertTrue(runCatching { successPublication(12, 11, 10) }.isFailure)
        assertTrue(runCatching { successPublication(10, 11, 12, generation = 0) }.isFailure)

        val journal = VisibleInferenceResultJournal(capacity = 2)
        val oldCursor = journal.cursor()
        repeat(3) { index ->
            journal.publish(successPublication(20L + index, 30L + index, 40L + index))
        }
        assertEquals(
            LocalDetectionAwaitFailure.JOURNAL_OVERFLOW,
            (journal.await(awaitRequest(oldCursor)) as LocalDetectionAwaitResult.Failed).reason,
        )
    }

    @Test
    fun detectionTimeoutRemainsTyped() = runTest {
        val journal = VisibleInferenceResultJournal(capacity = 2)
        val result = LocalVisibleTargetAimer(
            LocalTargetAlignmentAction { _, _ -> TargetActionReceipt(100, 7, .5, .5) },
            journal,
            observationTimeoutMs = 1,
        ).align(aimRequest())
        assertEquals(
            LocalTargetAimFailure.DETECTION_TIMEOUT,
            (result as LocalTargetAimResult.Failed).reason,
        )
    }

    @Test
    fun laserValidatorRejectsReusedObservationAndCrossOperationEnvelope() {
        val binding = binding(operation = 2)
        val sample = hardwareSample(binding, operation = 2, sequence = 10, at = 1_000)
        assertEquals(
            LaserValidationFailure.DUPLICATE_OBSERVATION,
            (LaserSampleValidator().validate(binding, listOf(sample, sample, sample)) as
                LaserValidationResult.Invalid).reason,
        )
        val cross = listOf(
            sample,
            hardwareSample(binding, operation = 1, sequence = 11, at = 1_300),
            hardwareSample(binding, operation = 2, sequence = 12, at = 1_600),
        )
        assertEquals(
            LaserValidationFailure.BINDING_MISMATCH,
            (LaserSampleValidator().validate(binding, cross) as LaserValidationResult.Invalid).reason,
        )
    }

    @Test
    fun osdFreshnessAcceptsExactBoundaryAndRejectsOlderOrFutureEvidence() {
        val exact = AircraftOsdSnapshot(34.0, 109.0, 100.0, 10, 8_000)
        assertTrue(exact.isFreshAt(10_000, 2_000))
        assertFalse(exact.copy(capturedAtMonotonicMs = 7_999).isFreshAt(10_000, 2_000))
        assertFalse(exact.copy(capturedAtMonotonicMs = 10_001).isFreshAt(10_000, 2_000))
    }

    @Test
    fun preciseRawSamplesAreDefensivelyUnmodifiable() {
        val binding = binding(operation = 2)
        val valid = LaserSampleValidator().validate(
            binding,
            listOf(
                hardwareSample(binding, 2, 1, 900),
                hardwareSample(binding, 2, 2, 1_200),
                hardwareSample(binding, 2, 3, 1_500),
            ),
        ) as LaserValidationResult.Valid

        assertTrue(
            runCatching { (valid.rawSamples as MutableList<BoundLaserSample>).clear() }
                .exceptionOrNull() is UnsupportedOperationException,
        )
        assertEquals(3, valid.rawSamples.size)
    }

    @Test
    fun localDetectionAwaitRequestRejectsMalformedTrustBoundaryValues() {
        val valid = { session: String, event: String, generation: Long, captured: Long, cursor: Long ->
            LocalDetectionAwaitRequest(
                session,
                event,
                DetectionKind.FIRE,
                generation,
                captured,
                cursor,
            )
        }

        assertTrue(runCatching { valid("", "event", 1, 0, 0) }.isFailure)
        assertTrue(runCatching { valid("session", "", 1, 0, 0) }.isFailure)
        assertTrue(runCatching { valid("session", "event", 0, 0, 0) }.isFailure)
        assertTrue(runCatching { valid("session", "event", 1, -1, 0) }.isFailure)
        assertTrue(runCatching { valid("session", "event", 1, 0, -1) }.isFailure)
    }

    private fun aimRequest() = LocalTargetAimRequest(
        "session", "event", DetectionKind.FIRE, NormalizedRoi(.4f, .4f, .6f, .6f),
    )

    private fun awaitRequest(cursor: Long) = LocalDetectionAwaitRequest(
        "session", "event", DetectionKind.FIRE, 7, 0, cursor,
    )

    private fun successPublication(
        captured: Long,
        started: Long,
        completed: Long,
        detections: List<VisibleDetection> = listOf(detection()),
        generation: Long = 7,
    ) = VisibleInferencePublication(
        sourceGeneration = generation,
        capturedAtMonotonicMs = captured,
        startedAtMonotonicMs = started,
        completedAtMonotonicMs = completed,
        outcome = VisibleInferenceOutcome.SUCCESS,
        health = VisibleInferenceStatus.HEALTHY,
        detections = detections,
        failure = null,
    )

    private fun detection() = VisibleDetection(.4f, .4f, .6f, .6f, .9f, 0, "fire")

    private fun binding(operation: Long) = LaserOperationBinding(
        "session", "event", NormalizedRoi(.4f, .4f, .6f, .6f), 7, operation, 900, 2_000,
    )

    private fun hardwareSample(
        binding: LaserOperationBinding,
        operation: Long,
        sequence: Long,
        at: Long,
    ) = BoundLaserSample(
        binding = binding,
        hardwareOperationGeneration = operation,
        observationSequence = sequence,
        sampledAtMonotonicMs = at,
        measurement = LaserRangefinderResult(34.0, 109.0, 10.0, 60.0, "NORMAL", .5, .5),
    )
}
