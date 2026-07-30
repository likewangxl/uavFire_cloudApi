package com.yinxin.uavfir.firedetection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MissionSafetyRereview2RegressionTest {
    private val mission = MissionExecutionKey(MissionIdentity("A", "a.kmz"), 51)
    private val breakpoint = MissionBreakpoint(1, 2, 0.4)
    private val fireControl = FireControlSessionKey("fire-a", 61)

    @Test
    fun legacyResumeThenLegacyPauseCannotReuseOldHoldToken() = runTest {
        val port = HoldPort(MissionSnapshot(mission, ObservedMissionState.EXECUTING, 10), breakpoint)
        val awaitable = AwaitableMissionControl(
            port,
            {},
            this,
            FlightSafetyGate(),
            FailClosedResumeSafetyEvidenceProvider,
        ) { 1_000 }
        val firstPause = async { awaitable.pause() }
        advanceUntilIdle()
        port.completeAwaitablePause()
        val oldToken = (firstPause.await() as MissionHoldResult.WaylinePaused).token
        assertEquals(11, oldToken.pausedCommandGeneration)

        port.emitLegacyCommand(ObservedMissionState.EXECUTING)
        port.emitLegacyCommand(ObservedMissionState.INTERRUPTED)
        val reconciled = (awaitable.pause() as MissionHoldResult.WaylinePaused).token

        assertNotEquals(oldToken, reconciled)
        assertEquals(13, reconciled.pausedCommandGeneration)
        assertEquals(
            MissionResumeResult.ManualHold(FlightSafetyReason.HOLD_COMMAND_GENERATION_MISMATCH),
            awaitable.resume(oldToken, fireControl),
        )
        assertEquals(1, port.pauseCalls)
        assertEquals(0, port.resumeCalls)
        awaitable.close()
    }

    @Test
    fun stopInterleaveGenerationCannotSatisfyAwaitablePause() = runTest {
        val port = HoldPort(MissionSnapshot(mission, ObservedMissionState.EXECUTING, 20), breakpoint)
        val awaitable = AwaitableMissionControl(
            port,
            {},
            this,
            FlightSafetyGate(),
            FailClosedResumeSafetyEvidenceProvider,
        ) { 1_000 }
        val pending = async { awaitable.pause() }
        advanceUntilIdle()
        port.emitLegacyCommand(ObservedMissionState.INTERRUPTED)
        port.succeedAwaitableCallback()

        assertEquals(
            MissionHoldResult.ManualHold(FlightSafetyReason.PAUSE_COMMAND_FAILED),
            pending.await(),
        )
        awaitable.close()
    }

    @Test
    fun holdGenerationMismatchInvalidatesHoverAndResumeEvidence() {
        val gate = FlightSafetyGate()
        val binding = HoverControlBinding(fireControl, mission, pausedCommandGeneration = 30)
        gate.observeHover(binding, 0, sample(0))
        gate.observeHover(binding, 0, sample(500))
        val stable = gate.observeHover(binding, 0, sample(1_000)) as HoverSafetyDecision.Stable
        val evidence = ResumeSafetyEvidence(
            controlSession = fireControl,
            mission = mission,
            breakpoint = breakpoint,
            pausedCommandGeneration = 30,
            terminalResultDurable = true,
            laserEnabled = false,
            targetAlignmentClosed = true,
            anotherFireSessionActive = false,
            signals = FlightSafetySignals(),
            telemetry = sample(1_000),
            stableHoverEvidence = stable.evidence,
            observedAtMonotonicMs = 1_000,
        )

        assertEquals(
            ResumeSafetyDecision.ManualHold(FlightSafetyReason.HOLD_COMMAND_GENERATION_MISMATCH),
            gate.evaluateResume(
                fireControl,
                mission,
                breakpoint,
                pausedCommandGeneration = 31,
                evidence = evidence,
                nowMs = 1_000,
            ),
        )
    }

    @Test
    fun initialInterruptedGenerationZeroReconcilesAndResumesWithExactSafetyProof() = runTest {
        val port = HoldPort(
            MissionSnapshot(mission, ObservedMissionState.INTERRUPTED, 0),
            breakpoint,
        )
        val gate = FlightSafetyGate()
        val provider = OwnedResumeSafetyEvidenceProvider()
        val awaitable = AwaitableMissionControl(port, {}, this, gate, provider) { 1_000 }

        val token = (awaitable.pause() as MissionHoldResult.WaylinePaused).token
        assertEquals(0, token.pausedCommandGeneration)
        assertEquals(0, port.pauseCalls)

        val binding = HoverControlBinding(fireControl, mission, 0)
        gate.observeHover(binding, 0, sample(0))
        gate.observeHover(binding, 0, sample(500))
        val stable = gate.observeHover(binding, 0, sample(1_000)) as HoverSafetyDecision.Stable
        val evidence = ResumeSafetyEvidence(
            controlSession = fireControl,
            mission = mission,
            breakpoint = breakpoint,
            pausedCommandGeneration = 0,
            terminalResultDurable = true,
            laserEnabled = false,
            targetAlignmentClosed = true,
            anotherFireSessionActive = false,
            signals = FlightSafetySignals(),
            telemetry = sample(1_000),
            stableHoverEvidence = stable.evidence,
            observedAtMonotonicMs = 1_000,
        )
        assertTrue(provider.publish(evidence))

        val resumed = async { awaitable.resume(token, fireControl) }
        advanceUntilIdle()
        port.completeResume()
        assertEquals(MissionResumeResult.Resumed, resumed.await())
        assertEquals(1, port.resumeCalls)
        awaitable.close()
    }

    @Test
    fun generationZeroMismatchIsTypedAndNegativeGenerationsRemainRejected() {
        val gate = FlightSafetyGate()
        val binding = HoverControlBinding(fireControl, mission, 0)
        gate.observeHover(binding, 0, sample(0))
        gate.observeHover(binding, 0, sample(500))
        val stable = gate.observeHover(binding, 0, sample(1_000)) as HoverSafetyDecision.Stable
        val evidence = ResumeSafetyEvidence(
            controlSession = fireControl,
            mission = mission,
            breakpoint = breakpoint,
            pausedCommandGeneration = 0,
            terminalResultDurable = true,
            laserEnabled = false,
            targetAlignmentClosed = true,
            anotherFireSessionActive = false,
            signals = FlightSafetySignals(),
            telemetry = sample(1_000),
            stableHoverEvidence = stable.evidence,
            observedAtMonotonicMs = 1_000,
        )
        assertEquals(
            ResumeSafetyDecision.ManualHold(FlightSafetyReason.HOLD_COMMAND_GENERATION_MISMATCH),
            gate.evaluateResume(fireControl, mission, breakpoint, 1, evidence, 1_000),
        )

        assertThrows(IllegalArgumentException::class.java) {
            MissionHoldToken(mission, breakpoint, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HoverControlBinding(fireControl, mission, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            evidence.copy(pausedCommandGeneration = -1)
        }
    }

    private fun sample(now: Long) = FlightTelemetrySample(now, now, 0.0, 0.0)

    private class HoldPort(
        private var current: MissionSnapshot,
        private val breakpoint: MissionBreakpoint,
    ) : MissionControlPort {
        var pauseCalls = 0
        var resumeCalls = 0
        private var callback: ((MissionCommandCallback) -> Unit)? = null
        private var submission: MissionCommandSubmission? = null
        private val listeners = linkedSetOf<(MissionSnapshot) -> Unit>()

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
            pauseCalls++
            this.callback = callback
            current = current.copy(commandGeneration = current.commandGeneration + 1)
            emit(current)
            return MissionCommandSubmission(
                mission,
                current.commandGeneration,
                MissionCancellation {},
            ).also {
                submission = it
                onSubmissionBoundary(it)
            }
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
            emit(current)
            return MissionCommandSubmission(
                mission,
                current.commandGeneration,
                MissionCancellation {},
            ).also {
                submission = it
                onSubmissionBoundary(it)
            }
        }

        override fun observeSnapshots(listener: (MissionSnapshot) -> Unit): MissionCancellation {
            listeners += listener
            listener(current)
            return MissionCancellation { listeners -= listener }
        }

        fun completeAwaitablePause() {
            val submitted = submission!!
            current = current.copy(
                state = ObservedMissionState.INTERRUPTED,
                commandGeneration = submitted.commandGeneration,
            )
            emit(current)
            callback?.invoke(
                MissionCommandCallback(
                    submitted.mission,
                    submitted.commandGeneration,
                    null,
                ),
            )
        }

        fun emitLegacyCommand(state: ObservedMissionState) {
            current = current.copy(
                state = state,
                commandGeneration = current.commandGeneration + 1,
            )
            emit(current)
        }

        fun completeResume() {
            val submitted = submission!!
            current = current.copy(
                state = ObservedMissionState.EXECUTING,
                commandGeneration = submitted.commandGeneration,
            )
            emit(current)
            callback?.invoke(
                MissionCommandCallback(
                    submitted.mission,
                    submitted.commandGeneration,
                    null,
                ),
            )
        }

        fun succeedAwaitableCallback() {
            val submitted = submission!!
            callback?.invoke(
                MissionCommandCallback(
                    submitted.mission,
                    submitted.commandGeneration,
                    null,
                ),
            )
        }

        private fun emit(snapshot: MissionSnapshot) {
            listeners.toList().forEach { it(snapshot) }
        }
    }
}
