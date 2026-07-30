package com.yinxin.uavfir.firedetection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AwaitableMissionControlTest {
    private val identity = MissionIdentity("mission-1", "route.kmz")
    private val breakpoint = MissionBreakpoint(2, 7, 0.35, 34.1, 108.9, 70.0, "GoBackToRecordPoint")

    @Test
    fun pauseCompletesOnlyAfterCallbackAndInterruptedStateInEitherOrder() = runTest {
        listOf(true, false).forEach { callbackFirst ->
            val port = FakeMissionPort(MissionSnapshot(identity, ObservedMissionState.EXECUTING), breakpoint)
            val control = AwaitableMissionControl(port, hover = { port.hoverCalls++ }, scope = this)
            val result = async { control.pause() }
            advanceUntilIdle()

            if (callbackFirst) {
                port.succeedPause()
                advanceUntilIdle()
                assertFalse(result.isCompleted)
                port.emit(ObservedMissionState.INTERRUPTED)
            } else {
                port.emit(ObservedMissionState.INTERRUPTED)
                advanceUntilIdle()
                assertFalse(result.isCompleted)
                port.succeedPause()
            }

            assertEquals(MissionHoldResult.WaylinePaused(MissionHoldToken(identity, breakpoint)), result.await())
            control.close()
        }
    }

    @Test
    fun resumeCompletesOnlyAfterCallbackAndExecutingStateAndUsesExactBreakpoint() = runTest {
        val port = FakeMissionPort(MissionSnapshot(identity, ObservedMissionState.INTERRUPTED), breakpoint)
        val control = AwaitableMissionControl(port, hover = {}, scope = this)
        val token = MissionHoldToken(identity, breakpoint)
        val result = async { control.resume(token, validResumeContext()) }
        advanceUntilIdle()

        assertEquals(breakpoint, port.resumeBreakpoint)
        port.succeedResume()
        advanceUntilIdle()
        assertFalse(result.isCompleted)
        port.emit(ObservedMissionState.EXECUTING)

        assertEquals(MissionResumeResult.Resumed, result.await())
        control.close()
    }

    @Test
    fun repeatedCallsCoalesceAndAlreadySatisfiedCallsAreIdempotent() = runTest {
        val port = FakeMissionPort(MissionSnapshot(identity, ObservedMissionState.EXECUTING), breakpoint)
        val control = AwaitableMissionControl(port, hover = {}, scope = this)
        val first = async { control.pause() }
        val second = async { control.pause() }
        advanceUntilIdle()
        assertEquals(1, port.pauseCalls)
        assertEquals(1, port.queryCalls)
        port.succeedPause()
        port.emit(ObservedMissionState.INTERRUPTED)
        val token = (first.await() as MissionHoldResult.WaylinePaused).token
        assertEquals(first.getCompleted(), second.await())
        assertEquals(MissionHoldResult.WaylinePaused(token), control.pause())
        assertEquals(1, port.pauseCalls)

        val context = validResumeContext()
        val resume1 = async { control.resume(token, context) }
        val resume2 = async { control.resume(token, context) }
        advanceUntilIdle()
        assertEquals(1, port.resumeCalls)
        port.succeedResume()
        port.emit(ObservedMissionState.EXECUTING)
        assertEquals(MissionResumeResult.Resumed, resume1.await())
        assertEquals(MissionResumeResult.Resumed, resume2.await())
        assertEquals(MissionResumeResult.Resumed, control.resume(token, context))
        assertEquals(1, port.resumeCalls)
        control.close()
    }

    @Test
    fun cancellationCleansListenersAndLateCallbacksCannotCompleteCaller() = runTest {
        val port = FakeMissionPort(MissionSnapshot(identity, ObservedMissionState.EXECUTING), breakpoint)
        val control = AwaitableMissionControl(port, hover = {}, scope = this)
        val job = launch {
            try {
                control.pause()
            } catch (_: CancellationException) {
                throw CancellationException()
            }
        }
        advanceUntilIdle()
        assertEquals(1, port.listenerCount)
        job.cancelAndJoin()
        assertEquals(0, port.listenerCount)
        port.succeedPause()
        port.emit(ObservedMissionState.INTERRUPTED)
        assertTrue(job.isCancelled)
        control.close()
    }

    @Test
    fun noWaylineUsesExplicitHoverButUnknownOrActiveFailureEntersManualHold() = runTest {
        val noWayline = FakeMissionPort(MissionSnapshot(null, ObservedMissionState.IDLE), null)
        val noWaylineControl = AwaitableMissionControl(noWayline, hover = { noWayline.hoverCalls++ }, scope = this)
        assertEquals(MissionHoldResult.HoveringNoWayline, noWaylineControl.pause())
        assertEquals(1, noWayline.hoverCalls)
        assertEquals(0, noWayline.pauseCalls)
        noWaylineControl.close()

        val unknown = FakeMissionPort(MissionSnapshot(identity, ObservedMissionState.UNKNOWN), breakpoint)
        val unknownControl = AwaitableMissionControl(unknown, hover = { unknown.hoverCalls++ }, scope = this)
        assertEquals(
            MissionHoldResult.ManualHold(FlightSafetyReason.UNKNOWN_MISSION_STATE),
            unknownControl.pause(),
        )
        assertEquals(0, unknown.hoverCalls)
        unknownControl.close()

        val active = FakeMissionPort(MissionSnapshot(identity, ObservedMissionState.EXECUTING), breakpoint)
        val activeControl = AwaitableMissionControl(active, hover = { active.hoverCalls++ }, scope = this)
        val failed = async { activeControl.pause() }
        advanceUntilIdle()
        active.failPause()
        assertEquals(
            MissionHoldResult.ManualHold(FlightSafetyReason.PAUSE_COMMAND_FAILED),
            failed.await(),
        )
        assertEquals(0, active.hoverCalls)
        activeControl.close()
    }

    @Test
    fun breakpointMissingOrChangedFailsClosedAndResumeFailureIsOneShot() = runTest {
        val missing = FakeMissionPort(MissionSnapshot(identity, ObservedMissionState.EXECUTING), null)
        val missingControl = AwaitableMissionControl(missing, hover = {}, scope = this)
        assertEquals(
            MissionHoldResult.ManualHold(FlightSafetyReason.MISSING_BREAKPOINT),
            missingControl.pause(),
        )
        assertEquals(0, missing.pauseCalls)
        missingControl.close()

        val changed = breakpoint.copy(segmentProgress = 0.5)
        val port = FakeMissionPort(MissionSnapshot(identity, ObservedMissionState.INTERRUPTED), changed)
        val control = AwaitableMissionControl(port, hover = {}, scope = this)
        assertEquals(
            MissionResumeResult.ManualHold(FlightSafetyReason.BREAKPOINT_MISMATCH),
            control.resume(MissionHoldToken(identity, breakpoint), validResumeContext()),
        )
        assertEquals(0, port.resumeCalls)

        port.breakpoint = breakpoint
        val failed = async {
            control.resume(MissionHoldToken(identity, breakpoint), validResumeContext())
        }
        advanceUntilIdle()
        port.failResume()
        assertEquals(
            MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_COMMAND_FAILED),
            failed.await(),
        )
        assertEquals(1, port.resumeCalls)
        assertEquals(
            MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_COMMAND_FAILED),
            control.resume(MissionHoldToken(identity, breakpoint), validResumeContext()),
        )
        assertEquals(1, port.resumeCalls)
        control.close()
    }

    @Test
    fun manualTakeoverCancelsAutomatedProgressionWithTypedManualHold() = runTest {
        val port = FakeMissionPort(MissionSnapshot(identity, ObservedMissionState.EXECUTING), breakpoint)
        val control = AwaitableMissionControl(port, hover = {}, scope = this)
        val pending = async { control.pause() }
        advanceUntilIdle()
        control.onManualControlTakeover()
        assertEquals(
            MissionHoldResult.ManualHold(FlightSafetyReason.MANUAL_CONTROL_TAKEOVER),
            pending.await(),
        )
        assertEquals(0, port.listenerCount)
        control.close()
    }

    private fun validResumeContext() = ResumeSafetyContext(
        terminalResultDurable = true,
        expectedMission = identity,
        observedMission = identity,
        expectedBreakpoint = breakpoint,
        observedBreakpoint = breakpoint,
        laserEnabled = false,
        targetAlignmentClosed = true,
        anotherFireSessionActive = false,
        missionStateKnown = true,
        telemetryFresh = true,
    )

    private class FakeMissionPort(
        private var snapshot: MissionSnapshot,
        var breakpoint: MissionBreakpoint?,
    ) : MissionControlPort {
        var queryCalls = 0
        var pauseCalls = 0
        var resumeCalls = 0
        var hoverCalls = 0
        var resumeBreakpoint: MissionBreakpoint? = null
        private var queryCallback: ((MissionBreakpoint?, MissionCommandError?) -> Unit)? = null
        private var pauseCallback: ((MissionCommandError?) -> Unit)? = null
        private var resumeCallback: ((MissionCommandError?) -> Unit)? = null
        private val listeners = linkedSetOf<(ObservedMissionState) -> Unit>()
        val listenerCount: Int get() = listeners.size

        override fun snapshot(): MissionSnapshot = snapshot

        override fun queryBreakpoint(
            identity: MissionIdentity,
            callback: (MissionBreakpoint?, MissionCommandError?) -> Unit,
        ): MissionCancellation {
            queryCalls++
            queryCallback = callback
            callback(breakpoint, null)
            return MissionCancellation { queryCallback = null }
        }

        override fun pause(callback: (MissionCommandError?) -> Unit): MissionCancellation {
            pauseCalls++
            pauseCallback = callback
            return MissionCancellation { pauseCallback = null }
        }

        override fun resume(
            breakpoint: MissionBreakpoint,
            callback: (MissionCommandError?) -> Unit,
        ): MissionCancellation {
            resumeCalls++
            resumeBreakpoint = breakpoint
            resumeCallback = callback
            return MissionCancellation { resumeCallback = null }
        }

        override fun observeState(listener: (ObservedMissionState) -> Unit): MissionCancellation {
            listeners += listener
            return MissionCancellation { listeners -= listener }
        }

        fun emit(state: ObservedMissionState) {
            snapshot = snapshot.copy(state = state)
            listeners.toList().forEach { it(state) }
        }

        fun succeedPause() = pauseCallback?.invoke(null)
        fun failPause() = pauseCallback?.invoke(MissionCommandError("pause-failed"))
        fun succeedResume() = resumeCallback?.invoke(null)
        fun failResume() = resumeCallback?.invoke(MissionCommandError("resume-failed"))
    }
}
