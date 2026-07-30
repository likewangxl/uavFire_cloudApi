package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.wayline.SerializedSnapshotObserver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MissionSafetyRereviewRegressionTest {
    private val mission = MissionExecutionKey(MissionIdentity("A", "a.kmz"), 31)
    private val breakpoint = MissionBreakpoint(1, 2, 0.4)
    private val control = FireControlSessionKey("fire-a", 41)

    @Test
    fun legacyCommandGenerationCannotSatisfyAwaitablePause() = runTest {
        val port = RereviewPort(MissionSnapshot(mission, ObservedMissionState.EXECUTING, 5), breakpoint)
        val safety = OwnedResumeSafetyEvidenceProvider()
        val control = AwaitableMissionControl(port, {}, this, FlightSafetyGate(), safety) { 1_000 }
        val pending = async { control.pause() }
        advanceUntilIdle()

        port.emitLegacyState(ObservedMissionState.INTERRUPTED)
        port.succeedAwaitableCommand()

        assertEquals(
            MissionHoldResult.ManualHold(FlightSafetyReason.PAUSE_COMMAND_FAILED),
            pending.await(),
        )
        control.close()
    }

    @Test
    fun successfulResumeIsTerminalForExactSessionAndOldTokenCannotReissue() = runTest {
        val port = RereviewPort(MissionSnapshot(mission, ObservedMissionState.INTERRUPTED, 7), breakpoint)
        val gate = FlightSafetyGate()
        val provider = OwnedResumeSafetyEvidenceProvider()
        assertTrue(provider.publish(validEvidence(gate)))
        val awaitable = AwaitableMissionControl(port, {}, this, gate, provider) { 1_000 }
        val token = MissionHoldToken(mission, breakpoint, 7)
        val first = async { awaitable.resume(token, control) }
        advanceUntilIdle()
        port.completeResume()
        assertEquals(MissionResumeResult.Resumed, first.await())

        port.setState(ObservedMissionState.INTERRUPTED)
        assertEquals(
            MissionResumeResult.ManualHold(FlightSafetyReason.UNKNOWN_MISSION_STATE),
            awaitable.resume(token, control),
        )
        assertEquals(1, port.resumeCalls)

        val otherControl = FireControlSessionKey(control.sessionId, control.generation + 1)
        assertEquals(
            MissionResumeResult.ManualHold(FlightSafetyReason.COMPETING_FIRE_SESSION),
            awaitable.resume(token, otherControl),
        )
        assertEquals(1, port.resumeCalls)
        awaitable.close()
    }

    @Test
    fun invalidationAndSubmissionClaimHaveOneAtomicOrder() {
        val gate = FlightSafetyGate()
        val provider = OwnedResumeSafetyEvidenceProvider()
        val evidence = validEvidence(gate)
        assertTrue(provider.publish(evidence))
        val versioned = provider.current(control)!!

        assertTrue(provider.invalidate(control))
        assertNull(provider.claim(versioned))

        assertTrue(provider.publish(validEvidence(gate)))
        val claimedVersion = provider.current(control)!!
        val claim = provider.claim(claimedVersion)!!
        assertTrue(provider.invalidate(control))
        assertFalse(provider.commitSubmitted(claim))
        assertNull(provider.current(control))

        assertTrue(provider.publish(validEvidence(gate)))
        val released = provider.claim(provider.current(control)!!)!!
        assertTrue(provider.invalidate(control))
        assertTrue(provider.release(released))
        assertFalse(provider.release(claim))
        assertNull(provider.current(control))

        assertTrue(provider.publish(validEvidence(gate)))
        val committed = provider.claim(provider.current(control)!!)!!
        assertTrue(provider.commitSubmitted(committed))
        assertFalse(provider.invalidate(control))
        assertNull(provider.current(control))
    }

    @Test
    fun cancellationBeforeBoundaryReleasesClaimAndAppliesPendingInvalidation() = runTest {
        val port = RereviewPort(MissionSnapshot(mission, ObservedMissionState.INTERRUPTED, 7), breakpoint)
        port.stopBeforeBoundary = true
        val gate = FlightSafetyGate()
        val provider = OwnedResumeSafetyEvidenceProvider()
        assertTrue(provider.publish(validEvidence(gate)))
        port.beforeBoundary = { provider.invalidate(control) }
        val awaitable = AwaitableMissionControl(port, {}, this, gate, provider) { 1_000 }
        val pending = async { awaitable.resume(MissionHoldToken(mission, breakpoint, 7), control) }
        advanceUntilIdle()
        pending.cancelAndJoin()

        assertNull(provider.current(control))
        awaitable.close()
    }

    @Test
    fun rthLaserAndManualInvalidationWinningBeforeClaimAlwaysBlockSubmission() = runTest {
        val unsafeReplacements = listOf<(ResumeSafetyEvidence) -> ResumeSafetyEvidence>(
            { it.copy(signals = it.signals.copy(rthActive = true)) },
            { it.copy(laserEnabled = true) },
            { it.copy(signals = it.signals.copy(manualTakeover = true)) },
        )
        unsafeReplacements.forEach { makeUnsafe ->
            val port = RereviewPort(
                MissionSnapshot(mission, ObservedMissionState.INTERRUPTED, 7),
                breakpoint,
            )
            val gate = FlightSafetyGate()
            val owner = OwnedResumeSafetyEvidenceProvider()
            val safe = validEvidence(gate)
            assertTrue(owner.publish(safe))
            val provider = ClaimHookProvider(owner) {
                assertTrue(owner.publish(makeUnsafe(safe)))
            }
            val awaitable = AwaitableMissionControl(port, {}, this, gate, provider) { 1_000 }

            assertEquals(
                MissionResumeResult.ManualHold(FlightSafetyReason.TELEMETRY_STALE),
                awaitable.resume(MissionHoldToken(mission, breakpoint, 7), control),
            )
            assertEquals(0, port.resumeCalls)
            awaitable.close()
        }
    }

    @Test
    fun rthLaserAndManualInvalidationWinningAfterClaimBlockRawBoundary() = runTest {
        val unsafeReplacements = listOf<(ResumeSafetyEvidence) -> ResumeSafetyEvidence>(
            { it.copy(signals = it.signals.copy(rthActive = true)) },
            { it.copy(laserEnabled = true) },
            { it.copy(signals = it.signals.copy(manualTakeover = true)) },
        )
        unsafeReplacements.forEach { makeUnsafe ->
            val port = RereviewPort(
                MissionSnapshot(mission, ObservedMissionState.INTERRUPTED, 7),
                breakpoint,
            )
            val gate = FlightSafetyGate()
            val owner = OwnedResumeSafetyEvidenceProvider()
            val safe = validEvidence(gate)
            assertTrue(owner.publish(safe))
            port.beforeBoundary = { assertTrue(owner.publish(makeUnsafe(safe))) }
            val awaitable = AwaitableMissionControl(port, {}, this, gate, owner) { 1_000 }

            assertEquals(
                MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_COMMAND_FAILED),
                awaitable.resume(MissionHoldToken(mission, breakpoint, 7), control),
            )
            assertEquals(0, port.rawResumeCalls)
            awaitable.close()
        }
    }

    @Test
    fun manualTakeoverCancellationAfterClaimStillBlocksRawBoundary() = runTest {
        val port = RereviewPort(
            MissionSnapshot(mission, ObservedMissionState.INTERRUPTED, 7),
            breakpoint,
        )
        val gate = FlightSafetyGate()
        val owner = OwnedResumeSafetyEvidenceProvider()
        assertTrue(owner.publish(validEvidence(gate)))
        lateinit var awaitable: AwaitableMissionControl
        awaitable = AwaitableMissionControl(port, {}, this, gate, owner) { 1_000 }
        port.beforeBoundary = { awaitable.onManualControlTakeover() }

        assertEquals(
            MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_OUTCOME_UNKNOWN),
            awaitable.resume(MissionHoldToken(mission, breakpoint, 7), control),
        )
        assertEquals(0, port.rawResumeCalls)
        assertTrue(owner.current(control) != null)
        awaitable.close()
    }

    @Test
    fun oldHoverProofFailsAfterObservationGapAndProofIsSingleUse() {
        val gate = FlightSafetyGate()
        val evidence = validEvidence(gate)
        val binding = HoverControlBinding(control, mission, 7)

        gate.observeHover(binding, 0, sample(1_600))
        val currentSampleWithOldProof = evidence.copy(
            telemetry = sample(1_600),
            observedAtMonotonicMs = 1_600,
        )
        assertEquals(
            ResumeSafetyDecision.ManualHold(FlightSafetyReason.HOVER_NOT_STABLE),
            gate.evaluateResume(control, mission, breakpoint, 7, currentSampleWithOldProof, 1_600),
        )

        val fresh = validEvidence(gate)
        assertEquals(
            ResumeSafetyDecision.Permitted,
            gate.evaluateResume(control, mission, breakpoint, 7, fresh, 1_000),
        )
        assertEquals(
            ResumeSafetyDecision.ManualHold(FlightSafetyReason.HOVER_NOT_STABLE),
            gate.evaluateResume(control, mission, breakpoint, 7, fresh, 1_000),
        )
    }

    @Test
    fun telemetryCaptureAfterObservationFailsTypedStale() {
        val gate = FlightSafetyGate()
        val binding = HoverControlBinding(control, mission, 7)
        assertEquals(
            HoverSafetyDecision.ManualHold(FlightSafetyReason.TELEMETRY_STALE),
            gate.observeHover(
                binding,
                0,
                FlightTelemetrySample(101, 100, 0.0, 0.0),
                nowMs = 100,
            ),
        )
    }

    @Test
    fun serializedObserverAllowsSelfRemovalAndPreservesSecondObserver() {
        val events = mutableListOf<String>()
        lateinit var first: SerializedSnapshotObserver<Int>
        first = SerializedSnapshotObserver {
            events += "first:$it"
            first.deactivate()
        }
        val second = SerializedSnapshotObserver<Int> { events += "second:$it" }

        first.enqueue(1)
        second.enqueue(1)
        listOf(first, second).forEach { it.drain() }
        first.enqueue(2)
        second.enqueue(2)
        listOf(first, second).forEach { it.drain() }

        assertEquals(listOf("first:1", "second:1", "second:2"), events)
    }

    @Test
    fun concurrentDrainHasOneDrainerAndDeactivateClearsPendingDelivery() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = mutableListOf<Int>()
        val observer = SerializedSnapshotObserver<Int> {
            synchronized(events) { events += it }
            if (it == 1) {
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
            }
        }
        observer.enqueue(1)
        val firstDrainer = thread(start = true) { observer.drain() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        observer.enqueue(2)
        val competingDrainer = thread(start = true) { observer.drain() }
        competingDrainer.join(2_000)
        release.countDown()
        firstDrainer.join(2_000)
        assertFalse(firstDrainer.isAlive)
        assertFalse(competingDrainer.isAlive)
        assertEquals(listOf(1, 2), synchronized(events) { events.toList() })

        observer.enqueue(3)
        observer.deactivate()
        observer.drain()
        assertEquals(listOf(1, 2), synchronized(events) { events.toList() })
    }

    @Test
    fun throwingObserverIsDeactivatedAndCannotPoisonOtherObservers() {
        var throwingCalls = 0
        val throwing = SerializedSnapshotObserver<Int> {
            throwingCalls++
            throw IllegalStateException("observer failure")
        }
        val received = mutableListOf<Int>()
        val healthy = SerializedSnapshotObserver<Int> { received += it }
        throwing.enqueue(1)
        throwing.enqueue(2)
        healthy.enqueue(1)
        healthy.enqueue(2)

        listOf(throwing, healthy).forEach { it.drain() }
        throwing.enqueue(3)
        healthy.enqueue(3)
        listOf(throwing, healthy).forEach { it.drain() }

        assertEquals(1, throwingCalls)
        assertEquals(listOf(1, 2, 3), received)
    }

    @Test
    fun fatalObserverErrorIsCleanedUpThenRethrown() {
        var calls = 0
        val observer = SerializedSnapshotObserver<Int> {
            calls++
            throw AssertionError("fatal")
        }
        observer.enqueue(1)
        observer.enqueue(2)
        try {
            observer.drain()
            throw AssertionError("fatal Error should be rethrown")
        } catch (expected: AssertionError) {
            assertEquals("fatal", expected.message)
        }
        observer.enqueue(3)
        observer.drain()
        assertEquals(1, calls)
    }

    private fun validEvidence(gate: FlightSafetyGate): ResumeSafetyEvidence {
        gate.reset()
        val binding = HoverControlBinding(control, mission, 7)
        gate.observeHover(binding, 0, sample(0))
        gate.observeHover(binding, 0, sample(500))
        val stable = gate.observeHover(binding, 0, sample(1_000)) as HoverSafetyDecision.Stable
        return ResumeSafetyEvidence(
            controlSession = control,
            mission = mission,
            breakpoint = breakpoint,
            pausedCommandGeneration = 7,
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

    private fun sample(now: Long) = FlightTelemetrySample(now, now, 0.0, 0.0)

    private class ClaimHookProvider(
        private val delegate: ResumeSafetyEvidenceProvider,
        private val beforeClaim: () -> Unit,
    ) : ResumeSafetyEvidenceProvider {
        private var invoked = false

        override fun current(controlSession: FireControlSessionKey) =
            delegate.current(controlSession)

        override fun claim(versioned: VersionedResumeSafetyEvidence): ResumeSafetyEvidenceClaim? {
            if (!invoked) {
                invoked = true
                beforeClaim()
            }
            return delegate.claim(versioned)
        }

        override fun commitSubmitted(claim: ResumeSafetyEvidenceClaim) =
            delegate.commitSubmitted(claim)

        override fun release(claim: ResumeSafetyEvidenceClaim) =
            delegate.release(claim)
    }

    private class RereviewPort(
        private var current: MissionSnapshot,
        private val breakpoint: MissionBreakpoint,
    ) : MissionControlPort {
        var resumeCalls = 0
        var rawResumeCalls = 0
        var beforeBoundary: (() -> Unit)? = null
        var stopBeforeBoundary = false
        private var commandCallback: ((MissionCommandCallback) -> Unit)? = null
        private var submitted: MissionCommandSubmission? = null
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
            commandCallback = callback
            return newSubmission(mission, onSubmissionBoundary)
        }

        override fun resume(
            mission: MissionExecutionKey,
            breakpoint: MissionBreakpoint,
            onSubmissionBoundary: (MissionCommandSubmission) -> Unit,
            callback: (MissionCommandCallback) -> Unit,
        ): MissionCommandSubmission {
            resumeCalls++
            commandCallback = callback
            beforeBoundary?.invoke()
            if (stopBeforeBoundary) {
                return MissionCommandSubmission(
                    mission,
                    current.commandGeneration,
                    MissionCancellation {},
                )
            }
            return newSubmission(mission, onSubmissionBoundary).also { rawResumeCalls++ }
        }

        override fun observeSnapshots(listener: (MissionSnapshot) -> Unit): MissionCancellation {
            listeners += listener
            listener(current)
            return MissionCancellation { listeners -= listener }
        }

        fun emitLegacyState(state: ObservedMissionState) {
            current = current.copy(state = state, commandGeneration = current.commandGeneration + 1)
            listeners.toList().forEach { it(current) }
        }

        fun succeedAwaitableCommand() {
            val value = submitted ?: return
            commandCallback?.invoke(MissionCommandCallback(value.mission, value.commandGeneration, null))
        }

        fun completeResume() {
            val value = submitted!!
            current = current.copy(
                state = ObservedMissionState.EXECUTING,
                commandGeneration = value.commandGeneration,
            )
            listeners.toList().forEach { it(current) }
            commandCallback?.invoke(MissionCommandCallback(value.mission, value.commandGeneration, null))
        }

        fun setState(state: ObservedMissionState) {
            current = current.copy(state = state)
            listeners.toList().forEach { it(current) }
        }

        private fun newSubmission(
            mission: MissionExecutionKey,
            boundary: (MissionCommandSubmission) -> Unit,
        ): MissionCommandSubmission {
            current = current.copy(commandGeneration = current.commandGeneration + 1)
            listeners.toList().forEach { it(current) }
            return MissionCommandSubmission(
                mission,
                current.commandGeneration,
                MissionCancellation {},
            ).also {
                submitted = it
                boundary(it)
            }
        }
    }
}
