package com.yinxin.uavfir.firedetection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MissionSafetyReviewRegressionTest {
    private val missionA = MissionExecutionKey(MissionIdentity("A", "a.kmz"), 11)
    private val missionB = MissionExecutionKey(MissionIdentity("B", "b.kmz"), 12)
    private val breakpoint = MissionBreakpoint(1, 2, 0.4)
    private val controlA = FireControlSessionKey("fire-a", 21)

    @Test
    fun missionBStateAndCallbackCannotCompleteMissionAPause() = runTest {
        val port = ReviewPort(MissionSnapshot(missionA, ObservedMissionState.EXECUTING, 5), breakpoint)
        val safety = RecordingSafetyProvider()
        val control = AwaitableMissionControl(port, {}, this, FlightSafetyGate(), safety) { 1_000 }
        val pending = async { control.pause() }
        advanceUntilIdle()
        port.switchMission(MissionSnapshot(missionB, ObservedMissionState.INTERRUPTED, 6))
        port.succeedPauseFor(missionB, 6)
        advanceUntilIdle()
        port.succeedPauseFor(missionA, 5)
        assertEquals(
            MissionHoldResult.ManualHold(FlightSafetyReason.MISSION_IDENTITY_MISMATCH),
            pending.await(),
        )
        control.close()
    }

    @Test
    fun targetTransitionInSubscribeGapIsReplayed() = runTest {
        val port = ReviewPort(MissionSnapshot(missionA, ObservedMissionState.EXECUTING, 5), breakpoint)
        port.beforeObserve = {
            port.switchMission(MissionSnapshot(missionA, ObservedMissionState.INTERRUPTED, 6))
        }
        val control = AwaitableMissionControl(port, {}, this, FlightSafetyGate(), RecordingSafetyProvider()) { 1_000 }
        val pending = async { control.pause() }
        advanceUntilIdle()
        port.succeedPauseFor(missionA, 7)
        assertTrue(pending.await() is MissionHoldResult.WaylinePaused)
        assertEquals(0, port.listenerCount)
        control.close()
    }

    @Test
    fun submittedResumeCancellationIsOutcomeUnknownAndAbaNeverReissues() = runTest {
        val port = ReviewPort(MissionSnapshot(missionA, ObservedMissionState.INTERRUPTED, 7), breakpoint)
        val safety = RecordingSafetyProvider().apply {
            evidence = validEvidence(controlA, missionA, breakpoint)
        }
        val control = AwaitableMissionControl(port, {}, this, safety.gate, safety) { 1_000 }
        val tokenA = MissionHoldToken(missionA, breakpoint)
        val first = async { control.resume(tokenA, controlA) }
        advanceUntilIdle()
        assertEquals(1, port.resumeCalls)
        first.cancelAndJoin()

        val tokenB = MissionHoldToken(missionB, breakpoint)
        port.switchMission(MissionSnapshot(missionB, ObservedMissionState.INTERRUPTED, 8))
        safety.evidence = validEvidence(FireControlSessionKey("fire-b", 22), missionB, breakpoint)
        val second = async { control.resume(tokenB, FireControlSessionKey("fire-b", 22)) }
        advanceUntilIdle()
        second.cancelAndJoin()

        port.switchMission(MissionSnapshot(missionA, ObservedMissionState.INTERRUPTED, 7))
        safety.evidence = validEvidence(controlA, missionA, breakpoint)
        assertEquals(
            MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_OUTCOME_UNKNOWN),
            control.resume(tokenA, controlA),
        )
        assertEquals(2, port.resumeCalls)
        control.close()
    }

    @Test
    fun sdkThrowAfterSubmissionBoundaryIsOutcomeUnknownAndNeverReissued() = runTest {
        val port = ReviewPort(MissionSnapshot(missionA, ObservedMissionState.INTERRUPTED, 7), breakpoint)
        port.throwAfterResumeBoundary = true
        val safety = RecordingSafetyProvider().apply {
            evidence = validEvidence(controlA, missionA, breakpoint)
        }
        val control = AwaitableMissionControl(port, {}, this, safety.gate, safety) { 1_000 }
        val token = MissionHoldToken(missionA, breakpoint)

        assertEquals(
            MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_OUTCOME_UNKNOWN),
            control.resume(token, controlA),
        )
        assertEquals(
            MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_OUTCOME_UNKNOWN),
            control.resume(token, controlA),
        )
        assertEquals(1, port.resumeCalls)
        control.close()
    }

    @Test
    fun differentFireSessionGenerationCannotCoalesceIntoSubmittedResume() = runTest {
        val port = ReviewPort(MissionSnapshot(missionA, ObservedMissionState.INTERRUPTED, 7), breakpoint)
        val safety = RecordingSafetyProvider().apply {
            evidence = validEvidence(controlA, missionA, breakpoint)
        }
        val control = AwaitableMissionControl(port, {}, this, safety.gate, safety) { 1_000 }
        val token = MissionHoldToken(missionA, breakpoint)
        val first = async { control.resume(token, controlA) }
        advanceUntilIdle()

        assertEquals(
            MissionResumeResult.ManualHold(FlightSafetyReason.COMPETING_FIRE_SESSION),
            control.resume(token, FireControlSessionKey(controlA.sessionId, controlA.generation + 1)),
        )
        assertEquals(1, port.resumeCalls)
        first.cancelAndJoin()
        control.close()
    }

    @Test
    fun staleCallerCannotSupplySafetyAndProviderIsReadImmediatelyBeforeSubmit() = runTest {
        val port = ReviewPort(MissionSnapshot(missionA, ObservedMissionState.INTERRUPTED, 7), breakpoint)
        val safety = RecordingSafetyProvider().apply {
            evidence = validEvidence(controlA, missionA, breakpoint)
            onRead = { evidence = evidence!!.copy(laserEnabled = true) }
        }
        val control = AwaitableMissionControl(port, {}, this, safety.gate, safety) { 1_000 }

        assertEquals(
            MissionResumeResult.ManualHold(FlightSafetyReason.LASER_ENABLED),
            control.resume(MissionHoldToken(missionA, breakpoint), controlA),
        )
        assertEquals(0, port.resumeCalls)
        assertEquals(1, safety.reads)
        control.close()
    }

    @Test
    fun movingTelemetryAndInvalidatedStableProofBlockResume() {
        val provider = RecordingSafetyProvider()
        val evidence = validEvidence(controlA, missionA, breakpoint)
        provider.evidence = evidence.copy(
            telemetry = evidence.telemetry!!.copy(horizontalSpeedMps = 0.31),
        )
        assertEquals(
            ResumeSafetyDecision.ManualHold(FlightSafetyReason.TELEMETRY_STALE),
            provider.gate.evaluateResume(controlA, missionA, breakpoint, provider.evidence, 1_000),
        )

        val refreshed = validEvidence(controlA, missionA, breakpoint)
        provider.gate.observeHover(
            HoverControlBinding(controlA, missionA),
            0,
            FlightTelemetrySample(1_100, 1_100, 0.31, 0.0),
        )
        assertEquals(
            ResumeSafetyDecision.ManualHold(FlightSafetyReason.HOVER_NOT_STABLE),
            provider.gate.evaluateResume(controlA, missionA, breakpoint, refreshed, 1_100),
        )
    }

    @Test
    fun malformedBreakpointsAndClockRegressionFailClosed() {
        assertFalse(MissionBreakpoint(-1, 0, 0.0).isValid)
        assertFalse(MissionBreakpoint(1, -1, 0.0).isValid)
        assertFalse(MissionBreakpoint(1, 0, -0.01).isValid)
        assertFalse(MissionBreakpoint(1, 0, 1.01).isValid)
        assertFalse(MissionBreakpoint(1, 0, 0.5, 91.0, 0.0, 1.0).isValid)
        assertFalse(MissionBreakpoint(1, 0, 0.5, 0.0, 181.0, 1.0).isValid)
        assertFalse(MissionBreakpoint(1, 0, 0.5, 0.0, 0.0, Double.NaN).isValid)
        assertFalse(MissionBreakpoint(1, 0, 0.5, recoverAction = "UNKNOWN").isValid)

        val gate = FlightSafetyGate()
        val binding = HoverControlBinding(controlA, missionA)
        gate.observeHover(binding, 100, sample(100), nowMs = 100)
        assertEquals(
            HoverSafetyDecision.ManualHold(FlightSafetyReason.TELEMETRY_STALE),
            gate.observeHover(binding, 100, sample(99), nowMs = 99),
        )
        assertEquals(
            HoverSafetyDecision.ManualHold(FlightSafetyReason.TELEMETRY_STALE),
            gate.observeHover(binding, 100, sample(110), nowMs = 111),
        )
    }

    @Test
    fun ownedProviderRejectsStaleOrCrossGenerationEvidenceAndInvalidatesItself() {
        val provider = OwnedResumeSafetyEvidenceProvider()
        val first = validEvidence(controlA, missionA, breakpoint)
        assertTrue(provider.publish(first))
        assertEquals(first, provider.current(controlA))

        assertFalse(provider.publish(first.copy(observedAtMonotonicMs = 999)))
        assertEquals(null, provider.current(controlA))

        assertTrue(provider.publish(first))
        val nextControl = FireControlSessionKey(controlA.sessionId, controlA.generation + 1)
        val nextGeneration = first.copy(
            controlSession = nextControl,
            stableHoverEvidence = null,
            observedAtMonotonicMs = 1_001,
        )
        assertFalse(provider.publish(nextGeneration))
        assertEquals(null, provider.current(controlA))
        assertEquals(null, provider.current(nextControl))

        assertTrue(provider.publish(nextGeneration))
        assertFalse(provider.invalidate(controlA))
        assertEquals(nextGeneration, provider.current(nextControl))
        assertTrue(provider.invalidate(nextControl))
        assertEquals(null, provider.current(nextControl))
    }

    private fun sample(now: Long) = FlightTelemetrySample(now, now, 0.0, 0.0)

    private fun validEvidence(
        control: FireControlSessionKey,
        mission: MissionExecutionKey,
        breakpoint: MissionBreakpoint,
    ): ResumeSafetyEvidence {
        val binding = HoverControlBinding(control, mission)
        val gate = RecordingSafetyProvider.sharedGate
        gate.reset()
        gate.observeHover(binding, 0, sample(0))
        gate.observeHover(binding, 0, sample(500))
        val stable = gate.observeHover(binding, 0, sample(1_000)) as HoverSafetyDecision.Stable
        return ResumeSafetyEvidence(
            controlSession = control,
            mission = mission,
            breakpoint = breakpoint,
            terminalResultDurable = true,
            laserEnabled = false,
            targetAlignmentClosed = true,
            anotherFireSessionActive = false,
            signals = FlightSafetySignals(),
            telemetry = sample(1_000),
            stableHoverEvidence = stable.evidence,
            observedAtMonotonicMs = 1_000,
        )
    }

    private class RecordingSafetyProvider : ResumeSafetyEvidenceProvider {
        var evidence: ResumeSafetyEvidence? = null
        var reads = 0
        var onRead: (() -> Unit)? = null
        val gate = sharedGate

        override fun current(controlSession: FireControlSessionKey): ResumeSafetyEvidence? {
            reads++
            onRead?.invoke()
            return evidence
        }

        companion object {
            val sharedGate = FlightSafetyGate()
        }
    }

    private class ReviewPort(
        private var current: MissionSnapshot,
        private var breakpoint: MissionBreakpoint?,
    ) : MissionControlPort {
        var beforeObserve: (() -> Unit)? = null
        var resumeCalls = 0
        var throwAfterResumeBoundary = false
        private var callback: ((MissionCommandCallback) -> Unit)? = null
        private val listeners = linkedSetOf<(MissionSnapshot) -> Unit>()
        val listenerCount get() = listeners.size

        override fun snapshot() = current

        override fun queryBreakpoint(
            mission: MissionExecutionKey,
            callback: (MissionBreakpoint?, MissionCommandError?) -> Unit,
        ): MissionCancellation {
            callback(breakpoint, null)
            return MissionCancellation {}
        }

        override fun pause(
            mission: MissionExecutionKey,
            onSubmissionBoundary: (MissionCommandSubmission) -> Unit,
            callback: (MissionCommandCallback) -> Unit,
        ): MissionCommandSubmission {
            this.callback = callback
            current = current.copy(commandGeneration = current.commandGeneration + 1)
            listeners.toList().forEach { it(current) }
            return MissionCommandSubmission(mission, current.commandGeneration, MissionCancellation {})
                .also(onSubmissionBoundary)
        }

        override fun resume(
            mission: MissionExecutionKey,
            breakpoint: MissionBreakpoint,
            onSubmissionBoundary: (MissionCommandSubmission) -> Unit,
            callback: (MissionCommandCallback) -> Unit,
        ): MissionCommandSubmission {
            resumeCalls++
            this.callback = callback
            current = current.copy(commandGeneration = current.commandGeneration + 1)
            listeners.toList().forEach { it(current) }
            val submission =
                MissionCommandSubmission(mission, current.commandGeneration, MissionCancellation {})
            onSubmissionBoundary(submission)
            if (throwAfterResumeBoundary) {
                error("SDK failed after accepting resume submission")
            }
            return submission
        }

        override fun observeSnapshots(listener: (MissionSnapshot) -> Unit): MissionCancellation {
            beforeObserve?.also { beforeObserve = null }?.invoke()
            listeners += listener
            listener(current)
            return MissionCancellation { listeners -= listener }
        }

        fun switchMission(snapshot: MissionSnapshot) {
            current = snapshot
            listeners.toList().forEach { it(snapshot) }
        }

        fun succeedPauseFor(mission: MissionExecutionKey, commandGeneration: Long) {
            callback?.invoke(MissionCommandCallback(mission, commandGeneration, null))
        }
    }
}
